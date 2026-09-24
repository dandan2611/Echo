# Run the placement-lock lab

This is an opt-in fault harness. It compiles the real Echo core and uses a
dedicated local Redis. It is not wired into normal builds or deployment.
See [REPORT.md](REPORT.md) for the historical diagnosis and
[IMPLEMENTATION.md](IMPLEMENTATION.md) for the v2 protocol and coordinated rollout.

## Validate the correction (7.3.0+)

Start the disposable Redis and Python proxy described below, then run:

```sh
./gradlew -I lab/placement-lock/lab.init.gradle :core:lockLab -Dlab.main=PlacementFaultLab -Dlab.redisson=3.29.0 --console=plain
./gradlew -I lab/placement-lock/lab.init.gradle :core:lockLab -Dlab.main=PlacementFaultLab -Dlab.redisson=3.32.0 --console=plain
```

Each command runs 12 cases: lost commit request, lost commit response and replay,
across publication, reservation, renewal and release. Each case requires proof
that the fault fired, checks confirmed effects before repair, and requires healthy
admission/reservation to recover within five seconds. It uses disposable database
15 and the actual Connector JSON codec. Expected: both commands exit 0.

The original lock experiments below are **historical**, designed for Echo 7.2.0
at commit `dfaa9ba5182a840e5f6819bc3d574f7605a51851`. They do not characterize the
v2 protocol: its commits no longer contain the lock commands they intercept.

## Requirements

- Redis 8.0.3 (tested), Python 3, and Echo's Gradle/Java 21 toolchain.
- Free loopback ports 16379 (Redis), 16380 (fault proxy), 16381 (proxy control).
- A **disposable Redis instance**: the harness clears the named placement lock
  and two lab admission keys between cases. Do not forward a shared Redis into
  these ports. The clients/proxy are hardwired to loopback.
- Gradle dependencies cached for `--offline`; omit that flag on first use if
  dependencies need resolving.

## Reproduce the historical failure on 7.2.0

1. In a terminal, start the dedicated Redis with no persistence:

   ```sh
   redis-server --bind 127.0.0.1 --port 16379 --save "" --appendonly no
   ```

   The original experiment compiled Redis 8.0.3 from its release tarball in the
   approved OpenCode temp directory and ran this command in WSL. Redis remained
   reachable on Windows loopback through WSL localhost forwarding.

2. From the Echo root, in another terminal:

   ```sh
   python lab/placement-lock/proxy.py
   ```

3. Establish the no-fault control:

   ```sh
   ./gradlew -I lab/placement-lock/lab.init.gradle :core:lockLab -Dlab.mode=baseline --offline --console=plain
   ```

   Windows: replace `./gradlew` with `./gradlew.bat` (Git Bash) or
   `.\gradlew.bat` (PowerShell). Expected: exit 0, `PASS no leaked lock`.

4. Reproduce the silent acquisition replay with default Redisson settings:

   ```sh
   ./gradlew -I lab/placement-lock/lab.init.gradle :core:lockLab -Dlab.mode=acquire-reply-lost -Dlab.timings=default --offline --console=plain
   ```

   About 45 seconds. Expected on the affected code: exit 1 with
   `BUG: empty lobby remains rejected ... beyond watchdog TTL`. The fault
   publication itself returns normally; the trace shows acquire/acquire/unlock.

5. Stop both local services with Ctrl+C when finished. Nothing in this harness
   touches Kubernetes, starts a Minecraft server, or publishes artifacts.

## Other experiments

- `-Dlab.timings=fast` (default): 3-second watchdog, 400-ms response timeout,
  zero retries, 400-ms publications. About 10 seconds per run.
- `-Dlab.mode=unlock-lost`: drop one unlock before execution. Reproduces with
  retries disabled; default retries recover this single-drop case.
- `-Dlab.pause=true`: pause the publisher beyond the watchdog TTL immediately
  after the fault, then resume. Tests whether idle expiry restores admission.
- `-Dlab.redisson=3.32.0`: compare Echo's test dependency with Connector's
  declared Redisson 3.29.0 dependency (the lab default).

`python lab/placement-lock/matrix.py` runs the 19-case characterization (the
original 16 cases plus three targeted follow-ups) serially, about 7 minutes,
and saves logs/JSON in `build/placement-lock-lab/`.
Its overall success means **observed outcomes matched the known-bug matrix**;
the individual fault-reproduction runs deliberately fail. A durable fix must
make those individual recovery assertions green; update the characterization
expectations when that behavior changes.

The harness allows transient denials during recovery but fails if admission is
still refused at the end of a period longer than the watchdog TTL. No-fault and
post-pause controls must admit throughout. It verifies real placement admission,
not a full Minecraft connection.
