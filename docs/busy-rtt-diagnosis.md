<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Busy timer RTT investigation

## Superior follow-up: supplied trace disproves the batch-only diagnosis

**No fix is enabled by this follow-up.** Two bounded candidate experiments show why changing the
batch-start gate or routing the pong onto existing REALTIME is insufficient. These are
characterization tests, not a fail/fix/pass claim. No new benchmark was run.

The supplied `testkit/results/hop-latdiag-slow/quic3-slow-hop-busy-run0.server.log`
contains 119 selected packet-62 pongs; **all 119 have an explicit ACK-stream dependency** in
`tx-granted`, in addition to implicit CONTROL FIFO. N=3 here, so ACK has logical index 5.
The worst pong is CONTROL `0:2509`, granted at `1201192736499260`, selected at
`1201195947046184`: **3210.547 ms**. Its chain is:

| Record | `tx-granted` dependencies | Evidence (server-log lines) |
| --- | --- | --- |
| Earlier CONTROL bundle opener `0:2503`, kind 4 | `1:9570,2:578,3:416,4:510,5:58` | 68447, selected 71062 |
| Packet 99 CONTROL `0:2507` | `1:9571,2:578,3:416,4:510,5:58` | 68455, selected 71072 |
| Packet 12 batch start CONTROL `0:2508` | `1:9571,2:578,3:416,4:510,5:58` | 68458, selected 71074 |
| Current batch finish ACK `5:59` | `0:2508,1:9571,2:585,3:421,4:513` | enqueued 68505, selected 71183 |
| Packet 62 pong CONTROL `0:2509` | **`5:59`** | 68511, selected 71185 |

Thus the batch start is not the sole blocker. Even after its CONTROL predecessor is selected,
the pong still waits another **359.198 ms** for ACK `5:59`, which trails the current batch's
installs. Of the 119 pongs, 36 have no earlier granted-but-unselected CONTROL record when
they are granted; all still carry the ACK dependency. For the others, the earliest outstanding
CONTROL record is a bundle opener (27), another pong (21), batch start (19), packet 48 (6),
packet 99 (6), packet 35 (2), packet 113 (1), or packet 72 (1).

The production path is `SendRouteResolver.resolve` / schema-2 routing, not `beginBatch`:
the batch start has a `tx-route` event, which `submitRouted` emits but `beginBatch` does not.
There are no platform callers of `beginBatch`/`batchInstall`/`finishBatch`. Those are currently
adapter APIs exercised by tests. The shipped table routes packet 12 as CONTROL/view and
packet 11 as ACK/trailing, not BATCH_JOIN. Its view scope waits for every earlier stream
(`StreamSequencer.scopeDependencies`, lines 444-451). Ordinary CONTROL **and REALTIME** both
call `afterLastAck` (lines 457-473), as required by protocol 13.9/13.21. A view gate also becomes
C (lines 497-499), so a pong moved to REALTIME still inherits both gates. The scheduler's FIFO
and explicit-prerequisite rules (13.20, `SendScheduler` lines 21-24) correctly enforce these
dependencies; changing weights cannot remove them. The one-in-flight write per stream does
not create an escape from either ordering edge.

Candidate tests added to `SendSchedulerTest`:

* `movingPongToRealtimeStillWaitsForViewGateAndCurrentBatchAck`: builds real sequencer
  dependencies; REALTIME is still blocked, including after CONTROL routing is avoided.
* `removingBatchStartDoesNotRemoveOrdinaryPongsExplicitAckGate`: omits the start entirely;
  an ordinary pong still waits for the current batch finish and its chunk prerequisite.

A correct liveness bypass must escape **both** all gameplay frontiers (including ACK) and FIFO
behind records gated by those frontiers. Simply exempting the pong from `afterLastAck` on
CONTROL does not escape its CONTROL predecessor. Simply adding an independent dependency
scope on REALTIME does not escape earlier gated REALTIME records. A dedicated bounded,
reliable liveness stream is the clearest next design, requiring peer routing/validation,
protected read and footprint accounting, stream negotiation and limits, and routing digest /
binding updates. It must retain existing REALTIME's queue/budget and all gameplay ordering.
That is a larger wire change needing maintainer sign-off, not a scheduler-only patch.

The requested seven-task gate passed on this follow-up: core 1622 tests, each bridge 140,
each Fabric module 221 (2344 total, zero failures/errors), and both client compilations.
Only core tests reran; the other six tasks were UP-TO-DATE. Production code and wire bytes
are unchanged. Model-guard preflight was attempted but rejected by the restricted shell;
no subagents were dispatched.

**Next measurement after an actual fix:** first repeat the supplied n=1 plain/busy slow hop with
latencydiag enabled. Packet 62 server `tx-granted` to `tx-selected` median and p90 should both
be <50 ms (previous median 1005 ms, p90 2685 ms). Inspect its explicit dependencies as well as
its preceding stream records. Then diagnostics off, n=5 paired TCP/plain/busy on slow,
delayloss, jitter and clean: RTT <=1.25*TCP; retain >=60% of the original busy chunk-time
advantage, i.e. `(TCP - newBusy)/(TCP - oldBusy) >= .60` on the same metric; no plain/clean
regression and zero disconnects. No performance acceptance claim is made here.

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
