# Handoff: after 0.1.0-alpha.1 (2026-10-15)

0.1.0-alpha.1 is published (pre-releases on GitHub and git.sudoe.rs, jars plus signed updater metadata). main and
feat/phase4-alpha are on the development version 0.1.0-alpha.2-SNAPSHOT.

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

## Open follow-ups
- Owner decision: after 0-RTT, the client delivers the connection only when the grace timer fires, even if the
  server's preamble arrived earlier (QuicClient.awaitPreamble). That costs up to G (≤100 ms) of join latency
  on real links. Delivering as soon as the preamble arrives would fix it; it changes transport timing, so ask first.
- Re-sign a seq 2 release manifest before 2026-11-13T19:52:53Z (same entries; the owner signs).
- updater.md blocker 6 (withdrawal), macOS FileChannel.force semantics, Velocity builds other than 4.2.0-30.
- The owner should revoke the Forgejo token pasted in chat and delete ~/.config/quicraft/forgejo-token.
- Quarantined branches agent/phase4-retry-tests and agent/phase4-cwnd-tests: never merge or delete.
