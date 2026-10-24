<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Busy timer RTT investigation

## Evidence and hypotheses

`ExternalArmGameTest.Pacer.tick` sends `ServerboundPingRequestPacket` from the client thread,
embedding its local `nanoTime`. `ConnectionTimingMixin.channelRead0` records the pong at HEAD
on the network thread. Client main-thread application of the **pong itself** is not included.
Game source 26.1.2 `ServerGamePacketListenerImpl.handlePingRequest` immediately sends the pong,
without `ensureRunningOnSameThread`: this request does not require a server tick to answer.

The reviewed protocol-775 routes are request 38 = CONTROL/global, response 62 = CONTROL/ordinary.
Neither uses the REALTIME reservation. Increasing REALTIME priority cannot fix this measurement.
`BatchJoinTracker.previousJoinDependency` makes the next batch start depend on the previous join;
an ordinary CONTROL pong behind that start inherits its delay through CONTROL FIFO. A larger
busy-derived batch can therefore delay a pong even without an explicit pong-to-CHUNK dependency.
`ordinaryControlPongWaitsBehindBatchGateEvenWithRealtimeBudget` reproduces this scheduling constraint.
At the client, `SessionReceiveDriver.drive` also allows only one executing unit, so application of
an earlier chunk can delay decoding/delivery of a pong. Neither mechanism is yet established as
the dominant cause of the measured multi-second RTT.

The supplied busy slow client log (line 203) reports median RTT 2280.52 ms, p95 4200.11 ms,
but zero stalled ticks out of 592 and maximum tick 109.4 ms. This argues against a multi-second
client tick stall, without excluding network-thread execution gating behind chunk application.

The supplied slow pathdiag data shows low path srtt and zero sampled Netty inFlight.
This does **not** rule out transport backlog: `NettyQuicByteStream` decrements inFlight on the
Netty write future, not a peer ACK. Path srtt measures acknowledged datagrams, not time spent
waiting before transmission or application delivery. Do not select a BDP limit from that counter.

## Next measurement (orchestrator runs it)

Run the same ExternalArm **hop** workload as `hop-pathdiag-slow`, n=1 paired plain/busy on **slow**,
with both client and server JVMs setting `-Dquicraft.v3.latencydiag=true` and
`-Dquicraft.v3.pathdiag=true`. Set client `-Dquicraft.v3.batchTimerBusy=false` for plain and `true`
for busy; leave every other transport, timer and netem setting unchanged. Preserve both logs and
the `playRtt`/hop fill rows. These diagnostic runs are not performance acceptance runs.

`latencydiag` is off by default. It logs metadata only, with no retained per-record trace state:

* `tx-route packet=62 stream=0 seq=S`: identifies the pong's sender record. Owner is the send driver.
  Request is packet 38. Only ordinary sends have this route mapping, not bundles or special markers.
* `tx-enqueued`, `tx-granted`, `tx-selected`, `tx-last-selected`: same owner/stream/seq. Differences
  separate pool/grant wait from scheduler wait. `deps=i:j` identifies each prerequisite.
  Selection is not transmission or acknowledgement.
* `rx-admitted`, `rx-enqueued`, `rx-applied`: same receiving owner/stream/seq. Differences separate
  dependency/execution wait from application acknowledgement. Admission is after the read pump,
  **not** wire arrival; a long pre-admission delay remains ambiguous between read gating and transport.
* Match sender and receiver by direction, stream and sequence, not owner. Compare intervals within
  one process only; do not assume cross-JVM nanoTime origins are equal.

Prediction: busy pongs have >=1,000 ms sender enqueue-to-selection or client admission-to-enqueue
wait on slow, versus substantially shorter plain waits. For sender HOL, walk preceding CONTROL
records to the batch-start dependency, then the CHUNK join/install selections. For receiver wait,
check the intervening executing CHUNK's enqueue-to-applied interval. If both pong waits remain
<100 ms while playRtt remains >2,000 ms, the proposed above-quiche queue attribution is falsified
for those stages: inspect read gating and quiche internal backlog next, not REALTIME priority.

No speculative pacing fix is enabled here. Bypassing CONTROL FIFO or removing the batch-start join
would violate current ordering. A delivery-rate-based producer bound needs the distinguishing run
above before choosing a measurement source and limit. After a fix, run n=5 paired TCP/plain/busy
on slow, delayloss, jitter and clean with diagnostics **off**. Required RTT is <=1.25*TCP; retain
>=60% of the original busy chunk-load advantage using the same metric and no clean regression.

## Local validation

The requested seven-task Gradle gate passed on this diagnostic patch: core 1620 tests, each
bridge 140, each Fabric module 221 (2342 total reported tests), and both client compilations.
Tests cover trace enabled/disabled behavior and the CONTROL FIFO reproducer. No benchmark
or acceptance claim is made; no pacing, ordering, budgeting or TCP code was changed.
