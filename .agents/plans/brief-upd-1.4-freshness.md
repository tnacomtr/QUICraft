# Brief upd-1.4: freshness policy and clock floor

GOAL: Wave 1.4 of `.agents/plans/2026-10-13-updater-runtime.md`, with tests.
SCOPE: new files only under `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir.
BRANCH/WORKTREE: `agent/phase4-upd-freshness` in `/home/tna/QUICraft/.worktrees/phase4-upd-freshness`. Absolute paths; Gradle only there.
READ FIRST: `docs/updater.md` lines 594-625 (12.5) and the R6/M6 rules (grep -n "R6\.\|M6\." docs/updater.md); `StateRecords.java`.

STEPS:
1. `FreshnessPolicy` (final class, static methods, no I/O). Constants `Duration FUTURE_TOLERANCE = 24h`, `CLOCK_WARN = 24h`, `MANIFEST_MAX_LIFETIME = 45 days`, `REVOCATIONS_MAX_LIFETIME = 400 days`.
   - `Instant effectiveNow(Instant systemNow, Instant clockFloor)` = max.
   - `boolean futureIssued(Instant issued, Instant systemNow)` = issued > systemNow + 24h (exactly +24h is NOT future).
   - `boolean unexpired(Instant expires, Instant effectiveNow)` = expires > effectiveNow (no grace; equal is expired).
   - `boolean clockBehind(Instant systemNow, Instant clockFloor)` = systemNow < clockFloor - 24h.
   - `boolean structuralTimesValid(Instant issued, Instant expires, Duration maxLifetime)` = issued < expires and (expires - issued) <= maxLifetime.
   Null arguments -> NullPointerException.
2. `ClockFloor.of(Instant trustBuilt, StateRecords.CurrentRevocations current, Map<String, StateRecords.KidSeq> seqByKid, Set<String> revokedKids) -> Instant`: max of trustBuilt, current.issued() when non-null (seed case has null), and issued of every seqByKid entry whose kid is NOT in revokedKids. Never reads a clock. Javadoc cites docs/updater.md 12.5.
3. Tests: boundaries at exactly 24h, exactly expiry, exactly 45 and 400 days (valid) and +1s (invalid), issued == expires invalid; effectiveNow with clock behind/ahead; ClockFloor ignores revoked kid even if its issued is the max, uses seed null issued correctly, returns built when nothing else. No real clock use in tests (fixed Instants).
4. Run `<worktree>/gradlew -p <worktree> :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit "updater: freshness policy and clock floor" with trailer lines (use your own model name in Co-Authored-By):
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: pure, JDK only. Never use the built-in advisor() tool.
REPORT: RESULT, CHANGED, test counts from XML, CONCERNS, BRANCH + COMMIT.
