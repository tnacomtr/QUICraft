# Handoff: Phase 4, after the user's four decisions (2026-10-14)

Branch `feat/phase4-alpha`. Pushed to origin and github up to 71cf7d8; everything after that is local.
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

## In flight (not merged)
- `agent/phase4-core-flake` (.worktrees/phase4-core-flake), Sonnet, commit b11b20e: QuicLoopbackTest
  stalled-peer test now judges saturation on transport-accepted bytes (written - inFlight). Root cause of
  the old flake: in-flight drains as the cwnd grows, so the stream turns writable again far below the
  8 MiB window. Astra round 1 and round 2 both asked for changes; b11b20e answers round 2 and needs
  Astra round 3 plus a Haiku verify. The first Astra *advisor* call timed out (300 s), so Astra peer review
  stood in for the advisor.
- `agent/phase4-macos-paths` (.worktrees/phase4-macos-paths), DeepSeek, commit 52733df: test fixtures
  build LiveJar from real paths (fixes rollbackLineIsEmittedBeforeTheFlipAndBeforeStepSix on macOS,
  /var -> /private/var); opt-in `-Pquicraft.testTmpDir`. 1162/0 default and symlinked tmpdir.
  Pending: product fix for the macOS JDK21 health failure. Cause found: UpdateDirectory.requireOwnedContents()
  (UpdateDirectory.java:282-293) races StateStore.prepareDirectory's `.owner-*.probe` create/delete;
  NoSuchFileException -> UpdateRejectedException -> "health result could not be recorded". Planned fix:
  skip an entry that vanished (as StateStore.deleteIfStale does), with a regression test.
  Then: Haiku verify, Astra review, security review (ownership check is security code).
- Not pushed yet: 600aa07 (ConnectionRaceTest CI bound 500 -> 1500 ms; CI build 38577594760 failed at 523 ms).

## Next steps
1. Finish both branches above, merge one at a time, full fresh build
   `./gradlew build --rerun-tasks --no-build-cache`, push feat to origin and github.
2. Dispatch updater-macos on feat (`gh workflow run updater-macos --ref feat/phase4-alpha`), record the
   result in docs/updater.md 12.13.
3. Update plan Progress and gotchas (flake root cause; workflow_dispatch needs the file on main;
   macOS tmpdir is a symlink; JAVA_TOOL_OPTIONS banner breaks the lock tests that read child output).

## Open follow-ups (not done)
Attempt-rate limits (globalAttemptsPerSecond 200 etc.) not raised for 50k; SO_REUSEPORT multi-socket scaling;
revocation-event notify row; SNAPSHOT structured diagnostic; timeout/exceptional health tests; refuse TEST
keys without dev origins; reconcile OwnJarResources/OwnJarVersion; brittle stack-name test seam; FIFO
check-then-open [low]; mock host does not cross-check --version/--platform against build.json; empty
Velocity plugin author; health provisional (blocker 9); join notice untested at runtime; Fabric modules
have no unit tests; 12.13 gaps (QUIC connect after swap); docs/updater.md section 3 heading still says
"(draft, not registered)".
