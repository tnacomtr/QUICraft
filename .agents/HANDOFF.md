# Handoff: 0.1.0-alpha.1 release in progress (2026-10-14)

main = feat/phase4-alpha = 594b890 ("release: 0.1.0-alpha.1"), tag v0.1.0-alpha.1, pushed to origin and github.
Phase 4 merged into main as 5186825 (tree identical to the verified feat c586d32).

## Done
- Haiku full feat verification PASS (core 231, velocity 79, updater 1209, release-tool 30, build-logic 56, bridges 30/30).
- CI on 4a1e6e2: build, natives (5 platforms), fabric-gametest (fabric-1.21.11 EarlyDataGameTest flaked once on
  "then 0-RTT again: 0-RTT used" after a 2 s server stall; rerun passed), updater-e2e 9/9 (ubuntu, macos-latest,
  macos-15-intel x velocity, fabric-26x, fabric-1.21.11).
- Release jars built with clean checkReleaseTrust (owner trust, 3 outer jars), in the scratchpad dist/.
- GitHub pre-release v0.1.0-alpha.1 with the 3 jars; read back byte-identical.
- release.v1.json (seq 1, expires 2026-11-13T19:52:53Z) and revocations.v1.json (seq 1) composed, unsigned.

## Waiting on the user
1. Sign revocations.v1.json with the backup key (1113f8638b12fe66), release.v1.json with the primary (a50c1ac6c6108379).
2. Run dist/finish-forgejo.sh (token from a file): assembles + verifies sigs, git.sudoe.rs release + updater-metadata.
Then: GitHub updater-metadata release (gh), live check of both hosts, report.

## Open follow-ups
- EarlyDataGameTest timing flake on slow runners (10-tick wait for the ticket).
- updater.md blocker 6 (withdrawal), macOS FileChannel.force semantics, Velocity builds other than 4.2.0-30.
- Bump main to the next -SNAPSHOT after the release.
- Quarantined branches agent/phase4-retry-tests and agent/phase4-cwnd-tests: never merge or delete.
