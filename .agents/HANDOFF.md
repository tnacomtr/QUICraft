# Handoff: Phase 4, after the user's four decisions (2026-10-14)

Branch `feat/phase4-alpha`, pushed to origin and github.
Routing: DeepSeek codes (gate.sh default-coder), Haiku verifies, Astra peer-reviews (opencode),
security via Antigravity Opus when its quota allows, else the native security-reviewer.
Test keys, the dev trust file and the TLS fixture are throwaway (user told; they will swap keys).

## User decisions (2026-10-14), status
1. Admission defaults raised: maxOpen 65536, maxTrackedIps 131072 (maxOpenPerIp 32). MERGED bfb0068.
2. Push and run the macOS/JDK21 job. Pushed feat; workflow file put on main (b70fe0f, pushed) so it can be
   dispatched. Run 38577920195: macos-latest JDK21 1160/1162, JDK25 1161/1162; macos-13 never started
   (image retired; cancelled). Matrix switched to macos-15-intel: 4fcba2f (local).
3. "The people on the OP list should be able to run it": MERGED b2ea6c4. Any OP-list entry (level 1-4), or
   the admin permission; console/RCON allowed; command blocks/functions refused (default level 2);
   execution-time recheck so `/execute as <non-op>` is refused. Live smoke on 26.1.2 passed all 8 cases.
   1.21.11 not probed live (testkit speaks 26.1 only). Security review: lows only, documented.
4. 31 `phase4-upd-*` worktrees removed (branches kept). Quarantined branches untouched.

## Merged since (2026-10-14 evening)
- Core flake fixed, b70ac29 (Sonnet; Astra rounds 1-3; saturation judged on written - inFlight).
- macOS fixes, f3d6bdc: real-path LiveJar fixtures (52733df), ownership scan skips only vanished top-level
  files (b9f5e2e + f75a924 after a security low). Linux repro: -Pquicraft.testTmpDir=/var/run/user/1000/qc-tmp.
- Full fresh build after all merges: core 210/0, velocity 73/0, updater 1166/0.

## Next steps
All four decisions are done and pushed. macOS run 38600235420 green (4 jobs, 1166/0 each), recorded in
docs/updater.md 12.13 (16a616d). Worktrees of the merged branches phase4-core-flake, phase4-admission-defaults,
phase4-fabric-oplist and phase4-macos-paths are retained (removal not yet asked for).

## Open follow-ups (not done)
readJournal exists-then-owner race vs deleteJournal (fails closed; availability only; check whether UpdaterTransaction admit holds the lease); Attempt-rate limits (globalAttemptsPerSecond 200 etc.) not raised for 50k; SO_REUSEPORT multi-socket scaling;
revocation-event notify row; SNAPSHOT structured diagnostic; timeout/exceptional health tests; refuse TEST
keys without dev origins; reconcile OwnJarResources/OwnJarVersion; brittle stack-name test seam; FIFO
check-then-open [low]; mock host does not cross-check --version/--platform against build.json; empty
Velocity plugin author; health provisional (blocker 9); join notice untested at runtime; Fabric modules
have no unit tests; 12.13 gaps (QUIC connect after swap); docs/updater.md section 3 heading still says
"(draft, not registered)".
