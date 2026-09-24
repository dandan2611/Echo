# Placement v2 — Echo 7.3.0

## Protocol

The global reentrant placement lock is removed. Java retains placement policy;
`RedisPlacementStore` commits decisions with a bounded optimistic Lua transaction.
The commit checks the placement revision, registry contents, every observed
external value, and applicable deadlines before writing. Conflicts recompute.
An operation UUID and a short-lived receipt make transport replay idempotent.
Opaque revisions avoid revision reuse, and expired operations cannot apply after
their receipt expires. Scripts validate key types before mutation: Redis Lua does
not roll back earlier writes on a runtime error.

Reservations live in the owned `placement:v2:reservations` hash. Lease creation,
expiry and cleanup use Redis TIME, not a worker's wall clock. Application snapshot
freshness still uses its application timestamps; an invalid snapshot fails closed.
Destination registration atomically claims a random publisher incarnation, invalidates
the previous admission snapshot, and starts a monotonically ordered publication
sequence. A replaced publisher cannot publish or admit using its old incarnation.
This startup registration happens once before the admission listener starts.
Its predecessor identity is fixed on the first attempt: a concurrent replacement
aborts delayed startup rather than allowing its retry to reclaim ownership.

The external reservation interface and capacity policy remain unchanged. Technical
unavailability is an exception, distinct from a full destination; Paper's existing
technical-error response handles it. A timed-out Paper login is never authorized
by a late commit reply. The implementation targets the existing standalone/Sentinel
Redis topology, not Redis Cluster multi-slot transactions. Atomic execution alone
does not guarantee durability across an asynchronous Redis failover.

## Verification

- Unit suites across modules and Paper/Velocity shadow builds pass locally.
- Eighteen Redis-backed placement/store integration tests pass, including
  orphaned legacy locks, last-seat contention, party/token semantics, changed
  routing inputs, malformed state, expired deadlines, application clock skew and
  replacement publisher fencing and delayed startup during replacement.
- Twenty-four fault cases pass with the actual Connector JSON codec and Redisson
  3.29.0 / 3.32.0. Lost request, lost reply and replay are tested on publish,
  reserve, renew and release. Confirmed effects are checked before recovery work.
  Healthy recovery measured 8–45 ms, below the five-second acceptance bound.
- Full Docker-backed build remains a CI release gate. Minecraft login, transfer,
  runtime image verification and observation are deployment acceptance checks.

The historical lab report remains evidence of the original failure mechanism;
its known-failure matrix is not the acceptance suite for this protocol.

## Coordinated deployment and rollback

1. Publish immutable Paper and Velocity artifacts together. Build all consumers
   against that exact release, including proxies, lobby and every game variant.
2. Close new admission/transfer entry points, drain all old writers, wait for zero
   players and completion/expiry of outstanding transfers and reservations.
3. Stop every v1 writer before starting v2 writers. Do not mix protocols: their
   reservation namespaces differ and they cannot enforce capacity jointly.
4. Start destinations and verify fresh fenced publication before opening proxies.
   Verify exact artifact/image digests, capacity decisions and real connections.
5. Observe publication errors, admission freshness and reservation progress for
   30 minutes. Rollback uses the same admission barrier, drainage and all-writer
   stop before restoring old image pins. Never roll back a single active writer.

Preserve rollback image pins. Rebuild ephemeral snapshots naturally; do not copy
legacy in-flight leases into the new namespace or flush shared Redis. Do not use
a broad infrastructure reconciliation that could replace unrelated live images.
