# Brief upd-3.7: startup recovery and health completion

GOAL: plan `.agents/plans/2026-10-13-updater-runtime.md` section "Wave 3 to 5: frozen", item 3.7 (+ LiveJarDiscovery/LiveJar types); spec docs/updater.md 12.11 steps 1-8 (lines ~922-952) and 12.10 step 7 (health, backup pruning). Never throws; deterministic; every case table-tested.
SCOPE: new files in `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir.
BRANCH/WORKTREE: `agent/phase4-upd-recovery` in `/home/tna/QUICraft/.worktrees/phase4-upd-recovery` (contains UpdateDirectory, JournalRecord/Codec, DurabilityOps, TransactionLock, StateStore, shared types). `./gradlew`/`git` from there only.
READ FIRST: the spec lines above; `UpdateDirectory.java` (class javadoc: cleanup order staging then journal; never delete the current journal's backup; sync failure after a move means the move may have happened), `JournalRecord.java`, `JournalPhase.java`, `TransactionLock.java`, `HealthSignal.java`, `RuntimeIdentity.java`, `UpdateVersion.java`.

TYPES:
- `LiveJar(Path realPath, Path realScanDirectory, long size, String sha512, UpdateVersion onDiskVersion)` record (absolute paths, 128-hex sha512, size > 0).
- `LiveJarDiscovery { LiveJar discover() throws IOException, UpdateRejectedException; }` (platform implementations come in Wave 4).
- `RecoveryStatus { IDLE, HEALTH_PENDING, RECOVERY_REQUIRED, DISABLED }`.
- `RecoveryTicket(String txid)` (32 hex).
- `RecoveryContext(Path updateDir, DurabilityOps ops, LiveJarDiscovery discovery, RuntimeIdentity identity)`.
- `RecoveryResult(RecoveryStatus status, Optional<RecoveryTicket> ticket, Optional<LiveJar> live, List<String> messages)`; messages are fixed strings plus paths of OUR files (backup path and the manual command text are allowed: "stop the server, copy <backup> over <live>, start it").
- `HealthCompletion { HEALTHY, TIMED_OUT, FAILED, JOURNAL_CHANGED }`.

`final class StartupRecovery`:
1. `static RecoveryResult run(RecoveryContext ctx)` — NEVER throws (catch Throwable at the top level -> DISABLED with a fixed message, except rethrow Errors that are VirtualMachineError). Order:
   a. Acquire TransactionLock.tryAcquire(updateDir) (busy/another process -> DISABLED "another server uses this update directory"); release it before returning (try-with-resources).
   b. Open UpdateDirectory(updateDir, ops); call requireOwnedContents() (failure -> DISABLED, touch nothing).
   c. Step 1: discovery.discover() -> LiveJar; failure -> DISABLED, no file touched. Hash is in LiveJar.sha512 (discovery computes it).
   d. deleteJournalTmp() (stale tmp from a crash).
   e. readJournal(): decode failure -> RECOVERY_REQUIRED, keep every file (step 3).
   f. Step 2 (no journal): delete staging entries whose txid is in stagingTxids() (no journal to match), never touch unexpected entries (report them in messages); backups untouched (they are pruned only after health). -> IDLE.
   g. Step 3: journal.platform != identity.platform, or journal.live.path != live.realPath.toString() -> RECOVERY_REQUIRED, keep all files.
   h. Step 4 (before phase dispatch): live.sha512 == journal.backupSha512 and backupSha512 != staged.sha512 -> manual restore: deleteStaging(txid), then deleteJournal(), keep backup, IDLE with an INFO message.
   i. Step 5 PREPARED: live == live.sha512 (journal.live.sha512) -> deleteStaging then deleteJournal, IDLE; live == staged.sha512 -> treat as step 6; else RECOVERY_REQUIRED.
   j. Step 6 SWAPPED: identity.runningVersion equals staged.version AND live.sha512 == staged.sha512 -> write journal phase BOOT_PENDING, bootAttempts 1 (durable, before anything else) -> HEALTH_PENDING with ticket; else RECOVERY_REQUIRED (write phase RECOVERY_REQUIRED to the journal? NO: spec says keep files; only the status is reported. Do not rewrite an existing journal to express recovery-required).
   k. Step 7 BOOT_PENDING: re-check version and hash as in j (fail -> RECOVERY_REQUIRED); bootAttempts < 2 -> increment, write, HEALTH_PENDING with ticket; else RECOVERY_REQUIRED.
   l. Phase RECOVERY_REQUIRED in the journal (written by an earlier version or by hand) -> RECOVERY_REQUIRED.
   m. Step 8 messages for RECOVERY_REQUIRED: fixed WARN text with backup path and the manual command; the caller logs every start and notifies OPs once per start.
   An IOException after a write may have happened (sync failure) -> RECOVERY_REQUIRED with the message "recovery could not confirm durable state", never IDLE.
2. `static CompletionStage<HealthCompletion> completeHealth(RecoveryContext ctx, RecoveryTicket ticket, HealthSignal signal, Duration timeout)`: awaits signal.awaitHealthy(timeout) WITHOUT holding any lock; then (on the completing thread) acquires TransactionLock, re-reads the journal: missing or different txid -> JOURNAL_CHANGED (touch nothing); phase not BOOT_PENDING -> JOURNAL_CHANGED; healthy -> deleteJournal, then delete backups of EARLIER transactions only (every backupTxids() entry except ticket.txid; the current transaction's backup is kept until the NEXT transaction passes health, spec 12.10 step 7) -> HEALTHY. Not healthy or timeout -> keep journal and backup -> FAILED / TIMED_OUT. Exceptions complete the stage exceptionally only with a fixed-message UpdateRejectedException; never leave a lock held.
TESTS (`StartupRecoveryTest`, table-driven where possible, @TempDir with owner-only permissions (0700/0600) — StateStore refuses group/other bits; fake LiveJarDiscovery; fake HealthSignal with controllable CompletableFuture): every step 1-8 branch including: discovery failure touches nothing; requireOwnedContents failure; stale journal.json.tmp removed; no journal deletes orphan staging and keeps unexpected entries and all backups; corrupt journal keeps everything; wrong platform / wrong live path; manual restore; PREPARED live==old, live==staged (continues to boot-pending), unknown; SWAPPED with matching and mismatching version/hash; BOOT_PENDING attempt 1 -> 2, attempt 2 -> recovery-required; journal write failure via a failing DurabilityOps -> RECOVERY_REQUIRED; lock busy -> DISABLED. completeHealth: healthy deletes journal and only earlier backups; timeout keeps both; journal replaced by another txid -> JOURNAL_CHANGED; no lock held while waiting (assert TransactionLock can be acquired by the test while the health future is pending).
Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Small commits, own-model trailer:
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9
CONSTRAINTS: JDK only. Spec wins over this brief; report differences. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
