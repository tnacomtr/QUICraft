# Benchmarks

Measured numbers only. Every result here comes from `testkit/` and names the commit, setup and
method it was produced with. Raw run files stay in `testkit/results/` (not committed); this file
keeps the summaries.

## Method

### Setup

```
bench client ──(front network, netem)──▶ Velocity ──(back network, clean)──▶ Paper
      └────────────────────────────────▶ mock session server ◀──────┘
```

- **Client:** headless bench client (`testkit/`, MCProtocolLib 26.1-1, protocol 775).
- **Proxy:** Velocity 4.2.0 build 30, online mode, modern forwarding.
- **Backend:** Paper 26.1.2 build 74, fixed seed `quicraft-testkit`, view distance 8, creative +
  peaceful so the bench player can't die, Paper's per-player chunk send/load rate caps turned off
  so chunk-load time reflects the link rather than Paper's limiter (`testkit/docker/paper/`).
- **World:** pre-generated into the Paper image at build time (Chunky, 256-block square around
  spawn, 1089 chunks), so every run reads the same chunks from disk and none are generated
  during a measurement.
- **Login:** online mode against a mock session server, so Minecraft's AES/CFB8 encryption is on,
  as it is on real servers.
- **Runtime:** every container runs Eclipse Temurin 25 JRE (image pinned by digest) on one host.
- **Link:** netem is applied to the client container only, to both directions (egress directly,
  ingress through an `ifb` device). Proxy to backend stays clean, like a LAN.

### netem profiles

Defined in `testkit/netem/profiles.sh`; every value is per direction.

| Profile | netem arguments | Effect |
| --- | --- | --- |
| clean | none | Docker bridge only |
| loss | `loss 2%` | 2% random loss each way |
| delay | `delay 75ms` | +150 ms round trip, no jitter (jitter would also reorder) |
| reorder | `delay 10ms reorder 25% 50%` | 25% of packets (50% correlated) skip the 10 ms delay and overtake earlier ones |

### Metrics

Each join ("run") records:

| Metric | Definition |
| --- | --- |
| Join time | Start of the TCP connect until the play-state Login packet arrives. Includes the handshake, online-mode auth, Velocity's backend login and configuration. Also split into phases (encryption request, login finished, first configuration packet, play login). |
| Chunk-load time | From the play Login packet until the last chunk that arrives before a 2 s quiet period. View distance 8 gives 329 chunks. |
| Play RTT | Every 5th tick (250 ms), right after a movement packet, the client sends a play-state ping request and times the pong. It travels the same path and stream as movement, through Velocity to Paper. The run's value is the median of about 40 samples. |
| Disconnects | The connection closed before the bench ended it. Failed runs (no login, no chunks) are counted too. |

The client moves 0.1 blocks back and forth at 20 Hz for 10 s per run.

### Reproducibility gate (Phase 0)

These parameters were fixed before the first full baseline run:

- **N = 20** measured joins per profile per batch, each after **2 warm-up joins** that are
  discarded. Warm-up absorbs JIT and first-generation costs.
- **3 batches.** Each batch runs all four profiles in turn, so slow drift on the host hits every
  profile instead of one.
- **Statistic:** the median per batch. Medians, because join time has a long tail that comes
  from the server, not the network: Velocity's backend login to Paper normally takes ~150 ms and
  sometimes 0.4–1.5 s.
- **Tolerance:** for every profile and every timed metric, each batch median must lie within
  **max(10% of the all-batch median, 5 ms)** of the all-batch median. The 5 ms floor exists
  because clean-link RTT is under 1 ms, where 10% is below timer noise.
- **Disconnects and failed runs:** must be **0** in every profile.

`testkit/run-baseline.sh` runs this and prints the gate table (`summarize`).

## Results

### TCP baseline

Pending: first full run in progress.
