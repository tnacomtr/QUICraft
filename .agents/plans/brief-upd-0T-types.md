# Brief upd-0T: Wave 0 state record types (updater module)

GOAL: add immutable record types (no logic beyond validation) that Wave 1 and Wave 2 share.
WHY: plan `.agents/plans/2026-10-13-updater-runtime.md` section "Wave 0 types"; Wave 1.3 and Wave 2 both depend on them.
SCOPE: only new files under `updater/src/main/java/rs/sudoe/quicraft/updater/` and `updater/src/test/java/rs/sudoe/quicraft/updater/`. Do not edit any existing file.
BRANCH/WORKTREE: `agent/phase4-upd-types` in `/home/tna/QUICraft/.worktrees/phase4-upd-types`. Work and run Gradle ONLY there (use absolute paths; never `cd` into /home/tna/QUICraft itself).
READ FIRST: `updater/src/main/java/rs/sudoe/quicraft/updater/UpdateDocuments.java` (record style to copy, SPDX header, javadoc density), `Hex.java` (package-private helpers), `docs/updater.md` lines 770-815 (state.json schema).

STEPS (do exactly these):
1. Create file `StateRecords.java`: `public final class StateRecords { private StateRecords() {} ... }` holding nested public records, same style as UpdateDocuments. First line `// SPDX-License-Identifier: GPL-3.0-or-later`. Records:
   a. `PersistedTrust(int epoch, String keySetFingerprint, String revocationPub)`. Compact constructor: epoch >= 1 else IllegalArgumentException; keySetFingerprint and revocationPub must each be exactly 64 chars of [0-9a-f] (write a private static helper `requireHex(String s, int chars, String field)` in StateRecords that throws IllegalArgumentException("invalid " + field) — never echo the value).
   b. `KidSeq(long seq, String sha256, Instant issued)`: seq >= 0; sha256 64 hex; issued non-null.
   c. `WithdrawnEntry(String sha512, UpdateVersion version, String kid)`: sha512 128 hex; version non-null; kid 16 hex.
   d. `CurrentRevocations(long seq, String sha256, Instant issued, Instant expires, long minManifestSeq)`: seq >= 0, minManifestSeq >= 0. Seed case: sha256 is "" AND issued == null AND expires == null. Non-seed: sha256 64 hex AND issued, expires non-null. Any other mix -> IllegalArgumentException. Add method `boolean seed()` returning sha256.isEmpty().
   e. `UpdaterState(int epoch, String keySetFingerprint, String revocationKey, CurrentRevocations revocations, Map<String, KidSeq> seqByKid, List<WithdrawnEntry> withdrawn, String lastAnnounced, List<String> warnedBad, Map<String, Instant> tornSince)`. Validation: epoch >= 1; keySetFingerprint, revocationKey 64 hex; revocations non-null; every seqByKid key 16 hex; withdrawn unique by sha512; lastAnnounced non-null ("" allowed, otherwise must parse with UpdateVersion's parse method - look at UpdateVersion.java for the exact static method name; if it throws a checked exception wrap it into IllegalArgumentException("invalid lastAnnounced")); warnedBad each 64 hex; tornSince keys only "modrinth", "github", "sudoe", values non-null. Copy maps into `Collections.unmodifiableMap(new TreeMap<>(map))` (sorted, deterministic) and lists with `List.copyOf`. Add method `PersistedTrust persistedTrust()` returning `new PersistedTrust(epoch, keySetFingerprint, revocationKey)`.
   f. `RevokedState(List<UpdateDocuments.RevokedKey> revokedKeys, List<String> blockedArtifacts)`: each RevokedKey pub 64 hex and kid 16 hex; revokedKeys unique by pub; blockedArtifacts each 128 hex and unique. List.copyOf. Add `static RevokedState empty()` and `Set<String> revokedPubs()` (unmodifiable).
   g. `Snapshot(UpdaterState state, RevokedState revoked)`: both non-null.
   h. `StateDelta(UpdaterState newState, RevokedState newRevoked)`: both non-null. Javadoc: "The single change a policy function returns; the store applies it exactly once."
   All null components -> NullPointerException via Objects.requireNonNull(x, "field name").
   Cardinality caps (32 kids, 256 withdrawn, 64 revoked keys...) are NOT checked here; the codec (Wave 2) does that. Say so in the class javadoc.
2. Create `StateRecordsTest.java` (JUnit 5, copy the style of `HexTest.java`): for each record, one valid construction test and one test per rejection rule above (uppercase hex rejected, wrong length rejected, seed mix rejected, duplicate withdrawn sha512 rejected, unknown tornSince key rejected, duplicate revoked pub rejected, defensive copy: mutating the input list/map after construction does not change the record, and the record's collections are unmodifiable).
3. Run in the worktree: `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache` (absolute path `/home/tna/QUICraft/.worktrees/phase4-upd-types/gradlew -p /home/tna/QUICraft/.worktrees/phase4-upd-types ...`). Must be green. Do not run the root `build`.
4. Commit: `git -C /home/tna/QUICraft/.worktrees/phase4-upd-types add -A updater && git -C ... commit -m "updater: state record types"` with the trailer lines:
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: no new dependencies; JDK only; Java 21 language level (records fine). No network. Never use the built-in advisor() tool.
DONE WHEN: tests green, one commit on the branch.
REPORT: RESULT, CHANGED (files), test count from `updater/build/test-results/test/*.xml` (grep tests= and failures=), CONCERNS, BRANCH + COMMIT sha.
