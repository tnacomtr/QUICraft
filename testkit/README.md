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
(`"tcp quic"` runs both back to back per profile), `TARGET` (`velocity` or `fabric`),
`QUICRAFT_PLUGIN` (`false` leaves the plugin or mod out).

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

## Notes

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
