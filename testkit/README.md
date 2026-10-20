# Testkit

Docker harness for measuring and testing QUICraft against real server software.

```
bench client ──(front, netem)──▶ Velocity ──(back)──▶ Paper           TARGET=velocity (default)
bench client ──(front, netem)──▶ Fabric dedicated server + mod         TARGET=fabric
      └──────────────────────▶ mock session server
```

| Piece | What it is |
| --- | --- |
| `src/` | `quicraft-testkit`: headless bench client (MCProtocolLib), mock Mojang session server, results summarizer |
| `docker/` | Images and `compose.yaml` for Velocity, Paper, the Fabric server and the bench client |
| `netem/profiles.sh` | `clean`, `loss`, `delay`, `reorder` link profiles |
| `run-baseline.sh` | Builds everything, runs batches × profiles × joins, checks reproducibility |

MCProtocolLib is used here only, never in a shipped jar (CLAUDE.md, approved exception).

## Requirements

- Docker with Compose v2 and BuildKit. Rootful Docker: the bench container needs `NET_ADMIN`
  for `tc`.
- Host kernel modules `sch_netem` and `ifb`. On Fedora they're in `kernel-modules-extra`, on
  Ubuntu in `linux-modules-extra-$(uname -r)`.
- JDK 25 on the host, for building and for the summarizer.

The Paper image sets `eula=true`. That accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA)
for the testkit servers; the project owner agreed to it for this purpose.

## Running

```sh
testkit/run-baseline.sh                                       # full baseline, ~75 min
BATCHES=1 RUNS=3 PROFILES=clean PLAY_MS=3000 testkit/run-baseline.sh   # smoke test, ~2 min
KEEP_UP=true testkit/run-baseline.sh                          # leave the servers running afterwards
```

Environment knobs: `BATCHES`, `RUNS`, `WARMUP`, `PROFILES`, `ONLINE` (`true` uses online-mode login
and with it Minecraft's encryption), `PLAY_MS`, `TOLERANCE_PCT`, `TOLERANCE_MS`, `TRANSPORTS`
(`"tcp quic quic-0rtt"` runs them back to back per profile), `TARGET` (`velocity` or `fabric`),
`QUICRAFT_PLUGIN` (`false` leaves the plugin or mod out), `SCENARIO`.

Transports: `tcp`; `quic`, a full QUIC handshake on every join (session resumption off, as in the
runs before it existed); `quic-0rtt`, which rejoins as one player (`bench0rtt`) so the first flight
recorded on one join fits the next, and sends it as 0-RTT data from the second join on (each run
reports `0rtt=true|false`). Scenarios: `join` (default); `early-fallback`, where each run joins
over QUIC, then makes a 0-RTT attempt whose server-to-client UDP is dropped at the client's
socket, abandons it after the head start as a lost race does, and joins over TCP as the same
player; the run's metrics are that TCP join's (docs/protocol.md §8).

`TARGET=fabric` joins a Fabric 26.1.2 dedicated server with the `fabric-26x` mod directly
(`docker/fabric/`). Its world is pre-generated at image build with vanilla `/forceload` (same
seed as Paper, 48×48 chunks around spawn); Paper's world can't be reused because Paper stores the
world-gen settings differently.

Results go to `testkit/results/<UTC timestamp>/`: one JSON file per batch and profile, plus
`environment.txt` and `summary.md`. Method and metric definitions are in
[`docs/benchmarks.md`](../docs/benchmarks.md).

By hand, with the servers up (`KEEP_UP=true` or `docker compose -f testkit/docker/compose.yaml up -d`):

```sh
docker compose -f testkit/docker/compose.yaml run --rm -e NETEM_PROFILE=loss bench \
    bench --host velocity --runs 1 --warmup 0 --online --verbose
```

`--verbose` prints the first arrival of every packet type with its protocol state, which helps
when a join stalls.

### Real client through Velocity

```sh
testkit/run-real-client.sh
```

Starts Velocity → Paper (offline mode) with Velocity's port published on `127.0.0.1:25565`, TCP and
UDP, then runs the Fabric client gametests with `ExternalServerGameTest` pointed at it. The real
26.1.2 client joins over QUIC, stays in the world for 5 s, disconnects as the pause menu does, and
rejoins, which must use 0-RTT. Velocity runs with `-Dquicraft.logStats=true`; the script checks
that its early-release count goes from 0 to 1 over the two connections and saves Velocity's log
under `results/`. Needs a display or `xvfb-run`, and port 25565 free. Offline mode only: the
client's authlib finds its session server through a discovery URL the mock doesn't serve.

### Server-side netem on a fresh real-Fabric container

`netem-attach.sh` applies one profile to the network namespace of the `fabric` container in a
fresh, uniquely named Compose project (the arm runner's `quicraft-arm-<id>`), for runs where the
link must be shaped on the server's side rather than the bench client's:

```sh
testkit/netem-attach.sh apply  --project quicraft-arm-<id> --profile delayloss [--port 25565]
testkit/netem-attach.sh status --project quicraft-arm-<id> [--profile delayloss] [--port 25565]
```

It refuses any project not named `quicraft-arm-*`, anything but exactly one running `fabric`
container, host or container networking, and any probe namespace or IPv4 mismatch. A
non-privileged probe container is inspected before `NET_ADMIN` is granted, and only probes this
script created are removed. `status` defaults to the `delay` layout because that is where the
egress and ingress filters live; pass `--profile clean` after a clean attach. Its raw tc
qdisc/filter counters are state, not a throughput result. Contract tests:
`python3 -I testkit/tests/test_netem_attach.py -v`.

### External arms: real Fabric client against a fresh server

```sh
testkit/run-external-arms.sh --arm quic3 --run 0 --out-dir /tmp/opencode/arms
testkit/run-external-arms.sh --arm tcp --run 0 --warmup --out-dir /tmp/opencode/arms
```

One invocation runs exactly one arm (`tcp`, `quic2`, `quic3`) against one fresh Fabric dedicated
server container and one fresh client JVM. It refuses a dirty tracked tree, records `HEAD` as the
build commit, starts ONLY `fabric` and `mocksession` from `docker/compose.yaml` +
`docker/compose.real-fabric.yaml` in a fresh unique Compose project, waits for the loopback port,
narrows the gametest `fabric.mod.json` to `ExternalArmGameTest`, and restores it byte for byte on
every exit path. Teardown is scoped to that project (`down -v --remove-orphans`), including on
failure; no image, host qdisc, route or firewall is touched. The run succeeds only when
`<out-dir>/<arm>-<netem>-run<N>.json` exists, parses, and `runs[0].ok` is true.

Options/knobs: `--port` (must be 25565, the port the server advertises), `--play-seconds` (default 20),
`--netem` (`clean loss delay delayloss reorder jitter`, applied to the server container by
`testkit/netem-attach.sh`),
`QUICRAFT_ARM_READY_TIMEOUT` (180 s) and `QUICRAFT_ARM_MOD_JSON` (the entrypoint file; tests use a
copy). Needs Docker Compose v2, JDK 25, `python3`, `git`, `flock`, and a display or `xvfb-run`.
`ExternalArmGameTest` is deliberately not in the committed entrypoint list; the runner adds it for
the invocation only.

Receive diagnostics (baseline scheduling unchanged): start the client JVM with
`-Dquicraft.v3.trace=true`; off by default. The external-arm runner has no client-JVM-args
option. An operator can set `JAVA_TOOL_OPTIONS=-Dquicraft.v3.trace=true` in its environment
before invoking the runner (this also enables traces in Gradle JVMs, not in Docker containers).
Search the saved `.client.log` for `/3 receive trace`. The harness prints one cumulative
snapshot after the chunk quiet window and one at disconnect, each with client and core lines.
Outside this harness, only the disconnect snapshot is automatic.

Client counters distinguish immediate acknowledgements from acknowledgements held by the
existing `overBound()` check (256 packets / 1 MiB). They include peak queued packets/decoded
bytes, decoded bytes per unit (mean/max), delivery-start-to-ack latency and held-time total/max.
Delivery starts at the first packet's `packetDelivered()` notification; a unit finishes at its
existing acknowledgement marker. Histograms use bounded storage: p50/p95 are **100 us bucket
upper bounds**, with the observed maximum as the overflow bucket's bound, not exact percentiles.
Core reports ack receipt to `doApplied` (event-loop queue), and ack receipt to the next
delivery. The latter **includes input/dependency/idle waits**, so it is not a pure CPU or hop
measurement. Snapshots are cumulative from activation, not chunk-only deltas. Tracing retains
no packets, payloads or connection identifiers and changes no acknowledgement decisions.

Merge the per-run documents of one arm and profile (all inputs must agree on clientType, arm,
netemProfile, profile, server and build commit; run indexes must be unique; the output is written
atomically and never overwritten):

```sh
python3 -I testkit/merge-arm-samples.py --out /tmp/opencode/arms/clean-quic-3.json \
    /tmp/opencode/arms/quic3-clean-run0.json /tmp/opencode/arms/quic3-clean-run1.json
```

For `/3`, validate the merged document with the testkit CLI (the same `v3-samples` command that
`run-matrix.sh` uses for its fail-closed row):

```sh
./gradlew :testkit:installDist
testkit/build/install/quicraft-testkit/bin/quicraft-testkit v3-samples --in /tmp/opencode/arms/clean-quic-3.json
```

### Updater end-to-end on a real server

```sh
bash testkit/run-updater-e2e.sh velocity         # or fabric-1.21.11, or fabric-26x
```

The evidence for docs/updater.md section 10 blocker 10: the live atomic jar swap on Linux and macOS,
including JDK 21. No Docker. One run builds the module twice (0.1.0-alpha.1 and 0.1.0-alpha.2, with
the development trust file), starts the mock release host from the updater test sources on three
ephemeral localhost HTTPS ports, installs the old jar and starts the real server with the console on
a pipe. On the console it runs `quicraft update check` (must offer alpha.2) and `quicraft update`
(must install and ask for a restart), then asserts that the live jar's SHA-512 is the new one, that a
backup of the old jar is in the updater's `backup/` directory, and that a TCP status ping still
answers. It stops the server cleanly (`end` for Velocity, `stop` for Fabric), asserts exit code 0 and
no exception stack frame from `rs.sudoe.quicraft`, starts it again, and asserts that the log names
alpha.2, that the updater reached its health completion, and that a check reports no update.

Needs JDK 25 (and JDK 21 for the 1.21.11 Fabric server; without JDK 21 that server runs on 25 and the
run says so), `python3`, `curl` or `wget`, a network for the server jars, and the patched Netty QUIC
build under `natives/build/out/repo` (`natives/build-linux.sh`). `JAVA21` and `JAVA25` name the two
server JVMs as a `java` launcher or a `JAVA_HOME`. Results go to
`testkit/results/updater-e2e-<server>-<timestamp>/`: the summary with the console transcript, both
server logs, the mock host's log, the status pings and `FAILED` on a failed run.
`.github/workflows/updater-e2e.yml` runs all three servers on ubuntu-latest, macos-latest and
macos-15-intel and uploads that directory when a run fails.

## Notes

- netem's counters: every bench run ends by printing the packets, bytes and drops netem handled
  in each direction. With a netem profile active, both ends send one segment per packet
  (`gso_max_segs 1`): netem drops whole packets, and with segmentation offload a TCP packet could
  hold dozens of segments, so TCP saw a fraction of the configured loss (0.3% instead of 2%
  before 2026-10-11; docs/benchmarks.md). `clean` keeps the offloads, as real NICs do.

- Server jars are downloaded at image build time and checked against pinned sha256 sums (see the
  Dockerfiles). Paperclip downloads and patches the Mojang server jar during the build. Nothing
  from Mojang is committed.
- The world is pre-generated when the Paper image is built: Paper starts once with Chunky
  (GPL-3.0-only, removed afterwards) and generates a 256-block square around spawn (1089 chunks).
  Every `up` starts from that same world, and nothing a run changes survives `down`. Without it,
  a fresh environment such as CI measured world generation instead of chunk sending.
- Paper sends no chunk-batch packets (its own chunk system paces sending). The bench acks batches
  anyway in case a backend does.
- Join time has a server-side tail: Velocity's login to Paper usually takes ~150 ms and sometimes
  0.4–1.5 s. The gate therefore compares medians.
