# Delay-profile batch aggregation experiment

Status: opt-in, performance unverified. No benchmark was run by the patch author.
This is not a new timer estimator and does not enable busy timing.

Verification: the requested six-task Gradle gate passed. Reports contain core
1617, bridge-netty42 140, and Fabric 221 tests per build, all with zero failures,
errors or skips; both client compilations passed. Six new unit tests cover
cork-off/on identical stream bytes, CHUNK-only holding, independent REALTIME/ACK,
deadline renewal/expiry, packet/size/pressure bounds and grant-wait progress.
These test aggregation policy, not performance; no red/green latency proof is
claimed. Model-guard preflight was attempted but the shell permission layer
rejected that command; no subagents were dispatched.

## Evidence and competing mechanisms

The supplied 2026-10-23 delay trace has lower client apply cost on /3, but its
desired rate ramps to only about 8 chunks/tick at leg 5 versus TCP's 12.75.
That accounts for extra 50 ms producer ticks without a per-batch application
round trip. `PlayerChunkSenderMixin` only pauses under outbound pressure;
`V3Join` attaches that predicate to `NStreamSession.outboundPressure()`.
Nothing in those hooks adds a feedback round trip when pressure is absent.

There is a concrete fragmentation opportunity: `V3Join.frameUnit` compresses
each unit synchronously, `NStreamSession.afterSubmit` requests an immediate
queued drain, and `QuicStreamWriteSink.writeCombined` calls writeAndFlush per
stream per drain. Coalescing ends at each drain, not at the vanilla batch
boundary. The supplied plain trace's hop-leg medians show 5-8 compression
calls taking roughly 6-7 ms per batch and 5-10 drain turns per batch.
`GameLanes.deliver` drains inline on the game loop, otherwise queues one drain;
it has no intentional inter-chunk delay. The plain timer restarts at the first
chunk, so server compression/drain work after that chunk can still enter the
wall-time sample. Earlier submit/drain fusion does not disprove this: earlier
emission does not aggregate the cohort and may expose more of its encoding span.

Competing mechanism: BBR/native pacing or cwnd gating can spread a cohort after
handoff. `Codecs.apply` applies configured initial cwnd (default 64 packets)
and BBR, with 2 MiB clientbound connection credit and 256 KiB stream windows
(the constants, not the stale 16 MiB comment in `Codecs`, determine the credit).
It does not override ACK delay or native pacing. Typical traced batch compressed
sizes (roughly 33-50 KiB in later legs) fit those initial windows, but concurrent
traffic and native inflight state are unknown. The supplied logs contain coarse
batch-window profiles, not exact datagram-to-record or cwnd-stall attribution.
They do not prove this second mechanism absent, nor prove the first dominant.

## Patch and bounds

Server property `-Dquicraft.v3.chunkBatchCork=true` enables CHUNK-only aggregation
between vanilla start/finish writes in active /3 connections. Default is false
until measured. Records stay in the existing charged driver queues: no extra
payload-holding queue, sequencing change, dependency change or new memory cap.
CONTROL, REALTIME and ACK remain independently eligible under the existing
scheduler. All original quanta, stream FIFOs, footprint and pool accounting
remain in force, including 13.20 start/install/finish ordering.

The cork opens on finish, 10 ms expiry, existing pressure, a waiting pool grant,
half the packet limit, or queued footprint reaching that of one 64 KiB record.
The size check occurs at poll, so an individual admitted record can cross the
threshold; existing hard admission caps still apply. A scheduled drain wakes
the idle sender at expiry. Bounds deliberately abandon aggregation on large
or slow batches rather than extending pressure. No handshake/lane-setup or TCP
path is changed. Corking may delay the first chunk without improving the last;
do not ship it based solely on improved feedback numbers.

## Orchestrator measurement

Use the same workload, paired ordering, server settings and delay netem profile
as the supplied n=10 baseline. Compare TCP, /3 plain cork-off, and /3 plain
cork-on, ten untraced runs each. Both /3 clients must explicitly use
`-Dquicraft.v3.batchTimerBusy=false`; only the experimental server needs
`-Dquicraft.v3.chunkBatchCork=true`. Rebuild/install both platform jars normally.

Measure idle chunk load, hop-fill median/p95, RTT during hops and disconnects.
One additional traced run per /3 arm should compare first-to-last chunk wall
spacing, desired rate per leg and batch count, not only aggregate drain count
(empty/CONTROL drains still occur while CHUNK is corked).

Mechanism target: the cited 0.5 ms/chunk arrival spacing falls to <=0.25 ms;
later-leg desired rate reaches >=11 and batches/leg approach TCP. If spacing
does not fall by at least 0.2 ms/chunk, encoding/drain fragmentation is not the
dominant cause and this patch should remain disabled. If spacing improves but
untraced delivery does not, reject a feedback-only victory. Performance target:
paired median hop penalty <=50 ms (formerly +224 ms), idle penalty <=5% of TCP
(roughly 410 ms at the supplied baseline), with no RTT/p95/disconnect regression.
These are acceptance thresholds, not achieved results. Loss/constrained-link
validation is additionally required before any default change: network stalls
still enter the unchanged wall timer, but send bursting can affect REALTIME.
