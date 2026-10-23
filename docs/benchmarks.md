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
| delayloss | `delay 75ms loss 2%` | both: loss recovery that has a real round trip to wait for |
| reorder | `delay 10ms reorder 25% 50%` | 25% of packets (50% correlated) skip the 10 ms delay and overtake earlier ones |

**Segmentation offload (changed 2026-10-11).** netem drops whole packets. Until 2026-10-11 the
containers kept segmentation offload on, so the server's TCP handed netem packets of many
segments and TCP saw a fraction of the configured loss: in one TCP and one QUIC join under
`loss`, netem dropped 10 of TCP's ~500 offloaded server-to-client packets (3,264 segments, about
0.3% of segments) and 263 of QUIC's 12,892 datagrams (2.0%). Since then, every profile except
`clean` caps segmentation at one segment per packet on both ends, and TCP loses 2.1% (69 of
3,287). **Every loss and delayloss row dated before 2026-10-11 (Phase 0 baseline, Phase 1, 2 and
3) was taken with offloads on**, so its TCP side had ~0.3% effective loss; the Phase 0 loss
reference values are from that harness too. Delay and reorder rows are affected less (no drops),
clean not at all. See "Phase 3 gaps" below.

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

- **N = 20** measured joins per profile per batch for clean and delay, and **N = 80** for loss
  and reorder, each after **2 warm-up joins** that are discarded. Warm-up absorbs client JIT
  costs. (Attempt 1 used N = 20 everywhere; the impaired profiles were too noisy at that size,
  see below. Raised to 80 by the user's decision on 2026-10-09, from the bootstrap estimate that
  ~4× the runs are needed for ±10%.)
- **3 batches.** Each batch runs all four profiles in turn, so slow drift on the host hits every
  profile instead of one. From attempt 2 on, the starting profile rotates from batch to batch.
- **Statistic:** the median per batch. Medians, because join time has a long tail that comes
  from the server, not the network: Velocity's backend login to Paper normally takes ~150 ms and
  sometimes 0.4–1.5 s.
- **Tolerance:** for every profile and every timed metric, each batch median must lie within
  **max(10% of the all-batch median, 5 ms)** of the all-batch median. The 5 ms floor exists
  because clean-link RTT is under 1 ms, where 10% is below timer noise.
- **Disconnects and failed runs:** must be **0** in every profile.

`testkit/run-baseline.sh` runs this and prints the gate table (`summarize`).

## Results

### TCP baseline, attempt 1 (2026-10-09): gate FAILED

Run `20261009T142043Z` at commit `206097f`, with the world volume generated by earlier runs (this
was before the pre-generated image). Host: i7-13650HX, Fedora kernel 7.2.8. 3 batches × 4
profiles × (2 warm-up + 20 measured) joins, online mode. 240 of 240 measured joins completed,
0 disconnects.

| Profile | Metric | Batch medians | All-batch median | Max deviation | Allowed | Result |
| --- | --- | --- | --- | --- | --- | --- |
| clean | join (ms) | 312.7, 310.3, 310.5 | 311.7 | 1.4 | 31.2 | pass |
| clean | chunk load (ms) | 428.2, 429.9, 430.1 | 429.4 | 1.2 | 42.9 | pass |
| clean | play RTT (ms) | 0.6, 0.6, 0.5 | 0.6 | 0.0 | 5.0 | pass |
| clean | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |
| delay | join (ms) | 1488.5, 1442.6, 1444.1 | 1453.9 | 34.7 | 145.4 | pass |
| delay | chunk load (ms) | 1211.6, 1157.6, 1178.1 | 1208.7 | 51.1 | 120.9 | pass |
| delay | play RTT (ms) | 150.6, 150.6, 150.6 | 150.6 | 0.0 | 15.1 | pass |
| delay | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |
| loss | join (ms) | 486.8, 361.8, 314.6 | 359.8 | 127.1 | 36.0 | **FAIL** |
| loss | chunk load (ms) | 429.5, 430.5, 430.4 | 430.2 | 0.7 | 43.0 | pass |
| loss | play RTT (ms) | 0.6, 0.6, 0.5 | 0.6 | 0.0 | 5.0 | pass |
| loss | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |
| reorder | join (ms) | 418.9, 476.1, 387.1 | 419.6 | 56.5 | 42.0 | **FAIL** |
| reorder | chunk load (ms) | 538.9, 422.0, 446.0 | 446.0 | 92.9 | 44.6 | **FAIL** |
| reorder | play RTT (ms) | 20.6, 20.6, 20.6 | 20.6 | 0.0 | 5.0 | pass |
| reorder | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |

Analysis:

- **The failing metrics are bimodal under impairment.** Loss join: either no login packet is lost
  (~260–310 ms) or one is and TCP waits ≥200 ms to retransmit (450–900 ms). A lost SYN costs
  1–3 s or more (outliers at 2.9, 3.5 and 6.2 s). About half the joins fall in each mode, so a
  20-run median flips between them. Reorder chunk load is either ~410 ms or 500–1800 ms, when
  reordering looks like loss to TCP and it retransmits and backs off.
- **Not server warm-up drift.** In the clean profile, the backend-login phase (login finished
  → first configuration packet, mostly Velocity's login to Paper) has medians 246, 247 and
  248 ms across the batches. Trimmed means fall from batch 1 to batch 3, but that comes from a few
  multi-second outliers, which appear in every batch.
- **Not host interference.** The only CPU-heavy work during the run (building the pre-generated
  Paper image, 14:44 UTC) overlapped batch 2's clean profile, which passed (310.3 ms).
- **Precision at N = 60 pooled** (bootstrap 95% CI of the median): loss join 359.8 ms
  [312, 461] (±21%), reorder chunk load 446.0 ms [415, 556] (±16%), reorder join 419.6 ms
  [389, 440] (±6%), clean join 311.7 ms [308.5, 313.5] (±0.8%).

### TCP baseline, attempt 2 (2026-10-09): accepted, 14 of 16 checks pass

Run `20261009T155427Z` at commit `a6398ca`, on the pre-generated world. Same host. 3 batches,
N = 20 (clean, delay) and N = 80 (loss, reorder) per batch, 2 warm-ups each, rotating start
profile, online mode. 600 of 600 measured joins completed, 0 disconnects.

| Profile | Metric | Batch medians | All-batch median | Max deviation | Allowed | Result |
| --- | --- | --- | --- | --- | --- | --- |
| clean | join (ms) | 311.6, 265.0, 310.7 | 311.2 | 46.2 | 31.1 | **FAIL** |
| clean | chunk load (ms) | 428.9, 430.9, 430.9 | 430.4 | 1.5 | 43.0 | pass |
| clean | play RTT (ms) | 0.6, 0.5, 0.5 | 0.5 | 0.0 | 5.0 | pass |
| clean | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |
| delay | join (ms) | 1443.0, 1463.1, 1429.4 | 1437.9 | 25.3 | 143.8 | pass |
| delay | chunk load (ms) | 1213.7, 1213.1, 1137.4 | 1212.0 | 74.5 | 121.2 | pass |
| delay | play RTT (ms) | 150.6, 150.5, 150.6 | 150.6 | 0.0 | 15.1 | pass |
| delay | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 60 | | 0 | pass |
| loss | join (ms) | 311.6, 305.2, 307.7 | 308.1 | 3.4 | 30.8 | pass |
| loss | chunk load (ms) | 429.8, 430.4, 431.0 | 430.4 | 0.6 | 43.0 | pass |
| loss | play RTT (ms) | 0.5, 0.5, 0.5 | 0.5 | 0.0 | 5.0 | pass |
| loss | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 240 | | 0 | pass |
| reorder | join (ms) | 421.7, 418.9, 402.2 | 417.1 | 14.9 | 41.7 | pass |
| reorder | chunk load (ms) | 479.2, 610.0, 519.3 | 524.8 | 85.2 | 52.5 | **FAIL** |
| reorder | play RTT (ms) | 20.6, 20.5, 20.6 | 20.6 | 0.0 | 5.0 | pass |
| reorder | disconnects / failed runs | 0, 0, 0 | 0 / 0 of 240 | | 0 | pass |

The two failures, and why more runs would not fix them:

- **Clean join: quantized by Paper's 50 ms tick.** Paper handles the backend login on its tick, so
  the backend-login phase falls on discrete steps (~195, ~245, ~295 ms…). In batch 2, 11 of 20
  runs hit the 195 ms step instead of the usual 6–7, and the median dropped to the lower step.
  This is server-side and transport-independent: the Velocity→Paper leg stays TCP on the LAN
  under QUIC too. Pooled: median 311.2 ms, 95% CI [265.3, 312.6], p90 411.2 ms.
- **Reorder chunk load: heavy tail from TCP treating reordering as loss.** Runs either finish
  at ~410 ms or take 500–1800 ms after spurious retransmits and backoff. Pooled over 240 runs:
  median 524.8 ms, 95% CI [464.7, 587.6] (±12%), p90 1156.9 ms.

**Decision (user, 2026-10-09):** accept this run as the Phase 0 TCP baseline, with the two
metrics above documented as noisy by nature rather than tuned further.

**How later QUIC-vs-TCP comparisons work.** The table below is a reference for what TCP does on
this setup, not the comparator. Every comparison run measures TCP and QUIC in the **same run**,
interleaved, with identical parameters. A difference counts only if the bootstrap 95% CI of the
difference of medians, computed within that run, excludes zero. That keeps method changes (for
example a shorter play phase) from leaking into the comparison. Each run's parameters are still
recorded here before it starts.

Possible faster parameters for such runs: play phase 3 s instead of 10 s (play RTT was identical
across every batch at 10 s). The quiet window stays at 2 s: a shorter one could end chunk-load
timing early in exactly the reorder tail, where TCP is backing off.

#### Baseline reference values (TCP, attempt 2, pooled medians)

| Profile | Join | Chunk load (329 chunks) | Play RTT |
| --- | --- | --- | --- |
| clean | 311.2 ms | 430.4 ms | 0.5 ms |
| loss (2% each way) | 308.1 ms (p90 657.3) | 430.4 ms | 0.5 ms |
| delay (+150 ms RTT) | 1437.9 ms | 1212.0 ms | 150.6 ms |
| reorder (25%, 10 ms) | 417.1 ms | 524.8 ms (p90 1156.9) | 20.6 ms |

## Transport benchmark: congestion control and head start (Phase 1)

Transport level only: no Minecraft code, no game or proxy, so none of these numbers are game
results. It picks the v1 congestion-control default and supplies the data for the head start H
(docs/protocol.md §5, §9).

### Method (recorded before the first full run)

- `testkit/run-transport-bench.sh`: `transport-server` (TCP plus one QUIC listener each for
  RENO, CUBIC and BBR, all on core's v1 transport parameters) and `transport-client` in the
  netem-shaped bench container. Same four profiles as the baseline, applied to both directions.
- Per run, over one transport: connect (QUIC: handshake plus the v1 stream; TCP: `connect`), then
  the client sends HELLO and the server answers with a **200 KiB login burst** (32 KiB messages)
  and a **2 MiB chunk burst** (4 KiB messages). Then **3 s of play**: the server sends 200-byte
  entity updates at 20 Hz while the client sends 64-byte pings at 20 Hz, which the server echoes.
  The burst sizes are rough stand-ins for configuration data and a view-distance-8 chunk load,
  not measured Minecraft traffic.
- Metrics: handshake time; burst time (HELLO until the end-of-burst marker); median ping RTT.
- **N = 20** runs per transport per profile, after 1 warm-up round. The transports rotate within
  every round, so each sees the same conditions.
- **Comparison:** each QUIC variant against TCP from the same run. A difference counts only if
  the bootstrap 95% CI (5000 resamples) of the difference of medians excludes zero.

### Results, run `transport-20261009T191726Z` (2026-10-09)

Commit `2a4bfe0`, same host as the baseline. 0 failed runs.

| Profile | Metric | TCP | QUIC Reno | QUIC CUBIC | QUIC BBR |
| --- | --- | --- | --- | --- | --- |
| clean | handshake (ms) | 0.3 | 2.8 (**+2.6 [+2.4, +3.0]**) | 2.8 (**+2.6 [+2.4, +3.0]**) | 2.8 (**+2.6 [+2.5, +2.9]**) |
| clean | login+chunk burst (ms) | 3.5 | 7.9 (**+4.4 [+2.8, +5.0]**) | 8.0 (**+4.5 [+2.8, +5.2]**) | 8.0 (**+4.5 [+2.8, +6.4]**) |
| clean | play RTT (ms) | 0.4 | 0.8 (**+0.4 [+0.3, +0.4]**) | 0.8 (**+0.4 [+0.3, +0.4]**) | 0.8 (**+0.4 [+0.3, +0.4]**) |
| delay | handshake (ms) | 150.5 | 303.4 (**+152.9 [+152.4, +153.1]**) | 303.3 (**+152.8 [+152.7, +153.1]**) | 303.3 (**+152.8 [+152.6, +153.0]**) |
| delay | login+chunk burst (ms) | 2179.1 | 4216.1 (**+2037.0 [+1662.3, +2413.7]**) | 3465.7 (**+1286.6 [+1059.9, +1662.8]**) | 1669.7 (**-509.4 [-746.9, -122.1]**) |
| delay | play RTT (ms) | 150.5 | 150.7 (**+0.2 [+0.2, +0.3]**) | 150.8 (**+0.3 [+0.2, +0.3]**) | 150.7 (**+0.2 [+0.1, +0.3]**) |
| loss | handshake (ms) | 0.3 | 3.0 (**+2.7 [+2.5, +2.9]**) | 2.8 (**+2.6 [+2.5, +3.1]**) | 2.8 (**+2.6 [+2.5, +3.2]**) |
| loss | login+chunk burst (ms) | 5.8 | 13.5 (+7.6 [-93.9, +11.7]) | 10.5 (+4.7 [-96.6, +7.6]) | 9.0 (+3.2 [-98.0, +5.8]) |
| loss | play RTT (ms) | 0.4 | 0.7 (**+0.3 [+0.3, +0.4]**) | 0.8 (**+0.3 [+0.3, +0.4]**) | 0.8 (**+0.4 [+0.3, +0.4]**) |
| reorder | handshake (ms) | 20.4 | 42.8 (**+22.4 [+15.5, +23.0]**) | 43.2 (**+22.8 [+17.6, +23.3]**) | 33.3 (**+13.0 [+12.5, +22.6]**) |
| reorder | login+chunk burst (ms) | 683.0 | 3602.6 (**+2919.7 [+2693.2, +3305.1]**) | 2120.3 (**+1437.3 [+1154.4, +1816.4]**) | 2644.7 (**+1961.7 [+1735.3, +2276.5]**) |
| reorder | play RTT (ms) | 20.7 | 20.8 (**+0.1 [+0.1, +0.2]**) | 20.8 (**+0.2 [+0.1, +0.2]**) | 20.8 (**+0.1 [+0.1, +0.2]**) |

Cells: median (difference to TCP [bootstrap 95% CI]); **bold** where the CI excludes zero.

| Profile | QUIC handshake p50 | p90 | p99 | max | TCP connect p50 |
| --- | --- | --- | --- | --- | --- |
| clean | 2.8 | 3.5 | 4.0 | 4.0 | 0.3 |
| delay | 303.3 | 303.8 | 304.1 | 304.4 | 150.5 |
| loss | 2.9 | 4.0 | 1005.3 | 1006.1 | 0.3 |
| reorder | 42.7 | 43.7 | 44.0 | 44.0 | 20.4 |

Failed runs: 0

Reading:

- **Congestion control: BBR** is the only variant that beats TCP anywhere (2.2 MiB burst at
  +150 ms RTT: −509 ms, CI [−747, −122]). Reno and CUBIC are 1.3–2 s slower than TCP there.
  BBR becomes the provisional v1 default.
- **Reorder** (25% of packets overtaking by 10 ms) is a weakness: every QUIC variant is 1.4–2.9 s
  slower than TCP on the burst. Cause not confirmed yet. The suspect is spurious loss detection:
  quiche declares reordered packets lost, while Linux TCP detects and undoes such false alarms.
  Next step: record quiche's loss and retransmission counters per run.
- **Handshake: ~2 RTT** for every QUIC variant against 1 RTT for TCP (Netty completes the
  connect one round trip after TLS finishes; docs/protocol.md §5). Under loss, the p99 (~1 s) is
  a lost first Initial waiting for its retransmission timer.
- **The loss profile has no added delay**, so loss recovery is nearly free and nothing differs
  significantly. A loss+delay profile is needed to judge loss.
- **Play RTT:** QUIC adds 0.1–0.4 ms (CPU cost per packet), immaterial next to the link RTT.

### Reorder diagnosis (2026-10-09)

A one-round rerun of the reorder profile with sender-side counters
(`QuicByteStream.connectionStats()` for QUIC, `/proc/net/{snmp,netstat}` deltas for TCP):

| Transport | Sender counters for one 2.2 MiB burst run |
| --- | --- |
| TCP | RetransSegs 1, TCPSACKReorder 5, TCPTSReorder 1 (reordering detected and absorbed) |
| QUIC Reno | lost 141 of 2333 packets sent, all retransmitted |
| QUIC CUBIC | lost 480 of 2660 |
| QUIC BBR | lost 488 of 2705 |

The profile drops nothing, so every QUIC "loss" is spurious. quiche treats reordered packets as
lost, retransmits them and backs off, while Linux TCP detects the reordering and adapts its
threshold. The quiche inside Netty 4.2.19 contains an adaptive mode
(`enable_relaxed_loss_threshold`), but Netty exposes no setter for it and the native exports no
quiche config symbols. Turning it on needs a Netty change or our own native build.

### Relaxed loss threshold and the connect fix (2026-10-10)

QUICraft's Netty build (`4.2.19.Final-quicraft1`, docs/protocol.md §11) adds quiche's relaxed
loss threshold and completes a client connect one RTT sooner. Method as above, with a fifth
transport interleaved, `quic-bbr-relaxed`. The other three QUIC transports set the option off
explicitly. Short runs (N=5, 1 warm-up), so CIs are wide. Commit `238cd87`, same host.

Run `transport-20261010T010019Z` (reorder, delay; N=5), 0 failed runs:

| Profile | Metric | TCP | QUIC Reno | QUIC CUBIC | QUIC BBR | QUIC BBR relaxed |
| --- | --- | --- | --- | --- | --- | --- |
| delay | handshake (ms) | 150.4 | 153.2 (**+2.8 [+2.2, +3.1]**) | 153.2 (**+2.7 [+2.3, +3.4]**) | 153.6 (**+3.2 [+2.5, +3.5]**) | 153.5 (**+3.1 [+2.3, +4.7]**) |
| delay | login+chunk burst (ms) | 2105.3 | 4067.5 (+1962.2 [-747.7, +2714.4]) | 1358.4 (-746.9 [-1346.3, +1510.8]) | 1556.3 (-549.0 [-1148.4, +54.2]) | 1526.5 (-578.9 [-1178.2, +155.7]) |
| reorder | handshake (ms) | 20.4 | 23.0 (+2.6 [-6.3, +12.5]) | 23.0 (**+2.6 [+2.3, +12.5]**) | 23.0 (**+2.6 [+2.3, +12.5]**) | 23.3 (**+2.8 [+2.6, +12.8]**) |
| reorder | login+chunk burst (ms) | 1211.9 | 3658.8 (**+2446.9 [+2032.1, +3495.4]**) | 2416.0 (**+1204.1 [+615.2, +2252.6]**) | 2754.2 (**+1542.3 [+1226.7, +2590.8]**) | 799.1 (-412.8 [-650.0, +635.7]) |

| Profile | Metric | QUIC BBR | QUIC BBR relaxed | Difference [95% CI] |
| --- | --- | --- | --- | --- |
| reorder | login+chunk burst (ms) | 2754.2 | 799.1 | **-1955.1 [-2155.2, -1561.4]** |
| delay | login+chunk burst (ms) | 1556.3 | 1526.5 | -29.8 [-282.0, +151.0] |

Sender counters under reorder (median per run): BBR lost 579 / retrans 575 of 2956 sent; BBR
relaxed lost 93 / retrans 91 of 2331; Reno 164/160; CUBIC 767/766. TCP: RetransSegs 15,
TCPSACKReorder 29.

Clean link, run `transport-20261010T010824Z` (N=5): burst TCP 7.1 ms, BBR 12.2, BBR relaxed 9.6
(relaxed vs BBR −2.6 [−9.5, +6.4]); handshake p50 2.5–3.1 ms for all QUIC variants. No regression.
(An N=3 run before it, `transport-20261010T010652Z`, showed BBR relaxed at 19.6 ms against 8.9,
CI [−6.4, +14.8]. Single runs on loopback have sporadic real losses in either mode.)

Reading:

- **Reordering: fixed for BBR.** The relaxed threshold cuts spurious losses ~6x and the 2.2 MiB
  burst by ~2 s, bringing QUIC BBR level with TCP (−413 ms, CI includes zero) instead of 1.5 s
  slower. Reno and CUBIC don't get it: quiche 0.30 implements it in its BBR recovery only.
- **Handshake: ~1 RTT now.** At +150 ms RTT the QUIC handshake p50 is 153 ms, down from 303 ms
  in run `transport-20261009T191726Z`. That is a cross-run comparison, anchored on TCP connect
  (150.4 and 150.5 ms). Remaining cost over TCP: ~3 ms.
- Core's `NativeFeaturesTest` shows the same effect on loopback through a relay that reorders
  both directions (25% overtake 10 ms): BBR declared 1778–1969 packets lost per 2 MiB without the
  option and 466–908 with it.

## Phase 2 gate: joins through the Velocity plugin (2026-10-10)

Setup as in the baseline (bench → Velocity 4.2.0 → Paper 26.1.2, online mode, so Minecraft's
AES/CFB8 runs inside QUIC), with the QUICraft plugin installed in Velocity. Clean profile,
1 warmup + 3 measured joins per transport: a functional check, not a comparison. N=3 says
nothing about small differences.

| Transport | Run | Joins OK | Join median | Chunk load median | Play RTT median | Disconnects |
| --- | --- | --- | --- | --- | --- | --- |
| QUIC (`--transport quic`) | `20261010T011255Z-quic` | 4 / 4 | 306.4 ms | 419.5 ms | 1.2 ms | 0 |
| TCP, plugin installed | `20261010T011425Z-tcp` | 4 / 4 | 311.9 ms | 430.9 ms | 0.8 ms | 0 |
| TCP baseline (attempt 2, no plugin) | | | 311.2 ms | 430.4 ms | 0.5 ms | |

- QUIC: the client pings over TCP for the advertisement (outside the timed window, like a
  server-list entry), connects QUIC to the advertised port, then runs MCProtocolLib's session
  over a bridge channel. Every join loaded all 329 chunks, with no lost pings.
- TCP with the plugin installed matches TCP without it, measured back to back in one session
  (2026-10-10, clean, 5 runs each): join 312.8 vs 310.2 ms, chunk load 426 vs 428 ms, play RTT
  0.7 vs 0.8 ms. The plugin leaves TCP joins alone.
- Join time is noisy at N=3 in both transports (warmups at 1.5–1.7 s, one QUIC run at 1559 ms in
  the configuration phase, one TCP run at 510 ms). That's the clean-join quantization documented
  in the baseline, not a transport effect.
- Ping at the status length cap against the real Velocity 4.2.0-30 jar, with a 64×64 noise
  favicon (16.5 KB PNG) and a MOTD sized around the cap (advertisement member: 136 characters):
  MOTD 10449 → 32767 characters with the advertisement; 10450 → 32632, advertisement left out,
  Velocity's response unchanged; 10585 → 32767 (Velocity's own response at the cap), left out.

### Latency work: QUIC vs TCP (2026-10-10)

The user's bar for Phase 2: QUIC at TCP's latency. Method: `run-baseline.sh` with
`TRANSPORTS="tcp quic"`, so each profile runs TCP and QUIC back to back in one session; 5
measured runs each; QUIC − TCP as the difference of medians with a 95% bootstrap CI.

**Where the gap came from (in-process, `quicraft-testkit latency`):** 16-byte ping-pong on
loopback, 20 ms idle between pings (like game ticks), p50, with each round trip traced point by
point:

| Path | p50 | Notes |
| --- | --- | --- |
| TCP (Netty NIO) | 352–391 µs | reference |
| QUIC, bridge on core's own QUIC thread | 739 µs | four thread hand-offs per round trip, ~80–90 µs each on idle threads |
| QUIC hosted on the game's loop (first version) | 487 µs | hand-offs gone; wake-up segments equal TCP's |
| hosted, every read deferred to a task | 524 µs | quiche then sent an ACK-only packet before each reply: 2 packets per side |
| hosted, inline up to 32 KiB per pass (current) | 485 µs | 1 packet per side; the rest is quiche's per-packet work on cold caches |

With busy threads (no idle gap) the same paths take 45 µs (TCP) and 48 µs (hosted QUIC):
QUIC's own cost is ~3 µs when caches are warm, ~110 µs per round trip (four packets) when the
CPU went idle in between.

**End to end through Velocity** (current code: runs `20261010T034955Z-tcp-quic` for clean and
loss; `20261010T030746Z-tcp-quic` for delay and reorder, taken before the inline-delivery fix,
which only affects idle-path latency):

| Profile | Join | Chunk load | Play RTT |
| --- | --- | --- | --- |
| clean | −2 ms [−255, +443] | +3 ms [+3, +16] | +0.10 ms [+0.08, +0.19] |
| loss 2% | −39 ms (noisy) | +21 ms [−237, +45] | +0.14 ms [+0.08, +0.22] |
| delay +150 ms | −47 ms [−243, +235] | **−291 ms** [−410, −116] | +0.05 ms [−0.09, +0.29] |
| reorder | +105 ms (noisy) | **+830 ms** [+613, +1168] | +0.05 ms [−0.01, +0.25] |
| jitter (10 ± 5 ms per direction) | +2 ms | +1268 ms [−247, +2607] | −0.24 ms [−2.62, +0.72] |

Fixed on the way, each found by these runs:

- **UDP receive-buffer overflow** (delay profile, 112–740 datagrams dropped per join; TCP's
  autotuned buffer absorbs the same burst): 4 MiB socket buffers where the kernel allows
  (`quicraft.udpBufferBytes`), 128 datagrams read per loop pass, and sliced delivery beyond
  32 KiB per pass so the loop keeps draining the socket while the game decodes. Drops: 0.
  Delay-profile chunk load went from +492 ms to −291 ms against TCP.
- **Close dropped data** (a kick message right before a close could be lost) and the bridge
  never fired channelInactive on a peer close: now TCP-like (protocol.md §8).
- **Event loop spin** after closing a connection right after connecting (the race loser):
  quiche bug, fixed upstream in fc9fe129 and backported (protocol.md §11).

Open:

- **Play RTT +0.10–0.14 ms on idle CPUs** (clean, loss): quiche's per-packet work, four packets
  per round trip. No profiler on the test machine; closing it means cutting per-packet work in
  Netty QUIC/quiche.
- **Chunk load under the reorder and jitter profiles.** Both netem models are harsher than real
  paths. `reorder` sends 25% of packets with no delay, so the path has a ~0 ms and a 20 ms RTT;
  BBR sizes its window from the minimum RTT and caps itself far below what the 20 ms packets
  need. Velocity-side stats: 32–84 spurious losses per join with BBR (relaxed threshold),
  2400–3600 with CUBIC (chunk load 2.0–2.2 s). `jitter` delays every packet independently, so
  each burst is shuffled: TCP also degrades (median 1756 ms vs 410 ms clean) and QUIC's sender
  declared 1200–1860 of ~7000 packets lost. Play RTT is at parity under both.


## Phase 3: Fabric client and dedicated server (2026-10-10)

### Fabric dedicated server, bench client (testkit)

`TARGET=fabric`: bench client → Fabric 26.1.2 dedicated server with the `fabric-26x` mod, no
proxy (`testkit/docker/fabric/`). Online mode against the mock session server, so Minecraft's
AES/CFB8 runs (inside QUIC, for QUIC joins). Same seed, view distance 8 (329 chunks), creative,
peaceful; world pre-generated at image build. TCP and QUIC back to back per profile, one batch,
10 measured joins each (20 under loss) after 2 warm-ups. QUIC − TCP is the difference of medians
with a 95% bootstrap CI. Run `20261010T094648Z-fabric-tcp-quic` (commit 09e96b8 plus the
testkit's Fabric image, committed with this section).

| Profile | Metric | TCP | QUIC | QUIC − TCP [95% CI] |
| --- | --- | --- | --- | --- |
| clean | join | 344 ms | 202 ms | −142 ms [−162, −102] |
| clean | chunk load | 379 ms | 390 ms | +10 ms [−37, +54] |
| clean | play RTT | 0.37 ms | 0.51 ms | +0.14 ms [+0.08, +0.21] |
| loss 2% | join | 346 ms | 217 ms | −129 ms [−247, −83] |
| loss 2% | chunk load | 400 ms | 445 ms | +45 ms [+4, +94] (offloads on: TCP saw ~0.3% loss; closed below) |
| loss 2% | play RTT | 0.35 ms | 0.47 ms | +0.11 ms [+0.07, +0.15] |
| delay +150 ms | join | 1330 ms | 1350 ms | +20 ms [−11, +37] |
| delay +150 ms | chunk load | 1011 ms | 1079 ms | +68 ms [−84, +75] |
| delay +150 ms | play RTT | 150.40 ms | 150.51 ms | +0.11 ms [−0.02, +0.19] |

Every join succeeded (80 of 80), no disconnects, no UDP receive-buffer drops. Under loss QUIC's
sender counted a median of 44 lost packets per join, TCP retransmitted a median of 5 segments.

- **Join:** QUIC is faster on the clean and loss profiles because the configuration phase is:
  about 150 ms over QUIC against 304 ms over TCP (phase timestamps in the run files). Through
  Velocity (Phase 2) the joins were level, since the proxy-to-backend hop is TCP for both. The
  cause inside the configuration phase wasn't investigated further.
- **TCP is unaffected by the mod**, back to back on the same image, clean, 10 joins each (runs
  `20261010T100910Z-fabric-tcp` with, `20261010T101214Z-fabric-tcp` without): with the mod join 323 ms, chunk load 363 ms, RTT 0.37 ms, configuration
  phase 304 ms; without it 324 ms, 365 ms, 0.37 ms, 304 ms.
- **Play RTT:** the same +0.1 ms per round trip as through Velocity (quiche's per-packet work,
  Phase 2 notes).
- **Chunk load under loss:** +45 ms (CI just above 0). Not looked into yet.

### Real client (gametests, loopback)

`JoinTimingGameTest` (`-Pquicraft.joinTimings=10`): the real Fabric client joins an in-process
dedicated server 10 times over TCP (`tcp-only`) and 10 times over QUIC (`auto`), alternating, after
one warm-up round. Timestamps are taken on the client's Netty thread as the play login packet and
each chunk arrive (117 chunks). The gametest harness runs the server's ticks in lockstep with
the client's, so server-side steps land on 50 ms tick boundaries and the times come in 50 ms
steps.

| Version | Connect → `Connection` active | Connect → play login | Connect → last chunk |
| --- | --- | --- | --- |
| 1.21.11 | TCP 14.8 ms, QUIC 20.9 ms | TCP 201.1 ms, QUIC 196.8 ms | TCP 743.8 ms, QUIC 744.0 ms |
| 26.1.2 | TCP 6.6 ms, QUIC 11.8 ms | TCP 154.3 ms, QUIC 204.3 ms | TCP 700.4 ms, QUIC 750.6 ms |

The advertisement is refreshed before each QUIC join, so no status query is in these times
(direct connects without one add a status query: one TCP connect and one round trip).

QUIC's connect costs 5–6 ms more than TCP's on loopback: the QUIC handshake (TLS 1.3 with
certificate check, p50 2.7 ms in the transport benchmark) plus the asynchronous connect path
(UDP socket bind, race bookkeeping, handing the winner to the screen's thread). Everything after
the connect is level: on 1.21.11 play login and last chunk match within a millisecond. On 26.1.2
the 5 ms are enough to miss the next lockstep server tick in 8 of 10 runs, so login and last chunk
come 50 ms later; with a real server, whose ticks don't wait for the client, that is a 5 ms delay
landing on a random tick phase, not a 50 ms one.

### Session resumption (2026-10-10)

The client now resumes TLS sessions (protocol.md §8). Two measurements, same host:

- **Core, loopback, no game** (temporary test, not committed): 250 interleaved pairs after 50
  warm-up pairs, connect future only. Full handshake p50 3.30 ms (p90 3.89), resumed p50 3.18 ms
  (p90 3.75). Resumption saves ~0.1 ms: the QUIC handshake's time on loopback is not crypto.
- **Real client** (`JoinTimingGameTest` now interleaves TCP, QUIC with a full handshake, and QUIC
  resuming the full join's session, and checks that the resumed joins ran no certificate check):
  in both versions every transport, TCP included, jumps to 33–38 ms connect → active halfway
  through the run, so only the first five rounds say anything. 26.1.2: TCP 9.1–13.4 ms, QUIC
  full 9.8–18.3 ms, QUIC resumed 14.1–18.2 ms. 1.21.11: TCP 19.0–19.7 ms, QUIC full 22.0–24.1 ms,
  QUIC resumed 22.5–23.9 ms. No difference between full and resumed beyond the noise. The machine
  was busier than in the run above (desktop load), so these don't compare with that table.
- **Round trips:** `HandshakeFlightProbeTest` at 200 ms RTT: full 1.08–1.10 RTT, resumed 1.02 RTT.
  The session ticket arrives about 2 RTT after the first Initial, so only a connection that lived
  that long leaves a ticket behind.

With the server holding early data until the handshake completed, 0-RTT data would not have saved
a round trip either. The transport benchmark sets `sessionResumption(false)`, so its handshake
numbers stay full handshakes, comparable with earlier runs.

### 0-RTT joins (2026-10-10)

The server now acts on a rejoin's 0-RTT handshake and Login Start when they carry an unused
single-use token (protocol.md §8; needs netty/0003, §11).

- **Core, through a relay at 200 ms RTT** (`EarlyDataTest`): first reply 1.01 RTT after the
  client's first datagram with 0-RTT, 2.03 RTT without.
- **Testkit, +150 ms RTT (delay profile), online mode**, `TRANSPORTS="tcp quic quic-0rtt"`, N=10
  each after 1 warm-up, interleaved by transport, play 2 s. `quic` is a full handshake every
  join; `quic-0rtt` sent 0-RTT data on 10 of 10 measured joins on both targets. Medians, ms;
  differences with 95% bootstrap CIs:

  | Target | Metric | TCP | QUIC full | QUIC 0-RTT | 0-RTT − TCP | 0-RTT − QUIC full |
  | --- | --- | --- | --- | --- | --- | --- |
  | Velocity → Paper (`20261010T150734Z-velocity-tcp-quic-quic-0rtt`) | first login response (encryption request) | 303.5 | 305.2 | 154.4 | −149.1 [−149.4, −148.4] | −150.8 [−151.4, −150.2] |
  | | play login (join) | 1435.6 | 1449.5 | 1275.5 | −160.1 [−201.3, −136.3] | −174.0 [−202.1, −145.0] |
  | | chunk load | 1093.3 | 1014.7 | 966.0 | −127.3 [−246.0, −0.6] | −48.7 [−100.8, +103.5] |
  | | play RTT | 150.7 | 150.8 | 150.7 | −0.0 [−0.2, +0.1] | −0.1 [−0.2, +0.1] |
  | Fabric server (`20261010T151553Z-fabric-tcp-quic-quic-0rtt`) | first login response | 303.3 | 305.7 | 154.6 | −148.8 [−149.2, −148.1] | −151.1 [−151.5, −150.4] |
  | | play login (join) | 1429.7 | 1350.3 | 1198.3 | −231.3 [−272.6, −187.1] | −151.9 [−181.3, −126.3] |
  | | chunk load | 1010.1 | 1092.1 | 1074.3 | +64.3 [+40.5, +75.6] | −17.8 [−26.2, +9.0] |
  | | play RTT | 150.5 | 150.7 | 150.6 | +0.0 [−0.0, +0.2] | −0.1 [−0.2, +0.1] |

  0-RTT saves one round trip on the join, as predicted. The Fabric play-login difference against
  TCP also includes the faster configuration phase over QUIC seen in Phase 3, and its chunk-load
  difference against TCP (+64 ms) is the one Phase 3 measured (+68 ms), not a 0-RTT effect.
- **Clean link, Velocity** (same run): no difference between 0-RTT and a full QUIC handshake
  (first login response 4.2 vs 4.5 ms, join 293.7 vs 294.4 ms; CIs span 0). QUIC's first login
  response stays ~2 ms behind TCP's (2.2 ms): the handshake's local cost, which a round trip
  saved doesn't touch at this RTT.
- **Under loss** (`TRANSPORTS=quic-0rtt`, Velocity, online mode, run
  `20261010T153059Z-velocity-quic-0rtt`, with the 10 s confirmation deadline): `loss` N=80 and
  `delayloss` (+150 ms RTT and 2% loss each way) N=20. 100 of 100 joins succeeded, 0 disconnects,
  0-RTT on all of them. delayloss: first login response 154.1 ms, join 1465.7 ms (median; max
  2408.2). loss: 3.1 ms and 275.6 ms (max 1314.3).
- **0-RTT the server rejects** (session ticket from before a server restart; `EarlyDataTest`,
  relay at 200 ms RTT): the data is resent only once quiche's loss detection declares it lost,
  so the first reply comes 4.18 RTT after the first datagram, against 2.03 RTT for a join
  without 0-RTT. That is about +320 ms at 150 ms RTT, once, on the first rejoin after the server
  restarted. Since 2026-10-11 the client abandons such a connection with `0x4` and reconnects
  without 0-RTT (docs/protocol.md §8, "Rejected 0-RTT"): 3.30 RTT in the same test (four runs,
  3.29-3.30). A quiche patch that treats in-flight 0-RTT packets as lost on the reject would be
  faster still; not done.
- **0-RTT data that arrives before the Initial** (found 2026-10-11 through a flaky
  `EarlyDataTest`): the server has no connection for it yet, the stream only reaches it after
  the handshake (seen in the server's preamble timing), and the join takes
  the same ~4 RTT path as a rejected 0-RTT. The test relay sent datagrams due at the same
  instant from two threads, so the 0-RTT packet sometimes overtook the Initial (2 failures in
  about 25 runs of the class; 0 in 14 with one thread). Real networks reorder that pair only
  rarely, and a lost 0-RTT packet ends the same way. The quiche fix above would shorten this
  case too.
- **Fallback after 0-RTT** (`SCENARIO=early-fallback`, clean, N=4 each): a 0-RTT attempt whose
  server-to-client UDP is dropped is abandoned after the head start, then the same player joins
  over TCP. Velocity offline and online, Fabric offline and online: 16 of 16 TCP joins succeeded.
  On Velocity the plugin closed the abandoned login each time (it had registered the player, in
  offline mode); on Fabric the abandoned login timed out by itself ~3 s later (the confirmation
  deadline was 3 s in this run; now 10 s) without getting in the way.

### No desync, fault injection, fallback

The client gametests (docs/platforms/fabric.md; last run on all six 2026-10-11) pass on 26.1, 26.1.1, 26.1.2, 26.2, 26.3 and
1.21.11: the scripted play session (block bursts, entities, client and server movement, inventory,
weather, a far teleport into freshly generated chunks) compares client and server state at every
step over QUIC and, with every hook throwing, over TCP; dead UDP port and fingerprint mismatch fall
back to TCP (join to rendered world 1.75–1.85 s and 1.25–1.35 s, against about 0.7 s normally;
both include the world load).

## Phase 3 gaps (2026-10-11)

The three gaps left at the Phase 3 report: chunk load under loss, play RTT on a clean link, and
0-RTT with the real client through Velocity.

### Chunk load under loss: closed (it was the harness)

netem's own counters (now printed after every bench run) showed that **the harness undercounted
TCP's losses**: with segmentation offload on, TCP saw ~0.3% loss under the 2% profile, QUIC 2%
(see "netem profiles" above). Fixed in the harness (commit 7ef8a94). With TCP facing the same
loss, QUIC is not slower.

Fabric target, online mode, TCP and QUIC back to back, offloads capped, current code (commit
b152f2b, run `20261011T125228Z-fabric-tcp-quic`), N=20 each, 0 disconnects, 0 failed runs:

| Profile | Metric | TCP | QUIC | QUIC − TCP [95% CI] |
| --- | --- | --- | --- | --- |
| loss 2% | join | 355 ms | 237 ms | −118 ms [−156, −82] |
| loss 2% | chunk load | 597 ms | 434 ms | **−164 ms [−247, +20]** |
| loss 2% | play RTT | 0.40 ms | 0.56 ms | +0.16 ms [+0.12, +0.19] |

Chunk load is bimodal under loss (TCP's median was 419–597 ms across today's runs), so read the
CI, not the median gap: QUIC is not slower.

**Tried and reverted: coalescing stream writes.** Netty's QUIC stream asks quiche for packets on
every write, and Minecraft writes once per game packet, so chunk bursts leave in small packets:
415 bytes on average against TCP's 1366 (12,892 server-to-client datagrams for two joins under
`loss`). Handing writes to Netty once per event-loop pass (commit 40670fb) brought that to about
1,110 bytes (4,054 datagrams; through Velocity 2,284 packets for 2.1 MB instead of 34,645 for
3.5 MB). But on a clean link it leaned the wrong way, N=10 per run, Fabric: play RTT +0.28 and
+0.29 ms against TCP with every write deferred, +0.21 ms with the first write per pass sent at
once (current code in the same conditions: +0.20 ms), and clean chunk load +24, +57 and +49 ms
(current code: −20 ms). Under loss it measured +10 ms [−67, +55] pooled over 40 joins, no better
than the current code. Reverted (b152f2b); worth another look for proxy CPU per player (Phase 9),
where a third of the packets matters more than here.

**Open: chunk load at +150 ms RTT is ~70 ms slower over QUIC.** Phase 3 (current code): +68 ms;
with coalescing (run `20261011T114704Z-fabric-tcp-quic`, N=10): +71 ms [+49, +79]. QUIC runs on
Netty's defaults, 1350-byte UDP payloads and quiche's 10-packet initial window, so ~11% less data
per packet than TCP's 1448-byte segments; that explains perhaps 20 ms. The rest is not explained
yet. Not one of the three gaps this section set out to close, but a regression all the same.

It follows the congestion control. Fabric server, delay profile, N=10 per arm, run back to back
on 44d4d93 (each server's `-Dquicraft.congestionControl` checked in its java command line);
QUIC minus TCP, difference of medians with a 95% bootstrap interval:

| Congestion control | Run | Join | Chunk load | Join + chunk load |
| --- | --- | --- | --- | --- |
| CUBIC | `20261011T152852Z-fabric-tcp-quic` | -42 [-92, -28] | +367 [+284, +374] | +307 [+219, +338] |
| BBR (default) | `20261011T153642Z-fabric-tcp-quic` | -33 [-71, -8] | +67 [+21, +73] | +19 [-37, +48] |

So the gap is the sender's ramp-up, not the harness or the bridge: CUBIC from quiche's
10-packet initial window is five times worse than BBR. With BBR, connect to last chunk is level
with TCP (QUIC's faster join pays for most of the slower chunk load); the chunk-load phase alone
is still ~0.45 RTT behind. Next lever: the initial window (`initialCongestionWindowPackets`,
docs/protocol.md §9), not measured yet.

### Initial window experiment (2026-10-11)

QUIC minus TCP, difference of medians with a 95% bootstrap interval, Fabric server, BBR, N=10 per
arm, run back to back on c04d641 (each server's `-Dquicraft.initialCwndPackets` checked in its java
command line):

| Initial window | Run | Profile | Join | Chunk load | Join + chunk load | Play RTT |
| --- | --- | --- | --- | --- | --- | --- |
| 10 packets (quiche default) | `20261011T181541Z-fabric-tcp-quic` | clean | -135.2 [-159.1, -88.1] | +31.4 [-45.4, +202.5] | -74.0 [-155.0, +55.4] | +0.2 [+0.1, +0.4] |
| 10 packets (quiche default) | `20261011T181541Z-fabric-tcp-quic` | delay | -15.0 [-31.9, +17.7] | +179.5 [-22.7, +275.9] | +148.6 [-20.8, +277.7] | +0.2 [+0.1, +0.4] |
| 32 packets | `20261011T183004Z-fabric-tcp-quic` | clean | -140.0 [-173.1, -85.4] | -23.0 [-59.9, +41.6] | -135.2 [-213.9, -72.9] | +0.2 [+0.1, +0.2] |
| 32 packets | `20261011T183004Z-fabric-tcp-quic` | delay | -111.1 [-130.6, -62.1] | +85.9 [+65.4, +119.9] | +6.9 [-36.7, +28.2] | +0.2 [+0.1, +0.2] |
| 64 packets | `20261011T184452Z-fabric-tcp-quic` | delay | -91.3 [-149.2, -63.5] | -95.5 [-166.0, -76.2] | -188.8 [-283.9, -148.0] | +0.2 [+0.2, +0.3] |

The setting was verified in each arm's java command line. The 10-packet arm ran with
`-Dquicraft.congestionControl=BBR` only. The 32-packet arm's added `-Dquicraft.initialCwndPackets=32`,
and the 64-packet arm's added `-Dquicraft.initialCwndPackets=64`.

Reading (reviewed after the run): the 10-packet arm is noisy. Its QUIC chunk loads split into
two groups (1073-1088 and 1225-1372 ms), and the earlier BBR run on the same code
(`20261011T153642Z`, above) gave +67 [+21, +73]. So 32 packets (+86) shows no gain over 10.
64 packets is the clear change: all ten QUIC chunk loads 916-1004 ms against TCP's 1004-1159,
and connect to last chunk -189 ms. The clean link shows no regression at 32 (intervals overlap).
64 packets was measured at +150 ms only; clean and loss are not measured yet, so the default
stays quiche's 10 until they are (a docs/protocol.md §9 decision).

Clean link and 2% loss (follow-up, 2026-10-11):

| Initial window | Run | Profile | Join | Chunk load | Join + chunk load | Play RTT |
| --- | --- | --- | --- | --- | --- | --- |
| 10 packets (quiche default) | `20261011T190719Z-fabric-tcp-quic` | clean | -158.0 [-187.5, -109.1] | +55.0 [-35.9, +121.0] | -80.5 [-213.3, +19.6] | +0.2 [+0.1, +0.3] |
| 10 packets (quiche default) | `20261011T190719Z-fabric-tcp-quic` | loss | -69.1 [-136.8, -7.7] | -82.2 [-202.8, -22.1] | -189.7 [-297.2, -47.2] | +0.2 [+0.1, +0.2] |
| 64 packets | `20261011T192603Z-fabric-tcp-quic` | clean | -145.7 [-171.2, -102.0] | +1.2 [-62.6, +39.2] | -149.7 [-200.7, -101.4] | +0.1 [+0.0, +0.2] |
| 64 packets | `20261011T192603Z-fabric-tcp-quic` | loss | -107.6 [-164.6, -16.7] | -82.7 [-167.7, -39.2] | -202.0 [-300.4, -113.7] | +0.2 [+0.2, +0.2] |

Verification (same method as above): the 10-packet arm's server ran with
`-Dquicraft.congestionControl=BBR` only; the 64-packet arm's added
`-Dquicraft.initialCwndPackets=64`. Both appear in the respective java command lines.

At 64 packets the clean-link chunk-load difference is +1.2 [-62.6, +39.2] against the 10-packet
arm's +55.0 [-35.9, +121.0]; the QUIC medians themselves are flat (411.6 vs 402.1 ms), the
TCP-only chunk median moved +44.3 ms between the two sessions, and the intervals overlap zero, so
no clean-link regression of ~100 ms or larger was detected at 64 packets, but equivalence is not
established and the narrowing of the QUIC-TCP gap from +55 to +1.2 should not be read as a QUIC
gain. Under 2% loss the two arms are indistinguishable at the median (-82.7 [-167.7, -39.2] at 64
against -82.2 [-202.8, -22.1] at 10, an arm-to-arm difference of -0.5 ms with a CI of about
+/-110 ms); the loss chunk loads are bimodal with overlapping tails (four 10-packet runs at
574-768 ms, one 64-packet run at 1035.5 ms that the same session's TCP arm echoes at 915.5 ms),
and flat 2% loss with deep queues cannot exercise a queue overflow from the larger initial burst.

### Play RTT on a clean link: not closed

End to end, current code, Fabric, clean, N=10 (run `20261011T122358Z-fabric-tcp-quic`): TCP
0.43 ms, QUIC 0.63 ms, **+0.20 ms [+0.11, +0.25]** (Phase 3: +0.14 ms; this machine measured
slower all day, see below). Under loss: +0.16 ms [+0.12, +0.19].

In-process (`quicraft-testkit latency`, 16-byte ping-pong on loopback, hosted QUIC against Netty
NIO TCP), p50 by the idle time between pings:

| Gap between pings | TCP | Hosted QUIC | QUIC − TCP |
| --- | --- | --- | --- |
| 0 (busy threads) | 52 µs | 87 µs | +35 µs |
| 0.5 ms | 97 µs | 139 µs | +42 µs |
| 2 ms | 202 µs | 311 µs | +109 µs |
| 20 ms (game ticks) | 392 µs | 553 µs | +161 µs |
| 50 ms | 406 µs | 585 µs | +179 µs |

Per-hop trace at 20 ms (p50): client write → UDP send 69 µs, server UDP read → stream 54 µs,
server reply → UDP send 38 µs, client UDP read → stream 32 µs: about 190 µs of QUIC processing
per round trip, all of it synchronous on one thread. A CPU profile (async-profiler, local tool
only) puts QUIC's user-space CPU per round trip at a few µs warm: the cost is that work running
on CPUs that idled in between (clock and caches), which TCP does in the kernel. The same code
measures the same today as at Phase 2's commit (550–556 µs), and Netty build quicraft2 the same
as quicraft3; Phase 2's 485 µs was this machine on a faster day.

**It doesn't accumulate.** The cost is per packet and finished before the thread sleeps;
nothing carries over to the next packet. Measured 2026-10-11:

- In-process, 5 minutes at 20 ms spacing (15,000 round trips, 10 blocks): hosted QUIC p50 552 µs
  in the first block (JIT warm-up), then 434–461 µs for blocks 2–10, flat; TCP 358 µs, then
  296–327 µs. The gap stays at ~140 µs from start to end.
- In the game, a 5-minute play session per transport through the Fabric server (run
  `20261011T133541Z-fabric-tcp-quic`, 1,200 pings each, none lost, no disconnect): QUIC median
  0.605 ms, p95 0.84 ms; TCP 0.349 ms, p95 0.46 ms. QUIC's median is no higher than in the
  10-second sessions (0.63–0.70 ms).

It could only build up if packets arrived faster than a loop can handle them, so they queue: a
capacity question for many players on one loop (proxy CPU per player, Phase 9), not one that
time or session length brings on.

Accepted by the user (2026-10-11) on that condition, with Phase 3.

What would close it, all outside this change:
- **Busy-polling** the event loop briefly before it sleeps: keeps the CPU awake, at a CPU cost
  on every server and client. It can only reach the warm floor, +35 µs, not zero.
- **Fewer instructions per packet** in Netty QUIC / quiche. A quiche change only reaches
  linux-x86_64 (every other platform runs upstream natives), so it can't close a client-side gap.
- **Accept it**: ~0.1–0.3 ms per round trip, against Minecraft's 50 ms tick and a ping display in
  whole milliseconds.

### 0-RTT through Velocity with the real client: closed (offline mode)

`testkit/run-real-client.sh` (commit b152f2b): the real 26.1.2 Fabric client against the testkit's
Velocity → Paper, offline mode. First join over QUIC, in the world after 1744 ms; disconnect as
the pause menu does; rejoin over QUIC with 0-RTT, in the world after 551 ms; 5 s in the world each
time, no disconnect. Velocity's `earlyReleases` went from 0 to 1, so it acted on the 0-RTT data.
Online mode isn't covered with the real client: its authlib finds the session server through a
discovery URL the testkit's mock doesn't serve. (The headless client covers online-mode 0-RTT
through Velocity, 10 of 10, earlier in this file.)

The first attempt failed with "You are already connected to this proxy!": the test disconnected
with `Minecraft.disconnect`, which leaves the network connection open (the pause menu closes it
first through `disconnectFromWorld`). A bug in the test, not in QUICraft.

## Connection capacity: the 50k concurrent target (2026-10-14)

First measurement of the Architecture scale target (50,000 concurrent QUIC players per node).
Transport level only: the real `QuicServer` and the real `QuicClient`, handshakes completed and then
held idle. No Minecraft protocol and no game traffic. Harness: `load-server`/`load-client` in
`testkit` plus `testkit/run-load-spike.sh`, branch `agent/phase4-load-spike` (unmerged, commits
0a86a4d and 1aaa00a). Raw logs and the sampling TSV are gitignored; the run is
`testkit/results/load-spike-20261013-233834`.

### Method

- One `QuicServer` on `127.0.0.1:65530` with admission limits raised, since the test measures
  capacity rather than the limits: maxOpen 100,000, maxTrackedIps 300,000, globalAttemptsPerSecond
  100,000, idle timeout 10 min.
- One client JVM, one virtual thread per connection, one unique `127.x.y.z` source address per
  connection (bypasses the ~28k ephemeral-port wall and the per-IP limits), ramp target 600/s, hold 60 s.
- Guards (machine: 20 cores, 62 GiB RAM, ~43 GiB available, linux-x86_64, JDK 25, loopback): one
  systemd scope per JVM with `MemoryMax=40G` and `MemorySwapMax=0`; the driver aborts below 8 GiB
  `MemAvailable` or above 36 GiB client RSS and kills both JVMs.

### Results

| Metric | Value |
| --- | --- |
| Concurrent connections held | 41,403 |
| Failed connections | 0 |
| Server RSS at peak | 1,625 MiB, ~37 KiB/connection |
| Server CPU (single event loop) | 23 s total, ~27% of one event-loop thread over the ~85 s run |
| Client RSS at peak | ~35 GiB, ~850 KiB/connection |
| Client CPU | 126 s total, ~3 ms/connection |
| Actual ramp | ~500 connections/s |
| Stop reason | free-RAM guardrail at 8 GiB, by design |

Server-side extrapolation to 50,000 connections: about 1.9 GiB RSS. The server is not the limit.

### Finding

The wall is the synthetic client JVM: ~850 KiB of native memory per connection. It is untracked by
NMT and by JFR's direct-buffer statistics, while the same native library costs ~37 KiB per
connection on the server side of the same process pair. 50,000 client connections in one JVM would
need about 42 GiB, more than this machine can give while keeping the 8 GiB safety margin.

This is a limitation of the test harness, not of the product. In production each player carries one
connection on their own machine, where 850 KiB is irrelevant, and a proxy carries the server-side
cost measured above.

Tested and ruled out as the cause, each with no change to the slope: plain sockets (3,000 JDK
datagram sockets cost 131 MiB in total), Java heap (52 MiB live at 3,000 connections), JDK direct
buffers (JFR reports zero), transparent huge pages (off), glibc arenas (`MALLOC_ARENA_MAX=1`), UDP
payload size (`maxRecv`/`maxSendUdpPayloadSize=1350`), flow-control windows (`initialMaxData` and
`initialMaxStreamData` at 256 KiB), TLS session resumption (shared context), and Netty allocator
settings (`io.netty.noPreferDirect=true`, `allocator.type=unpooled`).

### Handshake burst and UDP sockets (2026-10-14)

The held-connection test above ramps at ~500/s, which one event loop handles. A faster burst shows
the single-loop limit, and the multi-socket listener (`udp-sockets`, SO_REUSEPORT, `docs/protocol.md`
section 11) removes it. Same machine and harness as above, on `feat/phase4-alpha` after the reuseport
merge: `COUNT=10000 RAMP_RATE=2000 HOLD_SEC=20 SOCKETS=<n> [SHIPPED_LIMITS=1] bash
testkit/run-load-spike.sh`, one run each. `SOCKETS` sets the listener's socket count; the script
records the sockets actually bound (`ss`). Receive-buffer drops are the change in the host's UDP
`RcvbufErrors` (`/proc/net/snmp`) across the run.

| Sockets | Admission limits | Connected / 10,000 | Failed | UDP receive-buffer drops | Attempts resolved in |
| --- | --- | --- | --- | --- | --- |
| 1 | raised | 9,091 (run aborted) | 686 at abort | 12,031 | aborted by the 5% failure guard |
| 4 | raised | 10,000 | 0 | 0 | 5 s |
| 8 | raised | 10,000 | 0 | 0 | 5 s |
| 1 | shipped defaults | 9,537 | 463 | 8,658 | 10 s |
| 4 | shipped defaults | 10,000 | 0 | 0 | 6 s |

Run directories (gitignored): `testkit/results/load-spike-20261014-192918`, `-192941`, `-193018`,
`-193050`, `-193132`. Server RSS at 10,000 connections stayed between 581 and 640 MiB.

Finding: at a 2,000/s handshake burst, one socket's event loop falls behind, the kernel drops
datagrams at its receive buffer, and handshakes time out (5 to 7% failed). With 4 or more sockets
every handshake completed and nothing was dropped, with the shipped admission defaults as well as
the raised ones: the shipped defaults are not what limits a burst of this size. One run per row,
loopback only, transport only; 8 sockets showed no gain over 4 at this rate.

### Next

- Root-cause the client-native per-connection cost in a follow-up unit. If it can be cut, one
  machine can generate 50k; otherwise the harness runs sharded across two machines.
- Game-traffic CPU per player on the proxy path is still unmeasured (Phase 9). This test covers
  connection state only, not gameplay.

## /3 node budget: idle activated connections (2026-10-16)

This test checks the per-connection reservation of the lazy read grants (`protocol.md` 13.12) against the node pending-bytes budget.

**Setup:**
- Branch `feat/phase5-multistream` at 59aa1e6, same machine as above.
- Transport level only: `MODE=v3 LANES=1 bash testkit/run-load-spike.sh`.
  - Each connection activates its three /3 lanes with an idle game and no Minecraft binding proof.
  - `NODE_BUDGET_MB` sets the budget.
- One run per row, ramp 50/s for the 1 MiB row and 500/s for the others.

| Node budget | Attempts | Held activated | Not held (failed + closed) | `0x11` closes seen | Server RSS at the end |
| --- | --- | --- | --- | --- | --- |
| 1 MiB | 100 | 34 | 66 (22 + 44) | 0 | 160 MiB |
| 256 MiB (shipped default) | 10,000 | 8,738 | 1,262 (780 + 482) | 0 | 662 MiB |
| 1 GiB | 10,000 | 10,000 | 0 | 0 | 686 MiB |

Run directories (gitignored):
- `testkit/results/load-spike-20261016-175523`
- `testkit/results/load-spike-20261016-175709`
- `testkit/results/load-spike-20261016-175807`

**Finding:** the held counts match the specified 30 KiB idle reservation exactly:
- 1 MiB ÷ 30 KiB = 34.
- 256 MiB ÷ 30 KiB = 8,738.

Before lazy grants, the same 1 MiB run held 17 connections. The 30 KiB reservation is made of:
- the 24 KiB quiche-unread share;
- read grants of 4 + 1 + 1 KiB.

Every attempt past the budget was turned away: it either failed to connect or was closed after connecting. No log showed a `0x11` peer-violation close. The harness does not record why a connect failed, so the split between failed and closed is not broken down further.

**Not yet a scale answer:**
- At 30 KiB, 50,000 activated connections need about 1.43 GiB of node budget.
- Under the serverbound dual-lane draft, which also reserves a 40 KiB inflater per connection at admission, they need about 3.05 GiB.
- So the 256 MiB default is a small-server value, not the product ceiling. The per-hardware profiles will set it from measurements.

## Planned Phase 5 measurements

Not run yet. These measurements are planned for the 2026-10-16 direction in `docs/protocol.md`
§13 ("Direction (2026-10-16)"). Nothing below is a result.

- Head-of-line blocking under loss: chunk-load time and play round-trip on the netem profiles
  (clean, loss, delay, reorder), comparing TCP, single-stream and chunk streams from 1 to 6.
- Server CPU per player as each stream is added, on the same hardware and with the same
  player count.
- Compact-header bytes, per-shard/CONTROL/REALTIME buffer peaks, execution and native/read
  exposure, cross-shard ack/batch join latency, and scheduling fairness under sustained
  REALTIME load, including all progress reservations occupied (`protocol.md` 13.12-13.20).
- Per-connection memory and peak concurrent players per node. The values in each planned
  server profile will be taken from these runs, not chosen in advance.

## Phase 9 follow-up: continuity trim and declared-waiter drain (2026-10-17)

Not measured yet. Nothing below is a result, and no index has been added. The entries record what
to measure so the scans are not forgotten.

- `PressureRanking.trimContinuity` (`core/src/main/java/rs/sudoe/quicraft/core/resource/PressureRanking.java`,
  around line 364): each trim scans the access-ordered continuity map from its oldest entry, first for an
  expired record, then for a record whose bucket has live users. Under churn the map sits at its bound of
  131072 entries, so every insert past the bound can cost a scan of up to 131072 entries. Measure the trim
  cost per insert under sustained churn at that bound.
- `PreLoginRegistry.drainDeclaredWaiters` (`core/src/main/java/rs/sudoe/quicraft/core/transport/PreLoginRegistry.java`,
  around line 338): `declaredInFlight` (line 275) and `bytesByFine` (line 286) each walk the staged and shedding
  sets, so one drain is O(declared waiters x staged). Measure a drain with declared waiters at 50,000 staged
  entries, and a drain after a release.
- Pending admission (U8b): a pending newcomer is woken on every confirmed shed (`PreLoginRegistry.waiters`), so a
  confirmed close costs O(waiters) plus one retry per woken newcomer. Measure it in the same 50k run.
- Index either scan only if these measurements show it matters at the scale target (50,000 concurrent players
  per node). Until then both stay as written.

## Transport-neutral /3 benchmark schema and six-profile matrix (planned, not measured)

**Not measured yet. Nothing in this section is a result.** It defines the result fields and the
comparison rows that a later run must fill in. The `/3` arm waits on U3: this scaffolding has no
scope identities, barrier rules, packet routing or real `/3` game driver, and it must not be read
as `/3` readiness.

### Result schema

Every measured run object in a bench result file carries these provenance fields next to the
existing Phase 0 metric fields. The existing fields and their meanings are unchanged. The fields are
part of `testkit/.../bench/RunResult.java`; `schemaVersion` is the result schema, not the routing
resource schema.

| Field | TCP | QUIC `/2` | QUIC `/3` |
| --- | --- | --- | --- |
| `schemaVersion` | `1` | `1` | `1` |
| `transportVersion` | `TCP` | `QUIC_2` | `QUIC_3` |
| `streamCount` | `1` | `1` | negotiated stream count, at least `1` |
| `buildCommit` | Git commit, or `unknown` for a manual run | Git commit, or `unknown` for a manual run | Git commit, 7 to 40 lowercase hex characters, no `-dirty` suffix |
| `routingTableId` | empty | empty | routing resource identity, not a placeholder |
| `routingTableDigest` | empty | empty | SHA-256 R, 64 lowercase hex characters |
| `routingTableSchema` | `0` | `0` | `2` |
| `negotiatedAlpn` | empty | `quicraft/2` | `quicraft/3` |
| `negotiatedTransportVerified` | true after a completed TCP join | true after a completed QUIC join | true only with the complete negotiated evidence below |

A failed run keeps the transport it attempted but sets `negotiatedAlpn` empty and
`negotiatedTransportVerified` false. A result file also records the base netem profile in
`netemProfile`, while `profile` keeps the transport-suffixed label used by the Phase 0 summarizer.

### Comparison rows

`matrix-summarize` emits one row per netem profile and metric (`join`, `chunk load`, `play RTT`),
with one cell for each transport: TCP, QUIC `/2`, QUIC `/3`. A cell is `verified` only when it has
at least one successful measured value. A missing or rejected arm is printed as `not run` or
`fail-closed` and carries no number.

A measured run that cannot be attributed to one of the six profiles is unclassifiable evidence and
fails the gate; only a known profile the caller did not request is ignored. A successful run that
reports a negative `joinMs` or `chunkLoadMs` is rejected as impossible evidence instead of being
dropped from the sample, so the matrix fails closed. `rttMedianMs` is different: `-1` records a
successful run that took no ping sample, so a cell with no valid RTT sample is printed as `not run`
and never as a median, and a verified RTT cell reports the number of valid samples it used.

The `/3` cell is fail-closed. It may be `verified` only when every contributing `/3` run satisfies
all of these:

- `transportVersion` is `QUIC_3`;
- the run completed successfully;
- `negotiatedTransportVerified` is true and `negotiatedAlpn` is `quicraft/3`;
- `routingTableId` is non-empty and not a placeholder, `routingTableDigest` is 64 lowercase hex
  characters, and `routingTableSchema` is `2`;
- `buildCommit` is a clean Git commit of 7 to 40 lowercase hex characters, and `streamCount` is
  at least `1`;
- the result schema is current;
- all contributing `/3` runs use the same routing table, build commit and stream count.

A run whose `transportVersion` is `QUIC_2` is never promoted to `/3`, even if every other field
looks like `/3` evidence. The unit tests construct exactly that case and require `fail-closed`.

### Six-profile matrix

`testkit/run-matrix.sh` runs the matrix over the six profiles defined in
`testkit/netem/profiles.sh`: `clean`, `loss`, `delay`, `delayloss`, `reorder`, `jitter`. It fixes
the Phase 0 parameters and delegates measurement and container cleanup to
`testkit/run-baseline.sh`; it does not duplicate the Docker or netem logic.

- `BATCHES=3`.
- `N=20` measured joins for `clean` and `delay`; `N=80` for `loss`, `delayloss`, `reorder` and
  `jitter`; `WARMUP=2`.
- Tolerance `max(10%, 5 ms)` per batch median against the all-batch median.
- Zero disconnects and zero failed runs.
- `TRANSPORTS="tcp quic"`: TCP and the single-stream `/2` path. The `/3` arm is deliberately not
  started before U3.
- Artifacts go to `testkit/results/matrix-<UTC timestamp>/`: `environment.txt`,
  `matrix-environment.txt`, `run-baseline.log`, the Phase 0 `summary.md`, `matrix-summary.md` and
  `matrix-status.txt`. Container cleanup stays with `run-baseline.sh`.

`matrix-summarize` exits `0` only when every requested profile has verified TCP, `/2` and `/3`
cells and every measured run is usable evidence. It exits `1` when `/3` evidence is rejected, a
measured run failed or disconnected, a measured run comes from a different schema, a required
metric field is missing, a successful run reports an impossible metric value, or an artifact could
not be read or carries no usable netem profile. It exits `2` while `/3` evidence is absent, which
is the expected state before U3. The presence of `/2` results never turns that `2` into a pass.

## Real Fabric client, TCP vs /2 vs /3, six profiles: first measured rows (2026-10-20, n=3)

Not a gate result. Three measured runs per arm and profile, far below the matrix above (N=20 or 80
per batch, 3 batches). Read the table as "the path works under every profile and these are the
first numbers", not as a comparison with a stated tolerance. No claim of improvement is made here.

### Method

- Build `f9db03c` (`feat/phase5-multistream`), every row's `buildCommit`. Real Fabric 26.1.2
  client (`ExternalArmGameTest`) against a containerized Fabric dedicated server, offline mode.
  One fresh server container and one fresh client JVM per run; arms interleaved tcp, quic2, quic3
  per run index; `testkit/run-external-arms.sh` per run, serialized by an flock.
- Arms: `tcp` = `TCP_ONLY`; `quic2` = `QUIC_ONLY`, multistream off, server multistream off (a /3
  listener is /3-only); `quic3` = `QUIC_ONLY`, multistream on (ALPN `quicraft/3`, 5 streams,
  routing table `quicraft-routing-p775-s2`, schema 2). Every row self-checks the negotiated
  transport (`negotiatedTransportVerified`) and fails if a status query runs inside the measurement.
- Timing as `BenchClient`: join = play-login packet arrival after `startConnecting`; chunk load =
  play login to the last chunk packet, after 2 s with no chunk; RTT = play ping/pong every 5 ticks for
  20 s, idle player.
- Netem on the server container's interface (`testkit/netem-attach.sh`, both directions):
  loss `loss 2%`; delay `delay 75ms`; delayloss `delay 75ms loss 2%`; reorder
  `delay 10ms reorder 25% 50%`; jitter `delay 10ms 5ms distribution normal`; clean attaches
  nothing. Shaping proven per run: e.g. loss run 0 dropped tcp 26/1391, quic2 540/25447, quic3
  490/25522 packets; delay gives ~151 ms ping RTT (75 ms each way).
- Validation: per arm and profile the merged document passes `testkit arm-samples`; /3 also passes
  `testkit v3-samples`. 54/54 runs ok. Structural checks only; provenance is self-reported by the
  emitter.
- Raw rows: `testkit/results/arms-2026-10-20/r2/<profile>/` (gitignored, local).

### Rows (medians over 3 runs)

| profile | arm | n ok/rows | join ms (median, min-max) | chunk load ms (median) | rtt median ms (median of runs) | rtt p95 ms (median) | rtt lost (sum) | streams |
|---|---|---|---|---|---|---|---|---|
| clean | tcp | 3/3 | 1224 (1157-1268) | 981 | 0.59 | 0.89 | 0 | 1 |
| clean | quic2 | 3/3 | 1469 (1409-1616) | 1542 | 0.96 | 1.39 | 0 | 1 |
| clean | quic3 | 3/3 | 1561 (1401-1585) | 2503 | 1.33 | 1.88 | 0 | 5 |
| loss | tcp | 3/3 | 1393 (1151-1406) | 1027 | 0.64 | 1.16 | 0 | 1 |
| loss | quic2 | 3/3 | 1516 (1458-1678) | 1637 | 0.96 | 1.56 | 0 | 1 |
| loss | quic3 | 3/3 | 1631 (1615-1634) | 2475 | 1.31 | 2.45 | 0 | 5 |
| delay | tcp | 3/3 | 1894 (1840-1934) | 2023 | 150.72 | 152.87 | 0 | 1 |
| delay | quic2 | 3/3 | 2232 (2030-2331) | 2067 | 150.94 | 153.08 | 0 | 1 |
| delay | quic3 | 3/3 | 2569 (2552-2602) | 3361 | 151.31 | 153.32 | 0 | 5 |
| delayloss | tcp | 3/3 | 1863 (1815-1942) | 18191 | 150.65 | 308.27 | 0 | 1 |
| delayloss | quic2 | 3/3 | 2411 (2074-3323) | 3596 | 263.69 | 346.50 | 0 | 1 |
| delayloss | quic3 | 3/3 | 2629 (2497-2954) | 3220 | 261.84 | 406.25 | 0 | 5 |
| reorder | tcp | 3/3 | 1243 (1209-1411) | 1745 | 20.63 | 21.05 | 0 | 1 |
| reorder | quic2 | 3/3 | 1629 (1511-1649) | 1710 | 20.88 | 21.43 | 0 | 1 |
| reorder | quic3 | 3/3 | 1732 (1727-1805) | 2713 | 21.04 | 21.83 | 0 | 5 |
| jitter | tcp | 3/3 | 1335 (1294-1424) | 8254 | 21.95 | 31.82 | 0 | 1 |
| jitter | quic2 | 3/3 | 1640 (1447-1668) | 2380 | 20.97 | 34.13 | 0 | 1 |
| jitter | quic3 | 3/3 | 1752 (1644-1824) | 6125 | 20.55 | 32.40 | 0 | 5 |

### Caveats

- **Routing table update (2026-10-20):** the current protocol-775 table digest is
  `d7aae8281ba38d4a9463bcad90c14e97eb40620a268c296fc1db7bdb6e910c5c`. The measurements and
  trace diagnosis below predate this update and retain their historical digest and routing behavior.
- **The /3 rows measure /3 with no chunk shards in use.** The shipped table
  `quicraft-routing-p775-s2` routes `level_chunk_with_light` to CONTROL (the CHUNK-route deferral
  in `protocol.md` 13.7), so every chunk rides the control stream and the three chunk streams stay
  idle. Opt-in traces (2026-10-20, branch `agent/p5-pipeline`) show zero writes on streams 2-7 and
  one Netty stream flush per server write (a no-op in our pinned Netty QUIC, not a cause). These rows are not a measure of sharded /3; the /3 >= 3 streams
  check counts opened streams, not streams that carried data.
- n=3 per cell: ranges are wide (e.g. delayloss quic2 join 2074-3323 ms). No tolerance is met or
  claimed.
- Chunk load under delayloss/jitter varies most; the TCP cells there (18191 ms, 8254 ms) are
  medians of three and need the full matrix before any reading.
- QUIC play-ping RTT under delayloss (~262 ms median) sits above TCP's (~151 ms); not investigated.
- Clean link: /3 chunk load (2503 ms) and join (1561 ms) are above TCP (981 ms, 1224 ms) and /2;
  not investigated.
- Loopback host, one machine, idle player, 26.1.2 offline only. No online-mode (channel-binding) run.

### /3 clean-link trace diagnosis (2026-10-20, diagnostic only)

Read the two operator-run `testkit/results/trace2/` samples and their client/server logs,
build `21035c7fed92d029dc86cf7f812b5739494ff43b`. These are opt-in instrumented observations,
not a performance gate or new uninstrumented baseline. Both JSON rows verify `/3`, schema 2,
`quicraft-routing-p775-s2`, digest
`ced506e3ab08c092eadb12c9ef1fa215c2eb75ecf4eab5dbfc8ddec78df94ff1`, N=3 (five streams).

| Run | Chunks | chunkLoadMs (JSON) | Client units/stream at chunk quiet | Client one-byte/read requests | Server writes/stream at disconnect |
| --- | ---: | ---: | --- | --- | --- |
| 0 | 157 | 1851.285440 | [713,3788,0,0,0] | 93780 / 98814 | [2128,21226,0,0,0] |
| 1 | 157 | 3482.787638 | [852,4952,0,0,0] | 120535 / 126912 | [2270,18706,0,0,0] |

The client chunk-quiet snapshots report no-local-bytes totals 3800.829 / 5367.604 ms;
server snapshots report zero pool-grant wait, zero stream/parent unwritability and zero
writes selected inside the one-second tick. Server totals include the idle-play interval;
client chunk-quiet totals include activation/configuration, not just the JSON chunk interval.
Summed per-record queue latencies are not wall-clock elapsed time, and cannot be compared
directly to that interval or treated as proof that flushing caused the delay.

**Historical explanation for no CHUNK writes:** these samples used the previous shipped CP2 table, not resolver
fallback, extractor failure, an epoch switch or a disabled/missing shard configuration.
`protocol-775.json` explicitly assigns clientbound PLAY id 45 (`level_chunk_with_light`)
CONTROL/global, with no position or extractor and `epoch_change:false`; the only bound
position decoder is the multi-chunk list decoder. `SendRouteResolver.resolve` therefore
returns GLOBAL, and `SessionSendDriver.streamFor` chooses CONTROL without calling a chunk
extractor. The frozen shipped-table exception in protocol.md 13.7 explicitly defers all
CHUNK routes, although the intended complete routing model in 13.8 places installs/updates/
forget on position shards. `ShippedRoutingCoverageTest` intentionally expects an empty
CHUNK set. Core's reviewed coordinate extractor works for real id 45 under an in-memory
proposed route; new resolver tests pin both behaviours, including 13.15 bundle promotion.
Those are diagnostic coverage tests, not a fail-before/pass-after bug fix.

Some causal submission infrastructure has since landed (`V3Join.submitUnitNow` calls
`sendCovered`), so the older statement in 13.7 that no adapter calls coverage hooks is
historical. That does not prove the gate complete: the production source has no calls to
the ACK/batch lifecycle methods, and `OutboundUnit.requireSupportedCoverage` still refuses
ACK/batch metadata and multi-token/bundle coverage. Flipping chunk routes alone would
bypass the explicit ordering/security deferral. No frozen table/spec or routing policy was
changed for this diagnosis. The real-Fabric wrapper enables multistream, and negotiation
uses the configured preference N=3 (maximum 6); changing N does not change table lanes.

**Why one-byte grants:** `CompactDecoder.wanted()` requests one byte until the outer length
VarInt is complete; `ClientReadPump` then requests a bounded prefix/body, never more than
the current charged unit still needs. `QuicStreamByteSource.read` issues a capped channel
read when its local buffer is empty. Every pump step probes all available streams, including
the three unused CHUNK streams still waiting for their first record-length byte. Thus the
one-byte counter counts requests, including repeated pending/empty probes, not received
bytes or UDP packets. Actual client read-complete counts at chunk quiet are 10013 / 12691,
far below the request counts. This is conservative framing/admission implementation, not
evidence that payloads are transferred byte-by-byte. The one-head/charged-capacity/no-next-
unit-prefetch invariants are mandatory (13.12); exactly-one-byte client prefix probing is
not itself a wire-format requirement. No correctness bug or licence to relax those bounds
was established. Read behaviour is unchanged.

**Flush batching: tried, no effect (2026-10-20).** `QuicStreamWriteSink.write` calls
`writeAndFlush` per selected slice, so the trace shows one flush per write. In our pinned Netty
QUIC (4.2.19.Final-quicraft5) a stream's `flush()` is a no-op and `write()` already passes the
bytes to quiche and sends the connection, so the count is API calls, not datagrams. A bounded
batching change (`agent/p5-pipeline` `965e251`, not merged) ran /3 clean at 16.0, 16.8 and
19.3 ms per chunk (157 chunks each) against 11.8-22.8 before: no measurable gain, as expected
from the no-op flush. Any real batching would have to happen at the connection level.

### /2 write coalescing experiment (2026-10-21, delayloss, n=3, interleaved TCP)

`-Dquicraft.coalesceWrites` (server and client, `agent/p5-coalesce-bench` `9de193d`) merges small
`NettyQuicByteStream` writes into one buffer per event-loop turn and flushes once. Real Fabric client,
delayloss profile (75 ms delay, 2% loss), 20 s play window, runs interleaved tcp / off / on, one TCP warmup
discarded. Results in `testkit/results/coalesce2-2026-10-21/` (gitignored).

| arm | join ms (median, min-max) | chunk load ms (median, min-max) | rtt median ms | rtt p95 ms (median) |
|---|---|---|---|---|
| tcp | 2101 (2077-2155) | 23555 (13957-29225) | 150.7 | 281.1 |
| /2 coalescing off | 2265 (2208-3556) | 2964 (2723-3215) | 273.5 | 372.5 |
| /2 coalescing on | 2480 (2316-2663) | 4852 (3461-6753) | 151.2 | 322.0 |

Coalescing removes the movement-RTT penalty under loss (median 273.5 -> 151.2 ms, TCP 150.7): the
penalty came from one QUIC packet per small write (the pinned Netty QUIC sends on every stream
`write()`; `flush()` is a no-op), which multiplies the loss exposure of the single ordered stream. Chunk
load got slower and noisier (median 2964 -> 4852 ms), still far ahead of TCP. An earlier round without the
drain flush (n=3) gave the same RTT gain (151.2 ms) with chunk load 5567 ms. Not yet the default; /3 has
the same 262 ms RTT penalty under delayloss (six-profile table above) and gets its own experiment.

### /3 write coalescing (2026-10-21, n=3 per arm, interleaved TCP)

`QuicStreamWriteSink` gathers the slices of one drain round per logical stream and writes one buffer per stream
(soft cap 64 KiB), instead of one QUIC packet per record slice. Same real-Fabric method as above; build includes the
universal ACK rule (protocol.md 13.21). Three sessions, each with its own TCP arm. Medians, ranges in brackets.

| session | profile | arm | join ms | chunk load ms | rtt median ms | rtt p95 ms |
|---|---|---|---|---|---|---|
| A | delayloss | tcp | 2104 | 18429 (12633-28940) | 150.8 | 309.1 |
| A | delayloss | /3 off | 2761 | 4192 (3879-4609) | 235.3 | 364.3 |
| A | delayloss | /3 on, 64 KiB | 3257 | 5190 (3710-5805) | 151.7 | 311.5 |
| B | delayloss | tcp | 2060 | 33116 (11717-36751) | 150.7 | 333.3 |
| B | delayloss | /3 on, 64 KiB | 2741 | 4699 (3950-4787) | 151.6 | 278.8 |
| B | delayloss | /3 on, 1200 B | 2848 | 4353 (3218-7783) | 151.5 | 322.6 |
| C | clean | tcp | 1323 | 1251 (900-1321) | 0.58 | 0.90 |
| C | clean | /3 off | 1684 | 3286 (2059-3420) | 1.33 | 1.73 |
| C | clean | /3 on, 64 KiB | 1730 | 3296 (3121-4240) | 1.37 | 1.81 |

Coalescing brings the /3 movement RTT under loss to TCP parity (235.3 -> 151.7 / 151.6 ms, TCP 150.8 / 150.7) and
is neutral on a clean link. Its chunk-load cost under loss is within run-to-run noise at n=3 (on-arm ranges overlap
the off arm). A 1200 B cap is not better than 64 KiB. Decision: /3 coalesces by default (`-Dquicraft.coalesceWrites=false`
turns it off); /2 keeps it opt-in because its chunk-load cost was larger (2964 -> 4852 ms). Open: /3 on a clean link
still trails TCP on chunk load (3296 vs 1251 ms) and RTT (1.37 vs 0.58 ms).

### /3 chunk-batch pacing: batch timer from the first chunk (2026-10-21, n=3 per arm, interleaved TCP)

Diagnosis: the vanilla client times a chunk batch from `chunk_batch_start` to `chunk_batch_finished` and the server
sizes the next batch from that rate. On /3 the start record rides CONTROL and arrives well before the chunks on their
shards, so the measured rate collapses to about 3 chunks per tick (batches of 1-2 chunks against 9-13 on TCP; a
diagnostic build forcing 64 chunks per tick loaded in 806 ms vs TCP 858 ms, n=1). Fix: on /3 only, the client
restarts the batch timer at the first `level_chunk_with_light` of the batch (`ChunkBatchTimerMixin`,
`-Dquicraft.v3.batchTimerFromFirstChunk=false` turns it off). A server-side rate floor was also tried and rejected:
under delayloss it out-produced the link and 3 of 6 runs closed with 0x12 (blocked-head timeout).

Production defaults (coalescing on, timer fix on), commit ce9b9e1, same real-Fabric method as above:

| profile | arm | join ms | chunk load ms | rtt median ms | rtt p95 ms | disconnects |
|---|---|---|---|---|---|---|
| clean | tcp | 1470 | 753 (729-856) | 0.59 | 0.93 | 0 |
| clean | /3 | 1695 | 1497 (1480-1783) | 1.50 | 2.26 | 0 |
| delayloss | tcp | 2115 | 22676 (14384-34903) | 150.72 | 265.21 | 0 |
| delayloss | /3 | 2724 | 3212 (2617-3737) | 151.69 | 305.03 | 0 |

Against the coalescing table above (clean /3 3296 ms, delayloss /3 4699-5190 ms) the clean-link chunk load halves
and delayloss improves, with RTT still at TCP parity under loss. Open: clean /3 still trails TCP (1497 vs 753 ms
chunk load, 1.50 vs 0.59 ms RTT); join is ~0.2-0.6 s slower.

After review, the timer restarts only on the network-thread pass of a chunk (vanilla replays the handler on the
main thread). Smoke rerun at 4b10c7d, n=2 per arm, interleaved TCP: clean tcp chunk 861 (843-880) / /3 1591
(1472-1710) ms, RTT 0.61 / 1.81 ms; delayloss tcp 24056 / /3 3101 ms, RTT 150.67 / 151.70 ms; 0 disconnects. The
gain holds. The clean /3 RTT (1.81 vs 1.50 ms) and join (2049 vs 1695 ms) are higher than the n=3 table, within
what n=2 can tell apart.

### /3 on a slow link (2026-10-21, n=2 per arm, interleaved TCP)

New `slow` netem profile: `delay 75ms loss 2% rate 2mbit` each way (delayloss plus a bandwidth cap, so a server
that out-produces the link builds a queue). Production defaults at 4b10c7d (coalescing on, first-chunk batch timer
on). Cap confirmed in the netem status (`rate 2Mbit` on both qdiscs). Per run:

| arm | run | join ms | chunk load ms | rtt median ms | rtt p95 ms | disconnected |
|---|---|---|---|---|---|---|
| tcp | 0 | 2069 | 28833 | 151.36 | 303.39 | no |
| tcp | 1 | 2085 | 39376 | 151.32 | 303.31 | no |
| /3 | 0 | 2944 | 6401 | 152.32 | 322.35 | no |
| /3 | 1 | 2673 | 6191 | 152.38 | 319.70 | no |

With vanilla pacing (no server-side rate floor) /3 does not hit the blocked-head deadline (0x12) on this link, and
loads chunks about 5x faster than TCP. The 0x12 seen with the rejected server floor stays a risk for any change
that makes the server send faster than the client's measured rate.

### /3 clean-link chunk gap: what it is not (2026-10-21, diagnostic)

Batch trace (`agent/p5-batchdiag`, gametest-only `quicraft.batch.trace`, clean, n=1 per arm): time from the last
chunk of a batch to `chunk_batch_finished` is 0.1-0.6 ms on TCP and 0.2-0.6 ms on /3 (one 10.4 ms outlier), so the
batch end is not skewed, and chunk deliveries do not wait for main-thread application (applies trail by tens of ms).
The difference is inside the batch: /3 shows 5-10 ms gaps between chunk deliveries in about half the batches,
and the client's desired rate settles at ~5.5 chunks per tick against ~9.7 on TCP.

Congestion control is not it: clean, n=2, interleaved TCP, both ends switched with `-Dquicraft.congestionControl`:

| arm | chunk load ms (r0, r1) | rtt median ms (r0, r1) |
|---|---|---|
| tcp | 767, 1116 | 0.59, 0.58 |
| /3 BBR (default) | 1360, 1395 | 1.58, 1.42 |
| /3 CUBIC | 1371, 1536 | 1.43, 1.45 |

Open: the in-batch gaps (server send scheduling, the client read pump, or ACK timing remain candidates).

### /3 receive-path experiments: inline pump and read-ahead (2026-10-21, n=3 per arm, interleaved TCP)

Same real-Fabric method as above (one fresh server and client per run, arms interleaved
tcp, /3, /3 inline pump, tcp, /3 read-ahead; n=6 for tcp). Base = integration 43c1f29; inline pump =
`agent/p5-pump-hops` 4cf519c (the client runs the receive pump on the loop right after an apply instead of
queueing it); read-ahead = `agent/p5-readahead` ed63bd5 with `-Dquicraft.v3.readAhead=true` confirmed on the
client JVM (several complete units buffered per stream within the 13.12 slot). Median (per-run values):

| profile | arm | join ms | chunk load ms | rtt median ms | disconnects |
|---|---|---|---|---|---|
| clean | tcp | 1317 | 825 (704-1164) | 0.59 | 0 |
| clean | /3 | 1788 | 1329 (1162, 1329, 1665) | 1.47 | 0 |
| clean | /3 inline pump | 1628 | 1286 (1268, 1286, 1419) | 1.48 | 0 |
| clean | /3 read-ahead | 1697 | 1388 (1367, 1388, 1489) | 1.43 | 0 |
| delayloss | tcp | 2131 | 21129 (3167-42292) | 150.77 | 0 |
| delayloss | /3 | 2935 | 2974 (2805, 2974, 3206) | 151.56 | 0 |
| delayloss | /3 inline pump | 2960 | 3487 (3283, 3487, 4333) | 151.29 | 0 |
| delayloss | /3 read-ahead | 2745 | 3231 (2998, 3231, 3616) | 151.75 | 0 |

Neither change moves the clean-link chunk load beyond run-to-run noise, so neither is merged (both branches
kept). Under delayloss the inline-pump runs all sit above the base runs (n=3, not conclusive). The /3 gain
under delay and loss holds: 2974 vs 21129 ms chunk load, about 7x. Open: the clean-link gap (~0.5 s chunk
load, ~0.9 ms RTT) is not in the client's read pump or in per-stream buffering; next suspect is the two
cross-thread hops per applied unit (QUIC loop -> game loop for delivery, game loop -> QUIC loop for the ack),
paid serially by ~3800 units in a join.

### /3 chunk batch timer from network-thread busy time (2026-10-21, n=2 per arm, interleaved TCP)

Vanilla's client sizes chunk batches from how long the network thread takes per chunk. On TCP that is decode
time (~0.3-0.4 ms/chunk); on /3 the wall time from a batch's first chunk also contains transport gating between
units, so the calculator saw ~1.0-1.2 ms/chunk and the server sent ~5.6 chunks per batch instead of ~9.8. With
`-Dquicraft.v3.batchTimerBusy=true` (default off) a /3 batch is timed by the connection's busy time on the
receive path (decode plus synchronous fire, accumulated in `GameLanes`) instead of wall time. Same real-Fabric
method as above, arms interleaved tcp, /3, /3 busy per profile and round. Per-run values:

| build | profile | arm | join ms | chunk load ms | rtt median ms | disconnects |
|---|---|---|---|---|---|---|
| c6ebc4e (first chunk's decode not counted) | clean | tcp | 1418, 1552 | 869, 1032 | 0.58, 0.59 | 0 |
| | clean | /3 | 1635, 1805 | 1518, 1821 | 1.43, 1.42 | 0 |
| | clean | /3 busy | 1618, 1708 | 885, 835 | 1.37, 1.46 | 0 |
| | delayloss | tcp | 2219, 1954 | 6680, 4587 | 150.70, 151.60 | 0 |
| | delayloss | /3 | 2804, 4371 | 3245, 3840 | 151.55, 151.50 | 0 |
| | delayloss | /3 busy | 3408, 2891 | 2110, 2042 | 152.71, 152.65 | 0 |
| 3cb3d66 (first chunk's decode counted) | clean | tcp | 1290, 1739 | 815, 809 | 0.61, 0.65 | 0 |
| | clean | /3 | 1592, 1641 | 1350, 1433 | 1.47, 1.42 | 0 |
| | clean | /3 busy | 1657, 1757 | 1115, 1143 | 1.51, 1.41 | 0 |
| | delayloss | tcp | 2061, 2110 | 38562, 7298 | 150.70, 150.77 | 0 |
| | delayloss | /3 | 2859, 2787 | 3349, 3492 | 151.57, 151.79 | 0 |
| | delayloss | /3 busy | 2840, 3222 | 2908, 2023 | 151.86, 151.67 | 0 |

Every run loaded all 157 chunks. Mean batch size with the busy timer: 8.7 (c6ebc4e, clean) and 8.3 clean / 6.5
delayloss (3cb3d66); about 5.6 without it. The first build left the first chunk's decode out of each interval
(review finding), which flattered it; the corrected timer (3cb3d66) narrows the clean-link chunk-load gap to TCP
by roughly half (about 1.4 s -> 1.1 s against 0.8 s) and is the fastest arm under delayloss in both rounds. n=2:
direction, not a gate. Still off by default: the reviewer's default-on conditions are a lagging-main-thread run
(bounded backlog, no runaway batches) and a broader interleaved matrix.

### /3 inline receive pump on CONTROL, REALTIME and ACK (2026-10-21, interleaved TCP)

Ping hop trace (`-Dquicraft.v3.rttTrace=true`, clean, diagnostic): after a stream's data arrived the receive pump ran
as a queued loop task; it read the 1-byte length prefix, then issued the body grant (13.12) in a second turn. That
prefix -> body-grant hop was p50 0.19 ms on the server and 0.09 ms on the client. Running the pump at the stream's
read-complete (with a re-entrancy guard shared by the tick) brings it to 0.026 / 0.008 ms. Draining the writes a
round submitted right after it moves the server's pong submit -> drain from 0.185 to 0.067 ms. Two send-side
experiments (draining at the game's flush, flushing the QUIC connection after a drain) moved no time out of the
round trip: the datagram still leaves at the end of the loop's read batch. They were dropped.

Inline on every stream, delayloss chunk load (n=8 per arm): /3 median 3366 ms, inline 3602 ms. Capping an inline
round at 4 steps did not help (n=3, 4170 ms). The difference is not significant, but it points the same way as the
earlier pump-after-apply variant, so CHUNK streams keep the queued pump. Shipped default: inline on CONTROL,
REALTIME and ACK (`-Dquicraft.v3.inlinePump=false` restores the queued pump everywhere;
`-Dquicraft.v3.inlineChunks=true` adds the CHUNK streams). Branch `agent/p5-inline-pump-tests2`, n=3 per arm, no
tracing:

| profile | arm | join ms | chunk load ms (runs) | rtt median ms |
|---|---|---|---|---|
| clean | tcp | 1421 | 1125 (1125, 1393, 881) | 0.603 |
| clean | /3 queued pump | 1886 | 1619 (1348, 1822, 1619) | 1.461 |
| clean | /3 inline non-chunk | 1715 | 1479 (1195, 1479, 1496) | 1.342 |
| delayloss | tcp | 2818 | 33266 (33266, 37505, 9861) | 151.16 |
| delayloss | /3 queued pump | 3163 | 3797 (4596, 3797, 3413) | 151.71 |
| delayloss | /3 inline non-chunk | 2815 | 3334 (4306, 3130, 3334) | 152.46 |

The clean-link RTT gain is about 0.12 ms, consistent across every traced and untraced round (inline-everywhere
rounds: 1.27-1.45 ms against 1.41-1.60 ms). The gap to TCP is still about 0.7 ms. It is spread over the remaining
hops: client send path to the datagram (~0.3 ms), the server's quiche receive (~0.11 ms), and decode plus game delivery
(~0.2 ms). No single hop dominates.

### /3 busy batch timer: seven-profile matrix and a lagging client (2026-10-21, n=2 per arm, interleaved TCP)

Default-on gate for `-Dquicraft.v3.batchTimerBusy=true` (above). Feat d3e2c22f (inline receive pump on) plus the
gametest lag injection (b7c22bc6). Arms run interleaved tcp, /3, /3 busy per profile and round. The lagging-client
rows (one round) sleep the client main thread 40 ms per tick and 1 s every 100 ticks
(`-Dquicraft.test.clientTickLagMs=40 -Dquicraft.test.clientStallMs=1000 -Dquicraft.test.clientStallEveryTicks=100`).
Per-run values:

| profile | arm | join ms | chunk load ms | rtt median ms |
|---|---|---|---|---|
| clean | tcp | 1224, 1462 | 1018, 892 | 0.58, 0.58 |
| clean | /3 | 1731, 1812 | 1641, 1574 | 1.30, 1.26 |
| clean | /3 busy | 1637, 1909 | 938, 974 | 1.37, 1.51 |
| clean | tcp, lagging client | 1139 | 710 | 0.55 |
| clean | /3, lagging client | 1383 | 1210 | 1.20 |
| clean | /3 busy, lagging client | 1289 | 819 | 1.21 |
| loss | tcp | 1226, 1372 | 716, 1199 | 0.65, 0.61 |
| loss | /3 | 1532, 1556 | 2418, 1497 | 1.34, 1.42 |
| loss | /3 busy | 1610, 1667 | 1153, 868 | 1.37, 1.38 |
| delay | tcp | 2034, 2202 | 2226, 2120 | 150.71, 150.67 |
| delay | /3 | 2674, 2845 | 1904, 1967 | 151.43, 151.52 |
| delay | /3 busy | 2901, 3049 | 1207, 1263 | 151.60, 151.77 |
| delayloss | tcp | 2118, 2277 | 10876, 24944 | 150.63, 150.76 |
| delayloss | /3 | 3206, 3371 | 3194, 2782 | 151.55, 151.75 |
| delayloss | /3 busy | 2780, 3231 | 2537, 2209 | 152.54, 151.90 |
| delayloss | tcp, lagging client | 1942 | 24302 | 150.67 |
| delayloss | /3, lagging client | 3937 | 3034 | 151.44 |
| delayloss | /3 busy, lagging client | 3742 | 1800 | 151.35 |
| reorder | tcp | 1356, 1681 | 1285, 1165 | 20.67, 20.66 |
| reorder | /3 | 1782, 2149 | 2099, 1670 | 21.30, 21.35 |
| reorder | /3 busy | 1997, 1915 | 929, 1015 | 21.34, 21.30 |
| jitter | tcp | 1414, 1504 | 7431, 7050 | 19.74, 20.65 |
| jitter | /3 | 2040, 1956 | 1825, 1933 | 23.05, 22.09 |
| jitter | /3 busy | 1963, 2036 | 1319, 1374 | 21.09, 22.31 |
| slow | tcp | 2367, 2496 | 41246, 29653 | 151.37, 159.31 |
| slow | /3 | 3329, 2595 | 5962, 5948 | 152.55, 152.96 |
| slow | /3 busy | closed 0x12, 3571 | closed 0x12, 5529 | -, 154.73 |

The busy timer gives the fastest chunk load on every profile but slow. On clean it reaches TCP (938/974 vs 1018/892
ms). With a lagging client main thread the backlog stays at the accepted-commit bound and no batch runs away.

**It stays off.** On `slow` (2 Mbit, 75 ms, 2% loss) one of two busy runs closed with 0x12 after 16 s of play. The
batch log explains it. Busy time leaves out wire transfer time, so the client asked for 12.6 and 15.8 chunks per
tick (/3 settles near 2, TCP at 0.1-1.8). The server then queued more chunk data than the link could carry, and a
cross-stream dependency waited past the 10 s blocked-head timer (13.10/13.12). Two follow-ups:

- The batch interval must keep the wire time. Next prototype: vanilla wall time minus the measured local-gating
  waits (apply/ack hops, sequence and eligible-head waits, data buffered but not yet pumped), keeping every
  unexplained or no-local-bytes wait.
- The blocked-head timer turns deep queueing into a disconnect where TCP only lags. That is a spec question for the
  13.10/13.12 timer design.

### Blocked-head timer: progress-based deadline, and why the busy timer still stays off (2026-10-22)

13.10/13.12 changed (user-approved, security-reviewed): a dependency-blocked head now closes 0x12 after 10 s with
no qualified progress (a validated commit on an unresolved prerequisite's stream: the head's own direct dependency
reaching its required sequence, or 64 KiB of qualifying wire bytes), not after a flat 10 s, with a nonrenewable 120 s
active cap (180 s retention with the 60 s stall allowance). The numbers are proposed defaults.

Slow profile (2 Mbit, 75 ms, 2% loss), busy batch timer (the prototype that floods: 8-13 chunks/tick), chunk load ms,
interleaved TCP and plain /3 (`diag/pt1`, `pt2`, `pt3` in the job scratch; not committed):

| Arm | Before (587ea286, n=2) | After, n=3 | After, second matrix, n=2 |
|---|---|---|---|
| /3 busy | closed 0x12, closed 0x12 | 5535, 5327, 5566 | 5443, closed 0x12 |
| /3 plain | 5962, 5948 | 6572, 5946, 6014 | 6459, 5692 |
| TCP | 41246, 29653 | 36355, 32221, 37791 | 32041, 25728 |

The busy timer is not safe to turn on: one of eight post-change flooded runs without a lagging client still closed
0x12 (pt2; no server log was collected for it, so its cause is not established; pt3 added 3 more clean runs at 5479, 5514
and 5493 ms), and with a lagging client (40 ms/tick + 1 s stall per 100 ticks) 14 of 14 closed (pt2 to pt7; pt4 to pt7 only add diagnostics).

Root cause of the lagging-client closes (server log, `-Dquicraft.v3.trace=true`): the SERVER drops the connection
about 3 s after join with `send close: check=queue-packet-limit queuedRecords=1024 queuedBytes=299113
queuePacketLimit=1024 queueByteLimit=16777216`. Mostly small REALTIME records (peak 915 queued) plus a few chunks
reach the 1024-record cap long before the 16 MiB byte cap, while a flooded 2 Mbit link drains about 250 KB/s. The
client then sees a silent connection and its own 10 s no-progress deadline fires, so that 0x12 was a symptom. A second
run hit the sibling bound, `the outbound hold is over its cap (13.12)`. The record-count cap exists because byte
accounting does not see per-record object overhead; raising it needs the overhead charged. Open design item, not
changed here. TCP in the same situation only buffers.

Also measured, negative: the local-gating batch timer (`-Dquicraft.v3.batchTimerGating`, branch
`agent/p5-gating-timer`, not merged). With only locally caused waits subtracted it matches plain /3 within noise on
clean, delayloss, slow and slow with a lagging client (n=2; chunks/tick on slow 1.9-2.3 vs 2.0-2.1 for plain /3). The
earlier gains came from over-subtraction (7-13 chunks/tick), which floods slow links. Details in
`.agents/plans/result-p5-gating-timer.md`.

New diagnostics, all off the wire: the local close detail (`receive driver closed with 0x12 (expiry=... head=...
unresolved=[... held= ready= enq=...] ...)`), `QUICraft v3 send close: check=...` (once per connection, INFO),
`QUICraft v3 profile: ...` (once per process), and `-Dquicraft.v3.stalldiag=true` (stall watchdog, off by default).

### Outbound queue footprint accounting: the slow-link disconnect is gone (2026-10-22, n=3 per arm, interleaved TCP)

Spec 13.20 and `SessionSendDriver` changed (user-approved, peer- and security-reviewed). A queued record now costs
`2 * encodedBytes + 1024` against a 16 MiB connection allowance and the node/IP/account pools charge the same
footprint; the record-count cap is 16384 instead of 1024. At 8 MiB of footprint a connection reports pressure and the
server stops starting new chunk batches (`PlayerChunkSender.sendNextChunks` mixin) until it drains to 4 MiB; a
connection under pressure closes 0x12 only after 30 s with no outbound bytes released by the transport. A bundle run
whose footprint exceeds one maximum record's is refused whole (0x12).

Slow profile (2 Mbit, 75 ms, 2% loss), commit cbdb8f95 (the bundle refusal in e5df0fc8 came after this run), chunk load ms, three rounds interleaved
(`diag/qc1` in the job scratch, not committed). Lagging client = 40 ms per tick plus a 1 s stall per 100 ticks:

| Arm | Chunk load ms | Disconnected |
|---|---|---|
| TCP | 33901, 32438, 32448 | 0 of 3 |
| TCP, lagging client | 33687, 30976, 31423 | 0 of 3 |
| /3 plain | 5905, 5904, 5992 | 0 of 3 |
| /3 busy batch timer | 5545, 5486, 5676 | 0 of 3 |
| /3 busy batch timer, lagging client | 5573, 5534, 5543 | 0 of 3 |

Before the change the busy timer with a lagging client closed 14 of 14 runs (the 1024-record cap, see the previous
section). The server logged no `send close` and no chunk pause in these runs: the queue never reached the 8 MiB
pressure mark. What removed the disconnects here is the record cap and the footprint accounting; the chunk pause and
the 30 s release deadline are covered by unit tests only, not yet exercised under load. On this link the busy timer is
about 6% faster than plain /3 (5.5 s against 5.9 s); /3 is about 5.6 times faster than TCP.

Costs and open items: the pools now charge footprint, which roughly halves how many encoded bytes the provisional
256 MiB node and 32 MiB IP/account pools hold; the constants (2x, 1024, 8/4 MiB, 30 s) are provisional and retained
heap per record is not measured. A vanilla bundle whose encoded size exceeds about 2.03 MB (126 members) now closes
0x12 instead of 2.10 MB before. A peer that keeps releasing a trickle of bytes keeps its backlog pinned below the hard
caps; that is bounded by the caps and pool shedding. The busy timer stays off by default until the seven-profile matrix
passes on this build.

## /3 seven-profile matrix with the busy batch timer, and the clean-link join gap (2026-10-22)

Real Fabric client against the external Fabric server, loopback with netem, idle client (no walking), commit
`c944278a`, n=2 per cell, runs interleaved. Chunk-load medians in ms; "busy" is `-Dquicraft.v3.batchTimerBusy=true`.

| Profile | TCP | /3 plain | /3 busy |
| --- | --- | --- | --- |
| clean | 939 | 1393 | 1149 |
| loss | 979 | 1783 | 1074 |
| delay | 2149 | 2290 | 1381 |
| delayloss | 30840 | 3539 | 2180 |
| jitter | 7793 | 1980 | 1261 |
| reorder | 2658 | 1831 | 1288 |
| slow | 36014 | 5989 | 5715 |

0 disconnects in every cell. n=2 means the ranges overlap on the clean and loss rows; only the large gaps (delayloss,
jitter, slow) are conclusive. Play RTT on the clean link: TCP 0.60 ms, /3 about 1.4 ms. On 2026-10-23 the maintainer
approved making the busy timer the default (`-Dquicraft.v3.batchTimerBusy=false` restores the wall-time timer); the
`plain` benchmark variant now sets it to false explicitly.

### Clean-link join: where the extra time goes

`-Dquicraft.v3.joindiag=true` records off-wire phase marks (one INFO line per connection per side). Clean loopback,
n=3, after the configuration wake (the server thread is woken through `MinecraftServer.execute` when the hold is
released, so configuration no longer waits for the next tick): TCP 1342 ms, /3 1506 ms (+164 ms; the same comparison
before the wake was +480 ms in the matrix above). Per phase, /3 costs about +85 ms from connect to login and about
+115 ms from login to the first configuration packet; configuration to play is about equal. The vanilla spawn
preparation task (about 330-400 ms between the tags build and Finish Configuration) is paid by TCP too.

### QUIC client warm-up (branch `agent/p5-warmup`, not merged)

A daemon thread at client start builds a throwaway TLS context and engine so the natives, event loop and TLS stack
are initialised before the first connect (`-Dquicraft.warmup=false` disables it). Clean link, n=5 per arm, interleaved
(display mode of this run was not recorded, so absolute values are not comparable with the headless rows in the next sections):

| Arm | join ms median (min-max) | chunk load ms median (min-max) | RTT median ms |
| --- | --- | --- | --- |
| TCP | 1331 (1217-1551) | 857 (665-1442) | 0.59 |
| /3 warm-up off | 1603 (1536-2078) | 918 (764-1192) | 1.42 |
| /3 warm-up on | 1541 (1477-1803) | 758 (752-1163) | 1.35 |

The warm-up saves about 60 ms of join, inside the run-to-run spread; /3 is still about 210 ms behind TCP on join and
more than twice TCP's RTT. No claim beyond that.

The warm-up was peer-reviewed (BLOCK: it builds TLS state before discovery, beyond the approved native and event-loop
warm-up; launch failures escape the silent boundary; the tests do not show it does anything) and is not merged.

## /3 moving-player matrix: TCP vs /3 plain vs /3 busy, seven profiles (2026-10-22, n=3 rounds)

Real Fabric client headless under Xvfb, external Fabric server, client sprinting for 60 s per run, arms interleaved
per profile and round (tcp, plain, busy), commit `938cea65` build, 63 runs planned. Rows are in
`testkit/results/moving-proof-1` (not committed); the manifest gate reads INCOMPLETE because 6 runs failed.

Failed runs: 6 of 63 (1 TCP, 5 /3), all "the moving window proved no movement" (6.9-22.8 m moved in 60 s instead of
about 220 m, 0 or 26 chunks). Each had 1200/1200 client ticks, 0 stalls and 0 disconnects, so the player was blocked
by terrain on its fixed walk, not by the network. They are excluded from the medians, which leaves n=2 in those cells;
the gate stays INCOMPLETE until they are rerun or the walk path avoids obstacles. The 1-versus-5 split is not
statistically distinguishable at these counts.

Movement metrics of the successful runs (medians; chunks/s range in parentheses): every cell shows 0 stall ticks.

| Profile | TCP chunks/s | /3 plain chunks/s | /3 busy chunks/s |
| --- | --- | --- | --- |
| clean | 3.7 (3.5-3.9) | 2.4 (0.6-4.1) | 4.1 (3.9-4.1) |
| loss | 3.9 (3.7-3.9) | 4.1 (3.7-4.5) | 2.3 (0.7-3.9) |
| delay | 4.0 (3.9-4.1) | 3.8 (3.7-4.1) | 3.7 (3.7-5.5) |
| delayloss | 3.6 (3.5-3.7) | 4.1 (3.9-4.5) | 3.8 (3.5-4.1) |
| reorder | 3.9 (3.9-4.1) | 5.4 (5.1-5.7) | 3.7 (3.7-4.1) |
| jitter | 4.0 (3.6-5.7) | 4.1 (3.9-4.1) | 4.1 (3.9-5.6) |
| slow | 3.2 (3.2-3.3) | 4.0 (3.8-4.1) | 3.9 (3.9-5.8) |

The sprint, not the network, sets the chunk rate on most profiles, so the rates agree within the walk's own variation
(the low minima in the clean-plain and loss-busy cells come from runs where the walk was partly blocked but still
passed the movement threshold). In the moving window "chunk load" is dominated by the 60 s walk and is not a
meaningful comparison except where TCP is slower than the window: slow, TCP 95.0 s against /3 plain 66.4 s and busy
65.8 s; delayloss, TCP 72.4 s against 64.4 s and 61.9 s. Play RTT: clean TCP 0.5 ms against /3 1.2 ms; loss 0.6 against
1.1; delay 150.6 against 151.3; delayloss 156.9 against 151.6; slow 162.0 against 154.4 (plain) and 155.3 (busy).
Join: /3 was slower than TCP in every profile (clean 2.63/2.65 s against 2.10 s; delay 3.37/3.35 against 2.43;
delayloss 3.52/3.25 against 2.64; slow 2.89/3.47 against 2.51).

## No-desync PlaySession under every netem profile (2026-10-22, 1 round)

`testkit/run-desync-sessions.sh`: one full PlaySession (about 22 s) per cell in a fresh client JVM with the in-process
dedicated server, inside a private user and network namespace whose loopback carries the profile. Rows record the
negotiated `quicraft/3`, 5 streams and routing table `quicraft-routing-p775-s2` for /3.

| Profile | TCP | /3 plain | /3 busy |
| --- | --- | --- | --- |
| clean, loss, delay, delayloss, reorder, jitter, slow | pass | pass | pass |

21 of 21 runs passed, 0 disconnects, 0 mismatches (commit `e6e2ea20`). One round is a smoke-level proof; more rounds
and longer sessions are still open.

## Clean-link push: warm-up, teleport-hop chunk fill and chunk pacing experiments (2026-10-23)

Goal: /3 faster than TCP on a clean link for join, idle chunk load, chunk fill while moving, and RTT. **Not reached.**
Every number below is a paired per-round difference against TCP from `testkit/run-hop-matrix.sh` (fresh server container
and client JVM per cell, headless Xvfb, TCP / /3 plain / /3 busy interleaved per round, clean profile, 0 disconnects,
0 stall ticks in every cell).

Hop mode: after the initial view fill the server teleports the player (`tp @s`, 240 m, ping-pong, six legs per run);
a leg's fill time runs from its first tick until 90% of the columns of the circle around the new position are loaded
(render distance 5, 73 of 81 columns).

**Baseline on `feat` with warm-up v2 (n=10 rounds, `hop-feat-warm-10`)**

| Metric | TCP median | /3 plain | /3 busy | busy paired delta (min..max) |
| --- | --- | --- | --- | --- |
| join (ms) | 1316 | 1470 | 1451 | +121 (-361..+688), +9.6% |
| idle chunk load (ms) | 4825 | 7352 | 5202 | +385 (+53..+989), +8.0% |
| hop fill median (ms) | 325 | 598 | 351 | +30 (-22..+66), +9.2% |
| hop fill p95 (ms) | 506 | 841 | 514 | +32 (-55..+107), +6.3% |
| play RTT median (ms) | 1.2 | 2.3 | 9.9 | +7.3 (-3.4..+18.9) |

Join before warm-up v2 (n=5, `hop-clean-1`): TCP 1283, busy 1574 (+301). Warm-up v2 is a loopback self-handshake on a
daemon thread at client start (no real-server contact, early data and resumption off, one 2 s deadline, reviewed and
cleared); it cuts roughly 150 ms of the join gap. The remaining gap is client cold start plus costs TCP pays too.

**Chunk pacing experiments (hop fill, busy arm, paired median vs TCP, n=5 each)**

| Build | Policy | Delta |
| --- | --- | --- |
| `470f7f42` | busy timer counts every lane's decode and handoff | +43 ms |
| `41c8ce31` | counts full-chunk decode and handoff only, in delivery order | +33.5 ms |
| `c796efd6` | main-thread apply cost with a guard | +28.6 ms (no gain) |
| `8aa1b842` | plus equal-weight adaptation, cap 32 chunks/tick | **+104 ms (worse)**, reverted |

Raising the requested chunk rate does not help: batches are serial (the next one waits for the client's batch ACK and
the next server tick), a bigger batch takes longer to deliver (62-155 ms against 34-37 ms for TCP at 25-30 chunks),
and the client applies the chunks of two hops at once. The 8aa1b842 policy also made busy RTT worse.

Per-batch delivery (server batch submit to client batch finish): TCP 20-25 ms median, /3 busy about 30 ms with a heavy
tail. In the traced example the server IO thread sends two chunk frames 3 ms after the submit, is then occupied for about
12 ms (untraced work, most likely vanilla encoding and compression of the following packets), and sends the rest at
17-22 ms. That serial per-batch overhead of about 5 ms times 6-7 batches per leg is the 30 ms hop gap.

Honest summary: /3 busy is about 6-10% slower than TCP on a clean loopback link for join, idle chunk load and hop fill;
its play RTT has a long tail while chunks arrive. Plain /3 is far behind on chunk load (the busy timer is needed and is
the default). No claim of a clean-link win is made.
