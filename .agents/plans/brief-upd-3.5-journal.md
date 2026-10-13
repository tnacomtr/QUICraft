# Brief upd-3.5: journal, update directory layout, durability seam, revalidation compare

GOAL: plan `.agents/plans/2026-10-13-updater-runtime.md` section "Wave 3 to 5: frozen", item 3.5; spec docs/updater.md 12.8 journal.json (lines ~817-848), 12.10 steps 2-3 and 5 (staging/backup files), invariant 11.
SCOPE: new files in `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir. Reuse (do not edit) StateStore's package-private helpers (prepareDirectory, openLock, owner check) if useful.
BRANCH/WORKTREE: `agent/phase4-upd-journal` in `/home/tna/QUICraft/.worktrees/phase4-upd-journal` (contains 3.0 types and StateStore/TransactionLock/CanonicalJson). `./gradlew`/`git` from there only.
READ FIRST: spec sections above; `StateStore.java`, `CanonicalJson.java`, `StateCodec.java` (codec style to copy), `ArtifactIdentity.java`.

API:
1. `JournalPhase` enum PREPARED("prepared"), SWAPPED("swapped"), BOOT_PENDING("boot-pending"), RECOVERY_REQUIRED("recovery-required").
2. `JournalRecord(String txid, JournalPhase phase, String platform, long manifestSeq, JournalLive live, JournalStaged staged, String backupSha512, Instant created, int bootAttempts)`, `JournalLive(String path, long size, String sha512, UpdateVersion version)`, `JournalStaged(long size, String sha512, UpdateVersion version)` — validation per the spec table (txid 32 hex, path absolute and <= 4096 chars, sizes >= 0, 128-hex hashes, backupSha512 == live.sha512, bootAttempts 0..9, platform in the three platforms). Method `JournalRecord withPhase(JournalPhase)` and `withBootAttempts(int)`.
3. `JournalCodec.encode/decode`: header type `quicraft.journal`, v 1, cap 8192, exact keys, deterministic (CanonicalJson), like StateCodec.
4. `DurabilityOps` interface: `void force(FileChannel ch) throws IOException; void atomicMove(Path from, Path to) throws IOException; void syncDirectory(Path dir) throws IOException;` and `static DurabilityOps system()` (real implementation: force(true), Files.move ATOMIC_MOVE, directory FileChannel force; syncDirectory THROWS on failure — do NOT swallow; callers decide). Tests inject failing implementations.
5. `UpdateDirectory(Path updateDir, DurabilityOps ops)`: subdirs `staging/` and `backup/` created 0700 (POSIX when supported), NOFOLLOW, owner check like StateStore. Methods:
   - `static String newTxid()` (32 lowercase hex from SecureRandom).
   - `Path stagingPart(String txid)`, `Path stagingJar(String txid)`, `Path backupJar(String txid)`, `Path journal()` — names fixed from a validated 32-hex txid; anything else -> IllegalArgumentException.
   - `FileChannel createPart(String txid)`: CREATE_NEW, WRITE, NOFOLLOW_LINKS, 0600.
   - `void commitPart(String txid, FileChannel ch)`: force, close, atomicMove part->jar, syncDirectory(staging).
   - `String copyBackup(String txid, Path liveJar)` -> sha512 of the copy: copy with NOFOLLOW to `backup/<txid>.jar` (CREATE_NEW), force, syncDirectory(backup), re-hash and return.
   - `Optional<JournalRecord> readJournal() throws IOException, UpdateRejectedException` (decode errors propagate; caller handles recovery-required); `void writeJournal(JournalRecord)` (tmp + force + atomicMove + syncDirectory; no .prev); `void deleteJournal()`; `void deleteStaging(String txid)` (part and jar, ignore missing); `List<String> stagingTxids()` (names matching the fixed patterns only; others listed separately via `List<Path> unexpectedStagingEntries()`).
   - `boolean sameFileStore(Path liveJar)`: Files.getFileStore(liveJar) equals Files.getFileStore(staging dir).
6. `RevalidationDecision` enum MATCH, RESTART_REQUIRED, REJECT and `Revalidation.compare(ArtifactIdentity staged, UpdateDocuments.ReleaseEntry revalidated, String platform)`: MATCH when platform, version, size and sha512 all equal; RESTART_REQUIRED when the entry is for the same platform but any of version/size/sha512 differ; REJECT when revalidated is null (no entry any more) or platform differs.
TESTS: codec round trip, determinism, every validation rule, cap; UpdateDirectory: path helpers reject bad txids; createPart refuses an existing file and a symlink; commitPart moves and syncs (assert via a recording DurabilityOps order: force, atomicMove, syncDirectory); copyBackup hash equals source hash; writeJournal with a DurabilityOps whose atomicMove throws leaves the old journal intact; syncDirectory failure propagates; stagingTxids ignores unrelated names and reports them as unexpected; sameFileStore true for two paths in one @TempDir; Revalidation cases.
Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit, trailer (own model):
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9
CONSTRAINTS: JDK only. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
