# Plan de correction — placement atomique et rejouable

Date : 2026-09-24. Statut : **plan proposé ; implémentation non commencée**.
Diagnostic et preuves : [REPORT.md](REPORT.md).

## Décision recommandée

Remplacer le `RLock` global de `RedisServerPlacement` par un **commit Redis
atomique, conditionnel et idempotent**. Les règles métier restent en Java ; un
module interne de stockage vérifie que les données lues sont toujours valides et
applique les mutations en une seule exécution Lua.

Cela supprime l'acquisition/libération distribuée du chemin de placement. Une
réponse perdue peut provoquer un retry, mais pas ajouter une acquisition ni
dupliquer une réservation. Un client arrêté ne laisse aucun verrou à renouveler.

Le module proposé, `RedisPlacementStore`, concentre lecture cohérente,
validation, déduplication, expiration et commit. Son interface interne reste
petite : lecture d'un état cohérent et tentative de commit d'une décision. Pas de
nouveau module générique de verrouillage, ni de hiérarchie publique de stores.
L'interface publique `ServerPlacement` reste le contrat métier.

### Pourquoi ce choix

Le laboratoire a montré une fuite **sans exception** avec les retries par
défaut. Une récupération exécutée seulement dans un `catch` ne suffit donc pas.
Un propriétaire différent à chaque appel peut limiter la perpétuation, mais ne
rend pas à lui seul l'acquisition idempotente. Réduire le TTL sans protéger les
écritures permettrait à un ancien propriétaire d'écrire après perte du verrou.

La refonte atomique est plus large qu'une mitigation de quelques lignes, mais
traite ensemble le replay, l'absence de libération et les écritures tardives.
Le choix est une recommandation de conception à valider par les tests ci-dessous,
pas une solution déjà démontrée par le laboratoire.

## Invariants obligatoires

1. **Pas de sur-réservation** : union des membres online/joining/réservés, limites
   publique et physique, permissions staff de confiance, groupe indivisible,
   admission directe et réservations concurrentes restent cohérents.
2. **Replay sans nouvel effet** : un même identifiant d'opération et le même
   contenu donnent le résultat déjà enregistré ; un contenu différent est rejeté.
   L'idempotence métier de `Request.requestId` reste distincte et fonctionne même
   si deux clients utilisent des identifiants d'opération différents.
3. **Pas de résurrection** : expiration, ancien token de réservation, publication
   périmée et ancienne incarnation d'un serveur ne peuvent réactiver un droit ou
   écraser un état plus récent. Rejouer `renew` ne prolonge pas le bail deux fois.
4. **Reprise bornée** : après rétablissement de Redis, aucune attente d'un TTL de
   verrou ni d'un redémarrage d'instance ; seulement les budgets réseau/retry de
   l'opération. Une indisponibilité refuse l'accès, sans attribution non vérifiée.
5. **Protocole unique en service** : aucun mélange d'écrivains v1/RLock et
   v2/commit atomique dans le même réseau de placement pendant la bascule.

## Architecture du commit

### État et lecture

- Définir un schéma versionné `placement:v2:*`, avec génération de stockage et
  révision, admissions, réservations et résultats d'opérations. Une génération
  évite qu'une ancienne révision soit acceptée après une réinitialisation.
- Les réservations v2 utilisent un format explicitement possédé par Echo et une
  expiration logique/indexée. Ne pas manipuler en Lua le format interne opaque de
  `RMapCache` Redisson. La suppression physique des entrées expirées ne décide
  jamais à elle seule si une réservation est encore active.
- Une lecture cohérente retourne les données nécessaires, leur révision et une
  référence temporelle Redis. La sélection, les filtres, les empreintes et les
  calculs d'occupation restent en Java.
- **La révision seule ne suffit pas** : `servers:map`, heartbeat, disponibilité,
  load, capacités, propriétés de filtrage et classification staff sont aussi
  modifiés en dehors du placement. Capturer les entrées externes qui participent
  à la décision et les revérifier au commit, y compris existence, fraîcheur et
  expiration. Vérifier tous les candidats nécessaires au classement pour
  préserver les politiques et le départage stable, pas seulement le serveur élu.
- Les valeurs externes sont comparées avec le codec réellement fourni par
  Connector. Ne pas faire dépendre les scripts de noms de classes Jackson ou
  d'une sérialisation supposée. Ce plan cible Redis standalone/Sentinel actuel ;
  il ne prétend pas rendre atomiques des clés dispersées sur Redis Cluster.

### Mutation atomique

Le script, court et borné, procède à ces vérifications avant toute écriture :

1. génération, schéma, validité temporelle et empreinte de l'opération ;
2. résultat déjà enregistré pour cet identifiant, s'il existe ;
3. révision attendue et ensemble des entrées de lecture nécessaires ;
4. validité des tokens/baux et des conditions temporelles au moment du commit ;
5. mutations, nouvelle révision et résultat de l'opération, enregistrés ensemble.

Une réponse perdue après le point 5 retourne le résultat original au retry.
Un conflit avant ce point retourne `CONFLICT`, sans mutation : Java relit et
recalcule dans un budget borné, avec le même identifiant d'opération. Aucun
nouvel identifiant n'est généré pour contourner une réponse incertaine.

Prévalider types, arguments et écritures avant la première mutation : **Lua est
atomique vis-à-vis des autres clients, mais ne rollback pas les commandes déjà
exécutées si le script échoue ensuite**. Les erreurs de schéma/type doivent être
testées avant d'autoriser une promotion.

Les scripts ne contiennent ni attente active ni boucle de retry. Borner les
volumes traités et les tentatives Java pour éviter de remplacer le blocage du
verrou par une saturation Redis ou une boucle de conflits.

### Replays tardifs et budgets

- L'identifiant d'opération est créé avant le premier envoi ; l'empreinte porte
  sur la demande métier, indépendamment de la révision relue après un conflit.
- Définir un horizon maximal d'opération. Conserver sa déduplication au moins
  pendant cet horizon et refuser les opérations expirées même après nettoyage de
  leur résultat. Un replay arbitrairement tardif ne doit pas devenir une nouvelle
  mutation simplement parce que le cache de déduplication a expiré.
- Ne pas traiter un résultat d'admission ou de réservation expiré comme une
  autorisation actuelle. Vérifier encore les dates/token côté appelant.
- Ordonner les publications par incarnation du serveur et numéro de publication,
  pour qu'une ancienne publication retardée n'écrase pas une plus récente.
- Préserver l'attente Paper de 100 ms et l'absence d'autorisation après expiration
  de cette attente. Définir séparément le budget de travail Redis restant et le
  nettoyage conservateur d'une éventuelle réservation non utilisée.

## Lots d'exécution

### 1. Transformer le lab en contrat de non-régression — 0,5 jour

**Dépendance :** aucune.

- Conserver la reproduction originale sur la révision fautive, avec les logs
  acquire/acquire/unlock et le témoin sans panne.
- Intégrer l'injection de pertes avant/après exécution dans des tests Redis réels
  du module core, avec un writer durable et un client d'admission indépendant.
- Séparer explicitement caractérisation de l'ancien bug et tests du correctif :
  la matrice actuelle accepte des échecs attendus ; la nouvelle suite doit échouer
  si une admission reste bloquée après récupération.
- Rendre l'injection indépendante du texte Lua `hincrby` : viser les opérations
  du protocole v2 et vérifier que l'événement voulu a réellement été injecté.

**Sortie :** tests rouges pertinents sur la version actuelle, témoin vert,
scénarios et bornes temporelles déterministes, exécution sans préprod.

### 2. Implémenter le stockage atomique v2 — 1,5 à 2 jours

**Dépendance :** lot 1.

- Définir schéma, contrats de lecture/commit, conservation des résultats et
  nettoyage des expirations ; documenter les règles de temps et de génération.
- Implémenter `RedisPlacementStore` et les scripts sous
  `core/src/main/resources/.../placement/`.
- Tester replay réussi mais réponse perdue, conflits, expiration pendant une
  opération, ancien résultat rejoué et erreurs de types avant mutation.
- Vérifier l'exclusion de deux commits concurrents sur la dernière place : un
  seul valide la révision ; l'autre relit et constate la place occupée.

**Sortie :** store sans RLock, commits idempotents et atomiques, pas d'écriture
acceptée à partir d'un état invalidé.

### 3. Raccorder toutes les opérations et les erreurs — 0,5 à 1 jour

**Dépendance :** lot 2.

- Migrer `publishAdmission`, `admit`, `reserve`, `renew`, `release`, et les vues
  `listActiveReservations`, `findActiveReservation`, `inspectServer`, `explain`
  dans `RedisServerPlacement.java`. Supprimer `withLock` de ce chemin.
- Conserver politiques FILL/SPREAD, filtres exacts, classement stable, empreinte
  des requêtes et protection des tokens de réservation.
- Distinguer l'indisponibilité technique/conflits épuisés d'un refus métier.
  Pour l'admission Paper, utiliser une exception technique explicite que
  `AdmissionListener` transforme en message d'indisponibilité ; ne plus retourner
  `false` pour une indisponibilité de stockage et la présenter comme un lobby plein.
- Tracer de façon bornée identifiant d'opération, type d'échec, durée, conflits,
  replays et âge des admissions. Réutiliser le mécanisme de logs/métriques présent.

**Sortie :** un seul protocole utilisé par tous les chemins, erreurs joueur
correctes, aucun accès résiduel à `placement:lock` dans ce module.

### 4. Prouver reprise et conservation des places — 1 jour

**Dépendance :** lot 3.

Tests requis, regroupés en cinq familles :

1. Pertes de requête/réponse avant/après commit, retry automatique et timeout
   ambigu. Même résultat sans nouveau token, double prolongation ou réservation.
2. Arrêt du client après commit et réponse perdue, pause longue d'un worker,
   publication en retard. Les autres clients continuent et l'ancien ne réécrit
   pas un état périmé. Tester aussi interruption/failover Redis : refus en cas
   d'incertitude ; ne pas supposer qu'un Lua garantit à lui seul la durabilité
   d'une écriture perdue lors d'un failover asynchrone.
3. Dernière place disputée par deux réservations, admission directe et groupe ;
   limites public/staff/physique, joueurs communs et spectateurs. Tests concurrents
   ciblés, pas un benchmark ou un test de charge de la préprod.
4. Renouvellement/libération avec ancien token, requête identique ou différente,
   expiration des baux et résultats, nettoyage et données malformées. Les suites
   de comportement existantes restent la référence pour les règles métier.
5. Chemin Paper + Proxy/queue : délai de 100 ms, absence d'autorisation tardive,
   messages techniques, attente de transfert et libération après échec.

**Sortie :** tests de reprise verts sans arrêt manuel du publisher. Dans les cas
locaux à transport rétabli, fixer une cible de reprise <= 5 secondes avec
admission fraîche ; aucune attente des 30 secondes de l'ancien verrou. Documenter
les budgets exacts, sans étendre cette borne à une panne réseau toujours active.

Vérifier les dépendances effectivement embarquées par Connector et les deux
artefacts Echo Paper/Velocity. L'écart de test Redisson 3.32.0 / dépendance
Connector 3.29.0 ne doit pas masquer le résultat.

### 5. Préparer et exécuter la bascule préprod — 0,5 jour + drainage

**Dépendance :** lots 1–4 acceptés ; fenêtre de remplacement autorisée selon le
runbook infra. Le « Go » précédent concernait une instance SG, pas cette migration.

- Publier une nouvelle version immuable Echo, puis les images concernées de
  proxy, lobby et tous les jeux qui embarquent l'admission/réservation Echo.
  Inventorier aussi les consommateurs de queue/commandes partageant le protocole.
- Répéter la bascule en local. Préprod : contrôler le kubeconfig explicite et
  l'API `.230`, fermer temporairement les nouvelles admissions/transferts,
  drainer les instances et vérifier zéro joueur avant leur remplacement.
- Vérifier l'absence de tout écrivain v1, de transfert en cours et de réservation
  active avant ouverture de v2. Aucun double-write ou rolling update mixte. Les
  nouvelles admissions sont republiées à partir des serveurs prêts ; ne pas
  copier aveuglément les snapshots/leases transitoires v1 vers v2.
- Contrôler tous les artefacts réellement chargés, la publication fraîche, la
  réservation et le /hub, puis une connexion Minecraft et un transfert réel.
  Observer 30 minutes sans échec inexpliqué ; aucune injection réseau en préprod.
- Rollback : fermer l'accès, drainer et arrêter les écrivains v2, attendre/vérifier
  la fin des transferts et baux, puis restaurer les images v1 coordonnées avec un
  état transitoire cohérent. Pas de rollback d'un seul pod vers v1 dans un ensemble
  v2. Préserver snapshots techniques/état utile et suivre le runbook ; aucune
  suppression globale Redis ni synchronisation Argo complète.

**Sortie :** connexion et transfert confirmés en préprod, protocole homogène,
preuves consignée dans `infra/kube/STATE.md` et procédure de rollback disponible.

## Estimation et jalon de décision

Ordre : **1 → 2 → 3 → 4 → 5**. Estimation : **4 à 5 jours de travail**, hors
attente de drainage et de fenêtre de déploiement. Ajouter une marge si les tests
révèlent une contrainte de codec ou de failover non couverte par le protocole.

Le premier jalon est le lot 1 et le contrat détaillé du lot 2 : ils doivent rendre
vérifiables les lectures externes, replays tardifs et mutations atomiques avant
de raccorder Paper/Velocity. Une difficulté à garantir ces invariants impose une
révision du protocole, pas une désactivation des tests ou un `forceUnlock`.
