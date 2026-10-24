# Handoff: after 0.1.0-alpha.2 (2026-10-15)

0.1.0-alpha.2 is published (pre-releases on GitHub and git.sudoe.rs, jars plus signed updater metadata seq 2). It
carries the 0-RTT delivery fix. main and feat/phase4-alpha are on 0.1.0-alpha.3-SNAPSHOT (668d2dc), CI green.

## Updater, tested live
- On the cloud test server, alpha.1 updated itself to alpha.2 with `quicraft update check` + `quicraft update`
  (INSTALLED_RESTART_PENDING, atomic swap under the old file name); after a restart it runs alpha.2 and the jar's
  sha256 matches the release asset.

## History rewrite (2026-10-14, owner's request)
- AI attribution trailers were stripped from every commit on main, feat and both tags, then force-pushed to both
  remotes. Trees are unchanged. Tags v0.1.0-alpha.1 and updater-metadata point at 3bdcf64; release assets on both
  hosts are unchanged.
- Rule (CLAUDE.md, Working rules): no AI attribution in commits, tags, release notes or PRs; Microsoft Copilot keeps
  its own. Local agent/* branches still carry the old history: re-apply their changes, don't merge them.

## CI
- CI runs on branch pushes only (no tag pushes): release tags carry a release version that the development trust
  refuses by design.
- EarlyDataGameTest flakes on fabric-1.21.11 had two causes, both fixed in tests only:
  1. the fixed 10-tick wait for the TLS session ticket (now waits on QuicClient.hasSessionTicket, which reads
     Netty's package-private session cache by reflection; SessionResumptionTest guards it);
  2. the 5 ms floor of the 0-RTT preamble grace (protocol.md section 8) on a busy runner: the client correctly
     abandoned 0-RTT. The gametest sets QuicClient.setGraceFloorForTests(100); production is unchanged.
  QuicClient.lastEarlyOutcome() names why an attempt sent no 0-RTT; failure messages include it.

## 0-RTT delivery (owner approved, 2026-10-15)
- After a 0-RTT handshake the client now hands the connection to the game as soon as the server's preamble is
  read (ClientStream.whenPreambleRead); the grace G is only the abandon timeout. protocol.md section 8 says
  "waits up to G". Regression test: EarlyDataTest.aZeroRttConnectionReachesTheGameWhenThePreambleArrives...

## Open follow-ups
- The release manifest (seq 2) expires 2026-11-14T10:18:29Z: publish seq 3 (re-sign or a new release) before
  then; the owner signs. Revocations seq 1 expire 2027-10-14.
- updater.md blocker 6 (withdrawal), macOS FileChannel.force semantics, Velocity builds other than 4.2.0-30.
- The owner should revoke the Forgejo token pasted in chat and delete ~/.config/quicraft/forgejo-token.
- Quarantined branches agent/phase4-retry-tests and agent/phase4-cwnd-tests: never merge or delete.

## Test servers and the UDP finding
- sudoe.rs (Fabric 1.21.11) and cloud.teamshiftenergy.com (Fabric 1.21.11, systemd unit `minecraft`, /opt/minecraft,
  online mode, RCON off) both run QUICraft.
- Both hosts sit at Realtox Media behind RCS Technologies (AS203446). Routes entering through RCS drop UDP 25565
  (also 19132, 27015); 25564, 25566 and 443 pass. Routes entering through LWLcom pass. The client falls back to TCP
  as designed. Not a QUICraft bug; the owner may raise it with Realtox.

## /3 default blocker: busy RTT on constrained links (2026-10-23)
- n=10 hop matrix (docs/benchmarks.md, a180eb82): busy RTT during chunk transfer is +687 ms on delayloss and +2.1 s
  (13.5x TCP) on slow; plain is +185 ms on slow. Chunk delivery is 58-89% faster than TCP on delayloss/slow/jitter/reorder.
- Mechanism not yet measured. Plan (advisor): sample quiche srtt/min_rtt/cwnd/in-flight and unsent chunk bytes server-side
  on one busy slow run; srtt ~ play RTT means network bufferbloat (delay-based chunk backpressure), srtt ~ base but play
  RTT high means in-stack queueing (bound unsent CHUNK bytes to ~1-2 cwnd, REALTIME urgency). Gate: busy RTT <= TCP+25% on
  slow/delayloss with >=60% of the chunk win, n=5, then clean n=10 no regression. Kill: two fixes without a pass -> adaptive
  guard. Blocks making /3 default, not current work. Join +150..415 ms is a separate round-trip-count track.

## /3 default decision (2026-10-24)
- Default flipped to the plain wall-time batch timer (62eab39e); busy is opt-in `-Dquicraft.v3.batchTimerBusy=true`. The server chunk-batch cork (9e6df973, `-Dquicraft.v3.chunkBatchCork`, `=false` disables) is on by default. Evidence: docs/benchmarks.md "Plain /3 default, server chunk-batch cork, rejected loss experiments".
- Still behind TCP with plain: clean and loss chunk delivery (+16.5% / +20.9% idle) and join (+98..+870 ms).
- Open: fresh Sol agents on branches agent/p5-loss2 (plain loss gap) and agent/p5-busyrtt (busy RTT mechanism, in-stack not network); briefs in .agents/plans/brief-p5-loss2.md and brief-p5-busyrtt.md, logs in .agents/opencode-runs/. Unmerged earlier branches: agent/p5-plain-loss (rejected experiments), agent/p5-chunkfeed (traces).

See .agents/HANDOFF-2026-10-24-slash3-default-and-gap.md for the full 2026-10-24 handoff (plain default, cork, rejected candidates, next steps).
