# Placement lock leak — isolated fault-injection lab

## Finding

Echo can turn a transient Redis transport error into an indefinitely renewed
global admission outage. With Redisson 3.29.0's default retries, dropping one
successful acquisition response causes the acquisition to execute twice. The
publication returns normally, but its single unlock leaves a residual hold.
With retries disabled, uncertain acquisition or failed release also leaves a
hold. That hold is reentered by the same admission writer on its next refresh.
Each successful refresh increments the hold count from 1 to 2, then decrements
it back to 1, refreshing the TTL rather than releasing the lock.

This is a leaked reentrant lock hold, not evidence of two threads waiting in a
cyclic deadlock. The affected publisher can appear healthy after the original
error while every other server fails to acquire `placement:lock`.

Source under test: Echo `dfaa9ba5182a840e5f6819bc3d574f7605a51851` (7.2.0).
Diagnosis dated 2026-09-24; preprod incident/recovery recorded as 2026-09-23.

## Actual call chain

- `paper/.../listener/AdmissionListener.java:59–61` creates a single persistent
  platform-thread writer. Its `refresh()` catches runtime failures inside the
  task (lines 162–168), preserving that worker and allowing subsequent refreshes.
- `core/.../server/placement/RedisServerPlacement.java:72–76` wraps admission
  publication in the fleet-wide `placement:lock`.
- `withLock()` (lines 446–460) calls `tryLock()` before the action's `try/finally`.
  An acquisition that executes remotely but whose response is lost can throw
  before entering the `finally`. Conversely, `unlock()` itself can fail before
  the release executes remotely. A `finally` guarantees an attempt, not a
  successful distributed release. More importantly, a retried acquisition can
  succeed twice without throwing: the one `finally` unlock cannot balance two
  remote increments.
- Redisson 3.29.0 `RedissonLock.java:218–227`: the owner is client ID + thread ID;
  acquisition by that same owner runs `HINCRBY +1` and `PEXPIRE`.
  Lines 344–367: unlock decrements, and if the count remains positive it renews
  the TTL instead of deleting the lock.
- `RedisServerPlacement.admit()` catches contention and returns `false`
  (lines 111–112). Paper translates this into `This server has no available
  slot.` (AdmissionListener lines 78–79, also 101–102), even with zero occupants.

## Lab design and limits

Dedicated Redis 8.0.3 bound to loopback port 16379, without persistence. A
loopback-only RESP proxy on 16380 injects one transport fault; a separate client
connects directly to Redis to inspect state and execute the real lobby `admit()`.
Control port 16381 arms only the next matching command. No cluster connections,
real users, or production credentials are used.

The harness calls the actual unmodified `RedisServerPlacement.publishAdmission`
and `admit` methods. It reuses one Java thread for sequential publications,
matching Paper's writer ownership pattern. It does not launch Paper or simulate
a complete Minecraft handshake. The mapping from `admit=false` to the player
message is verified in the source above.

Fast cases use a 3-second watchdog, a 400-ms response timeout, zero retries and
publications every 400 ms. Each case runs beyond the watchdog duration. The
default-timing cases retain Redisson's watchdog, timeout and retry defaults and
publish every second for 34 iterations. Only the local connection pool sizes are
reduced. These defaults are not asserted to be the exact deployed Redis config.

Faults:

1. `unlock-lost`: close the connection before forwarding the unlock request to
   Redis. Redis has the acquired hold but never executes that release.
2. `acquire-reply-lost`: forward the acquisition and read Redis's successful
   response, then close the connection without delivering the response.
3. `baseline`: no injection; the lobby must admit normally.

For each fault, a control pauses all publisher activity longer than the watchdog
before resuming. The idle control distinguishes normal TTL recovery from
perpetuation by the recurring writer. The proxy must confirm fault injection;
an unrelated build/startup failure is not a reproduced bug.

## Relation to preprod

The incident had the same signature: one SG owner retained count 1, with an
approximately 30-second TTL renewed every second, while a 0/120 lobby rejected
admission. Draining the empty SG stopped the recurring owner; the lock expired
and lobby publication recovered.

The lab proves **two sufficient fault types** for that signature, with different
retry sensitivity. A single lost unlock is recovered under the tested default
retry settings; a lost acquisition response still leaks, silently. It does not prove
which transport event originally occurred in preprod. Distinguishing acquisition
response loss from release failure requires historical logs at the first leaked
hold. Do not attribute the incident to a particular network outage, Redis failover
or Redisson retry without that evidence.

## Results and decisive trace

Initial 16-case characterization:

| Configuration | Scenario | Observations |
|---|---|---|
| 3.29.0 and 3.32.0, fast | No fault | Both controls admit throughout, no residual lock |
| Both versions, fast | Lost unlock, recurring publisher | 4/4 runs remain blocked with count 1 |
| Both versions, fast | Lost acquisition response, recurring publisher | 4/4 runs remain blocked with count 1 |
| Both versions, fast | Either fault, pause beyond TTL first | 4/4 recover; idle lock expires |
| 3.29.0, default | One lost unlock | Recovers through retry; no leaked hold |
| 3.29.0, default | One lost acquisition response | Publication reports success, residual hold 1, all 34 subsequent admissions denied |

The original matrix expected the default lost-unlock case to fail and therefore
exited nonzero on that case. This falsified that expectation and narrowed the
diagnosis. The retained runner classifies that case as a recovery control; the
original evidence is preserved rather than relabeled as a successful initial run.

A second default-3.29.0 acquisition run, using the retained repository lab harness and a
proxy trace, reproduced the same failure. The first publication after injection
performed exactly:

```text
acquire -> Redis nil (success); reply deliberately dropped
acquire -> Redis nil (success); reply delivered
unlock  -> Redis integer 0 (still held); reply delivered
FAULT publication returned normally
AFTER FAULT holds={same-client:same-thread=1}
TICK 33 holds={same-client:same-thread=1} ttl≈30000 emptyLobbyAdmits=false
```

Thus the replay is observed on the wire, not inferred solely from source. The
acquire Lua script increments a counter without an operation-level deduplication
token. By contrast the unlock script has a request-specific latch, explaining
why retrying a single lost unlock can recover safely in the tested case.

The isolated runs demonstrate that subsequent refreshes alone can sustain the
hold; the fast pause controls show that merely blaming the watchdog is
insufficient. Source reference: Redisson `RedissonBaseLock.java:293–313` cancels
renewal when unlock completes or fails.

Detailed console logs and initial matrix JSON are stored locally under
`build/placement-lock-lab/` (ignored build artifacts).

Two final targeted controls confirmed:

- **Redisson 3.32.0 with defaults** also silently replays the acquisition and
  leaves count 1; 34 consecutive later admissions are denied. Updating from
  3.29.0 to the version already used by Echo's tests is not a fix.
- **Redisson 3.29.0 with defaults, pause 34 seconds before resuming**: the exact
  acquire/acquire/unlock sequence first leaves count 1, but the idle hold then
  expires and all 34 subsequent admissions succeed. The repeated publisher,
  rather than an independently surviving watchdog alone, sustains the leak.

Evidence comprises 19 captured case logs plus two JSON summaries: the initial
16 cases, the repeated default acquisition trace, and the two final controls.
The aggregate runner now includes all 19 configurations and the corrected
single-lost-unlock expectation. The cases were executed in those separate
batches, not as a second full aggregate run. The retained assertion checks
admission at the end of the recovery window, allowing transient failures before
then; baseline/post-pause controls additionally require admission throughout.

Both local services were stopped after verification. Runtime source files are
unchanged; only this opt-in lab/report was added. No commit or push was made.

## Correction requirements

- Prevent an ambiguous operation from being treated as legitimate reentrancy by
  later, unrelated publications. Evaluate operation-specific ownership or a
  recovery/quarantine state that prevents reuse of the uncertain owner. Preserve
  exclusive ownership for every mutation; do not blindly force-unlock.
- A more structural option is to move admission/reservation mutations into
  idempotent atomic Redis operations, avoiding an application-held global lock.
  Preserve cross-server request idempotency, party capacity and lease/token
  semantics; simply splitting the lock by server is not sufficient proof.
- Keep these fault-injection cases as acceptance tests: after connectivity
  recovers, no persistent residual hold; a free lobby admits within a bounded
  recovery window; concurrent admissions never overbook.
- Report contention separately from genuine capacity exhaustion, and observe
  admission age and abnormal lock-hold duration. These improve diagnosis but do
  not repair the locking protocol.

A fixed lease or larger timeout alone is not a demonstrated fix. Expiring a lease
while its action still writes can allow concurrent writers; a shorter TTL needs
an appropriate ownership/fencing protocol.

No production source fix or deployment is included in this lab.

Proposed implementation and coordinated rollout plan: [PLAN.md](PLAN.md).
