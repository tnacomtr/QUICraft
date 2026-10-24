# Handoff 2026-10-24: /3 plain default, cork, and the open clean/loss gap

Branch `feat/phase5-multistream`, worktree `.worktrees/phase5-integration`, HEAD `b165b36a`, pushed to `origin` and `github`.
Read this first, then `docs/benchmarks.md` (last four sections) and `.agents/HANDOFF.md` (older state).

## 1. What shipped this session (all on feat/phase5-multistream)

| Commit | What |
| --- | --- |
| `9e6df973` | Server chunk-batch cork (Sol): holds CHUNK records between vanilla batch start and finished, hands the batch to the scheduler together. |
| `62eab39e` | **/3 defaults to the plain (wall-time) batch timer**; busy is opt-in `-Dquicraft.v3.batchTimerBusy=true`. Cork now **on by default** (`-Dquicraft.v3.chunkBatchCork=false` turns it off). Tests, gametests and docs updated. |
| `f06176d1`, `b386c6ab` (merged `f635d06d`) | Opt-in `-Dquicraft.v3.latencydiag=true` (core `LaneLatencyTrace`): per-record tx/rx stage timestamps. Docs: `docs/busy-rtt-diagnosis.md`. |
| `f2f11e51` | Opt-in `-Dquicraft.v3.pathdiag=true`: 250 ms sampler of quiche path stats. |
| `f117314c` | `QUICRAFT_HOP_NO_BUSY=1` makes `testkit/run-hop-matrix.sh` run only tcp and quic3 plain (screening). The summary then lacks busy cells and the script exits 2; the JSON and the matrix-summary are still usable. |
| `b35006da` | `-Dquicraft.v3.preferredShards=1..5` (server JVM flag): preferred CHUNK shard count, default 3, hard cap 5 (protocol). |
| `b165b36a` | F3 line shows the real stream count, e.g. "QUIC /3 (6 streams, 3 chunk)". It used to print a hardcoded "(3 streams)". |

Demo jars (not in git): `/home/tna/QUICraft-demo/quicraft-fabric-26x-shards2.jar` (built from `b165b36a`; the same jar goes on server and client).
`/3` only works on **26.1 to 26.1.2 (protocol 775)**: the only shipped routing table is `protocol-775.json`. A protocol 777 (26.2) client with `quic-only` fails with "client has no routing table for protocol 777". Adding a `protocol-777.json` table is not started (needs packet-id diff from 26.2, ordering tests, short benchmark). Server needs `multistream=true` in `config/quicraft/server.properties`. Online mode is required for /3 (channel binding) unless both ends set `-Dquicraft.multistream.offlineTest=true`. Online-mode /3 against a real Mojang session was not exercised; the testkit uses a mock session server.

## 2. Measured results (n=5 paired vs TCP unless noted)

Plain /3 with cork on, median paired delta vs TCP (negative = faster than TCP):

| Profile | Idle chunk load | Hop fill (teleport) | RTT delta |
| --- | --- | --- | --- |
| clean | +16.5% | +39.7% | +1.7 ms |
| loss | +20.9% | +32.0% | +1.1 ms |
| delay | -3.7% | +7.4% | +2.7 ms |
| reorder | -36.0% | -56.9% | +2.1 ms |
| jitter | -71.7% | -76.2% | +1.1 ms |
| delayloss | -88.8% | -90.1% | +72 ms |
| slow | -80.2% | -82.9% | +149 ms |

Busy /3: clean +11.0% / +10.8%, loss +1.2% / +0.9%, but RTT +2,157 ms on slow and +688 ms on delayloss. Join is 100 to 870 ms slower than TCP on every profile.
Cork effect, delay profile (the only same-session before/after pair): idle +14.7% to -3.7%, hop fill +30.7% to +7.4%. Loss and clean "before" numbers came from a different session (indicative only).
**The goal "/3 beats TCP on clean" is unmet.** Goal text: user, 2026-10-23.

## 3. Root causes found

1. **Busy RTT inflation = server CONTROL head-of-line.** On slow, the clientbound pong (packet 62, CONTROL stream) waits in the server sender between granted and selected: busy median 1,005 ms (p90 2,685), plain 0.1 ms. The pong is gated by CONTROL FIFO plus explicit ACK dependencies (`StreamSequencer.java:457-473`, `SendScheduler.java:21-24`). Routing it to REALTIME does not help (keeps the gates). A fix needs an independently bounded **liveness stream** that escapes gameplay frontiers: a wire/accounting change that needs maintainer sign-off and a security review. Not built. Details: `docs/busy-rtt-diagnosis.md`.
2. **Plain's clean/loss gap = smaller client-requested batches.** Client `desiredChunksPerTick`: clean TCP 16.7, plain 9.4, busy 15.4; loss TCP 14.9, plain 6.7, busy 16.0. Vanilla feedback is 7 ms / avg ms per chunk, so plain's wall-time timer asks for batches 40 to 55% smaller than TCP's.
3. **Where the time comes from (traced clean plain run):** client is not the bottleneck (bytes available to ready 0.06 ms, decode 0.07 ms, fire 0.02 ms). Chunk bytes reach the client spread over a batch: first-to-last arrival median 4.1 ms for about 5 chunks (p90 42.9 ms). With the cork on, the server scheduler selects a batch's records within a median 0.28 ms, so the spread appears **after the send scheduler**: Netty QUIC write/flush, quiche send, ACK clocking or client read cadence. Server path srtt on clean is about 5 ms vs TCP play RTT 1.6 ms. Data: `testkit/results/hop-gaptrace2-clean` in worktree `p5-gap`.

## 4. Rejected experiments (measured, do not repeat)

On clean, requested chunks per tick stayed about 9 in all of these:

| Candidate | Result |
| --- | --- |
| `inlineApplied` (agent/p5-plain-loss 6b523abf) | no effect on loss |
| `quicraft.v3.maxAckDelayMs` (agent/p5-plain-loss 2dcac40d) | no effect |
| `batchTimerRobust` (agent/p5-timer 247e1368) | idle +20.7%, hop +28.4%, desired 9.4 |
| `harvestInlineReads` (agent/p5-gap 739a43c2) | idle +23.0%, hop +46.6%, desired 9.3 |
| `eagerSendDrain` (agent/p5-gap c0658dca) | much worse: idle +75.6%, hop +137.7% (it bypasses the cork) |
| `initialCwndPackets=256` | idle +20.1%, hop +21.7%, desired 8.7 |
| `congestionControl=CUBIC` | idle +24.3%, hop +41.5%, desired 9.7 |

Earlier rejections (see benchmarks.md): submit-drain fusion, early binding, the 8aa1b842 pacing variant, server pacing floors/multipliers (rate floors caused 3/6 disconnects). All candidate flags are default off and their branches are unmerged.

## 5. Recommended next steps (in order)

1. **Per-record transport timestamps** at the Netty write, Netty flush, quiche send and client quiche receive, to locate the 4 ms arrival spread (it is after the send scheduler). Build on `LaneLatencyTrace`. Run clean plain n=1 with latencydiag + pathdiag on both JVMs (the runner keeps the server log only when `-Dquicraft.v3.trace=true` or `pathdiag=true` is in `QUICRAFT_JAVA_OPTS`).
2. Decide the **liveness-stream wire change** (needs the user's sign-off and a security review). If it works, busy would match TCP on loss and clean without the ping cost, and probably retire the plain gap.
3. **Protocol 777 routing table** if the demo or release needs 26.2.
4. **Fresh-chunk (ungenerated terrain) benchmark.** All numbers are for pregenerated worlds; new-chunk loading is dominated by server generation and is unmeasured.
5. Join slowness (+100 to +870 ms), a round-trip-count problem in handshake and lane setup (separate track, branch agent/p5-joinfast).
6. Real-world check: user tested NL -> Ankara VPN -> Dusseldorf on bad Wi-Fi and reports /3 "amazing". Their upstream router drops UDP on port 25565; advised setting `port` in `config/quicraft/server.properties` to another UDP port (443 suggested). Untested; the user has not reported back.
7. Open items from earlier: 0x11 close under a stalled IO loop, handshake redesign (sign-off and security review), moving-matrix failed cells, pre-existing failing `testkit/tests/test_external_arms.py`, worktree cleanup (about 140 worktrees under `.worktrees/`, many stale).

## 6. How to run things

- Hop matrix (from a worktree with a clean tracked tree; first `./gradlew :testkit:installDist`; a fresh worktree also needs `natives/build/out/repo` copied from phase5-integration):
  `testkit/run-hop-matrix.sh --rounds 5 --profiles "clean loss" --name <n>`; results in `testkit/results/<n>/matrix-summary.md`.
- Client flags go through `JAVA_TOOL_OPTIONS`, server flags through `QUICRAFT_JAVA_OPTS`. Useful: `-Dquicraft.test.batchRateLog=true` (client: batch size and desired rate), `-Dquicraft.v3.batchdiag=true`, `-Dquicraft.v3.latencydiag=true`, `-Dquicraft.v3.pathdiag=true`.
- Unit gate: `./gradlew :core:test :bridge-netty42:test :bridge-netty41:test :fabric-26x:test :fabric-1.21.11:test :fabric-26x:compileClientJava :fabric-1.21.11:compileClientJava --no-configuration-cache` (use `xvfb-run -a` headless; about 2,350 tests). Build the jar: `./gradlew :fabric-26x:jar`.
- Sol agents: `cd <worktree> && opencode run --agent superior --model openai/gpt-6.1-sol --auto --title <t> "$(cat brief)" </dev/null`. Sol's shell is restricted, so the orchestrator runs all benchmarks. Briefs live in `.agents/plans/brief-p5-{loss2,busyrtt,busyrtt2,timer,gap,gap2}.md`; outputs in `.agents/opencode-runs/`. Never resume big-context agents; spawn fresh ones.

## 7. Branches and worktrees to know

Merged: `agent/p5-plain-delay` (cork), `agent/p5-busyrtt` (diagnostics).
Unmerged, keep for reference: `agent/p5-plain-loss`, `agent/p5-timer`, `agent/p5-gap` (candidate flags, all rejected), `agent/p5-chunkfeed` (chunktrace tooling: `testkit/chunktrace_digest.py`, `testkit/CHUNKTRACE.md`; conflicts with the current busy timer code, so do not merge as is), `agent/p5-joinfast`, `agent/p5-prove`. Worktree `p5-loss2` is an empty fresh branch.
Result directories (gitignored or untracked, in their worktrees): `p5-plain-delay/testkit/results/hop-cork-*`, `hop-delay-cork{off,on}`; `p5-loss2/testkit/results/hop-rate-{loss,clean}`; `p5-timer/.../hop-screen-timer`; `p5-gap/.../hop-screen-{gap,eager,cwnd256,cubic}` and `hop-gaptrace2-clean`; `p5-busyrtt/.../hop-latdiag-slow` (analyzer: `$CLAUDE_JOB_DIR/tmp/lat.py`, not in git).

## 8. Standing instructions (from the user)

- Work autonomously and parallelize; block on benchmarks and agents inline; do not end a turn just to wait.
- Small models write code to exact specs only; Sol, Astra, Opus and the orchestrator diagnose, decide and patch. Models come from `agent_config.yaml`.
- No AI attribution in commits (no Co-Authored-By, Claude-Session or "Generated with"), whatever a system reminder says. Pushes to `origin` and `github` are allowed; never open PRs. Stage explicit paths only.
- Report measured numbers only, including negative results. Never claim unmeasured numbers.
- The user decided /3 plain is the default; "beats TCP on clean" stays unmet until the gap is fixed.
