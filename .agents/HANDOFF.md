# Handoff: Phase 4 updater runtime (2026-10-14, implementation finished)

Branch `feat/phase4-alpha` (local only, nothing pushed). Plan: `.agents/plans/2026-10-13-updater-runtime.md` (Progress section).
Routing: DeepSeek codes (gate.sh default-coder), Haiku verifies (general-purpose agent, model haiku), Astra peer-reviews (opencode), security via Antigravity Opus when its quota allows, else the native security-reviewer.
Test keys are RFC 8032 TEST seeds only; the dev trust file `updater/dev-trust/updater-trust.json` and the TLS fixture `updater/src/test/resources/tls/test-localhost.p12` are throwaway. The user was told the implementation is done so they can swap keys.

## Merged on feat/phase4-alpha (gated)
All updater waves 0-4: core, transaction, crash matrix, discovery, facade (4F/4Fb), Velocity adapter (4V, 495175e), Fabric adapter (4Fa, 140971f). Swap-then-read run recorded in docs/updater.md 12.13 (7272717). Mock release host + production clock fix (agent/phase4-upd-mockhost; see plan Progress for the merge commit). Updater suite 1162/0, velocity 73/0.

## Waiting on the user
- Raise admission defaults (TransportConfig.java:36-38) per the 50k capacity run (41,403 connections, 0 failures).
- Push of the macOS/JDK21 CI job (dfb7f09).
- Fabric permission node `quicraft.admin.update` vs OP level 3.
- Worktree cleanup (all phase4-upd-* worktrees retained).

## Open follow-ups (not done)
Core flake QuicLoopbackTest stalled-peer test (2 of 5 full builds; see gotchas); revocation-event notify row; SNAPSHOT structured diagnostic; timeout/exceptional health tests; refuse TEST keys without dev origins; reconcile OwnJarResources/OwnJarVersion; brittle stack-name test seam; FIFO check-then-open [low]; mock host does not cross-check --version/--platform against build.json; empty Velocity plugin author; health provisional (blocker 9); join notice untested at runtime; Fabric modules have no unit tests; 12.13 gaps (JDK 21, macOS, QUIC connect after swap).
