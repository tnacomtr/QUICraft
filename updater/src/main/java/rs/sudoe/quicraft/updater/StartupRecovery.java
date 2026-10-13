// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Startup recovery and health completion of an update transaction (docs/updater.md 12.10 step 7,
 * 12.11). Run once per start, under {@code txn.lock}, before anything else in the updater.
 *
 * <p>{@link #run} never throws. Any unexpected failure before a journal write disables the updater for
 * this start; a failure after a write that may have happened reports {@link RecoveryStatus#RECOVERY_REQUIRED}.
 * Files are only deleted where the spec allows it; every doubtful case keeps them.
 *
 * <p>{@link #completeHealth} waits for the health verdict without holding any lock, then takes
 * {@code txn.lock} on the completing thread to record the result.
 */
final class StartupRecovery {

    static final String MSG_BUSY = "another server uses this update directory";
    static final String MSG_UNAVAILABLE = "update directory is unavailable; updates are disabled for this start";
    static final String MSG_DISCOVERY = "live jar could not be located; updates are disabled for this start";
    static final String MSG_INTERNAL = "startup recovery failed; updates are disabled for this start";
    static final String MSG_UNCONFIRMED = "recovery could not confirm durable state";
    static final String MSG_UNREADABLE = "update journal is unreadable; keeping every update file";
    static final String MSG_MISMATCH = "update journal does not belong to this server; keeping every update file";
    static final String MSG_UNKNOWN_LIVE = "live jar matches no known transaction state; keeping every update file";
    static final String MSG_VERSION = "running version or live jar does not match the journal; keeping every update file";
    static final String MSG_BOOT_LIMIT = "new version did not pass the health check twice; keeping every update file";
    static final String MSG_KEPT = "update journal is in recovery-required; keeping every update file";
    static final String MSG_MANUAL_RESTORE = "backup was restored by hand; staged update discarded, backup kept";
    static final String MSG_UNEXPECTED_STAGING = "unexpected entry kept in staging: ";
    static final String MSG_HEALTH_UNCONFIRMED = "health result could not be confirmed on disk; the next start re-checks";
    static final String MSG_NOT_INSTALLED = "update was not installed; staging removed";

    /** Largest boot-pending attempt count that still gets another boot (12.11 step 7). */
    private static final int MAX_BOOT_ATTEMPTS_BEFORE_RECOVERY = 2;

    private StartupRecovery() {
    }

    /** Marks whether a file write or delete may have started, so a late failure is never reported as "disabled". */
    private static final class Progress {
        boolean mutated;
    }

    /**
     * Runs startup recovery (docs/updater.md 12.11). Never throws; every failure maps to a status.
     */
    static RecoveryResult run(RecoveryContext ctx) {
        Progress progress = new Progress();
        try {
            return runLocked(Objects.requireNonNull(ctx, "ctx"), progress);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            if (progress.mutated) {
                return new RecoveryResult(RecoveryStatus.RECOVERY_REQUIRED, Optional.empty(),
                        Optional.empty(), List.of(MSG_UNCONFIRMED + "; inspect the update directory by hand"));
            }
            return disabled(MSG_INTERNAL);
        }
    }

    private static RecoveryResult runLocked(RecoveryContext ctx, Progress progress) {
        TransactionLock.TxnLease lease;
        try {
            lease = TransactionLock.tryAcquire(ctx.updateDir());
        } catch (StoreBusyException e) {
            return disabled(MSG_BUSY);
        } catch (IOException | UpdateRejectedException e) {
            return disabled(MSG_UNAVAILABLE);
        }
        try {
            return recover(ctx, progress);
        } finally {
            closeQuietly(lease);
        }
    }

    private static RecoveryResult recover(RecoveryContext ctx, Progress p) {
        UpdateDirectory dir;
        try {
            dir = new UpdateDirectory(ctx.updateDir(), ctx.ops());
            dir.requireOwnedContents();
        } catch (IOException | UpdateRejectedException e) {
            return disabled(MSG_UNAVAILABLE);
        }

        LiveJar live;
        try {
            live = ctx.discovery().discover();
        } catch (IOException | UpdateRejectedException e) {
            return disabled(MSG_DISCOVERY);
        }

        // Step 1 done. Remove a stale journal.json.tmp left by a crash during writeJournal.
        try {
            p.mutated = true;
            dir.deleteJournalTmp();
        } catch (IOException e) {
            return required(dir, live, Optional.empty(), MSG_UNCONFIRMED);
        }

        Optional<JournalRecord> journal;
        try {
            journal = dir.readJournal();
        } catch (IOException | UpdateRejectedException | RuntimeException e) {
            return required(dir, live, Optional.empty(), MSG_UNREADABLE);
        }

        // Step 2: no journal, so staging entries cannot be matched to a transaction and the
        // transaction's staged jar is safe to drop. Backups are left for the health pass.
        if (journal.isEmpty()) {
            return recoverWithoutJournal(dir, live, p);
        }

        JournalRecord j = journal.get();
        // Step 3: the journal must describe this platform and this exact live jar.
        if (!j.platform().equals(ctx.identity().platform())
                || !j.live().path().equals(live.realPath().toString())) {
            return required(dir, live, journal, MSG_MISMATCH);
        }

        // Step 4, checked before any phase: the owner restored the backup by hand.
        if (live.sha512().equals(j.backupSha512()) && !j.backupSha512().equals(j.staged().sha512())) {
            p.mutated = true;
            try {
                dir.deleteStaging(j.txid());
                dir.deleteJournal();
            } catch (IOException e) {
                return required(dir, live, journal, MSG_UNCONFIRMED);
            }
            String message = j.phase() == JournalPhase.PREPARED ? MSG_NOT_INSTALLED : MSG_MANUAL_RESTORE;
            return new RecoveryResult(RecoveryStatus.IDLE, Optional.empty(), Optional.of(live), List.of(message));
        }

        return switch (j.phase()) {
            case PREPARED -> recoverPrepared(ctx, dir, live, j, p);
            case SWAPPED -> recoverSwapped(ctx, dir, live, j, p);
            case BOOT_PENDING -> recoverBootPending(ctx, dir, live, j, p);
            case RECOVERY_REQUIRED -> required(dir, live, journal, MSG_KEPT);
        };
    }

    /** Step 2: staging entries of no known transaction are deleted; anything unexpected is kept and reported. */
    private static RecoveryResult recoverWithoutJournal(UpdateDirectory dir, LiveJar live, Progress p) {
        List<String> messages = new ArrayList<>();
        try {
            for (Path unexpected : dir.unexpectedStagingEntries()) {
                messages.add(MSG_UNEXPECTED_STAGING + unexpected);
            }
            for (String txid : dir.stagingTxids()) {
                p.mutated = true;
                dir.deleteStaging(txid);
            }
        } catch (IOException e) {
            return required(dir, live, Optional.empty(), MSG_UNCONFIRMED);
        }
        return new RecoveryResult(RecoveryStatus.IDLE, Optional.empty(), Optional.of(live), messages);
    }

    /** Step 5 (PREPARED): either the swap never happened, or it did and the journal was not advanced. */
    private static RecoveryResult recoverPrepared(RecoveryContext ctx, UpdateDirectory dir, LiveJar live,
            JournalRecord j, Progress p) {
        if (live.sha512().equals(j.live().sha512())) {
            p.mutated = true;
            try {
                dir.deleteStaging(j.txid());
                dir.deleteJournal();
            } catch (IOException e) {
                return required(dir, live, Optional.of(j), MSG_UNCONFIRMED);
            }
            return new RecoveryResult(RecoveryStatus.IDLE, Optional.empty(), Optional.of(live),
                    List.of(MSG_NOT_INSTALLED));
        }
        if (live.sha512().equals(j.staged().sha512())) {
            return recoverSwapped(ctx, dir, live, j, p);
        }
        return required(dir, live, Optional.of(j), MSG_UNKNOWN_LIVE);
    }

    /** Step 6 (SWAPPED): the new version runs on the staged jar, so boot-pending starts with attempt 1. */
    private static RecoveryResult recoverSwapped(RecoveryContext ctx, UpdateDirectory dir, LiveJar live,
            JournalRecord j, Progress p) {
        if (!runsStagedJar(ctx, live, j)) {
            return required(dir, live, Optional.of(j), MSG_VERSION);
        }
        JournalRecord next = j.withPhase(JournalPhase.BOOT_PENDING).withBootAttempts(1);
        return writeAndHealthPending(dir, live, j, next, p);
    }

    /** Step 7 (BOOT_PENDING): the previous boot of this generation did not pass the health check. */
    private static RecoveryResult recoverBootPending(RecoveryContext ctx, UpdateDirectory dir, LiveJar live,
            JournalRecord j, Progress p) {
        if (!runsStagedJar(ctx, live, j)) {
            return required(dir, live, Optional.of(j), MSG_VERSION);
        }
        if (j.bootAttempts() >= MAX_BOOT_ATTEMPTS_BEFORE_RECOVERY) {
            return required(dir, live, Optional.of(j), MSG_BOOT_LIMIT);
        }
        JournalRecord next = j.withBootAttempts(j.bootAttempts() + 1);
        return writeAndHealthPending(dir, live, j, next, p);
    }

    private static boolean runsStagedJar(RecoveryContext ctx, LiveJar live, JournalRecord j) {
        return ctx.identity().runningVersion().equals(j.staged().version())
                && live.sha512().equals(j.staged().sha512());
    }

    /** Writes the next journal state durably before reporting HEALTH_PENDING. Any failure is "unconfirmed". */
    private static RecoveryResult writeAndHealthPending(UpdateDirectory dir, LiveJar live, JournalRecord previous,
            JournalRecord next, Progress p) {
        p.mutated = true;
        try {
            dir.writeJournal(next);
        } catch (IOException | RuntimeException e) {
            return required(dir, live, Optional.of(previous), MSG_UNCONFIRMED);
        }
        return new RecoveryResult(RecoveryStatus.HEALTH_PENDING, Optional.of(new RecoveryTicket(next.txid())),
                Optional.of(live), List.of());
    }

    /**
     * Step 8: RECOVERY_REQUIRED. Keeps every file. The message carries the backup path and the manual
     * restore command when the journal is known.
     */
    private static RecoveryResult required(UpdateDirectory dir, LiveJar live, Optional<JournalRecord> journal,
            String reason) {
        StringBuilder text = new StringBuilder("WARN: update recovery required; no new updates until resolved: ")
                .append(reason).append(". ");
        if (journal.isPresent()) {
            Path backup = dir.backupJar(journal.get().txid());
            text.append("Manual restore: stop the server, copy ").append(backup)
                    .append(" over ").append(live.realPath()).append(", start it.");
        } else {
            text.append("Inspect the update directory by hand before starting again.");
        }
        return new RecoveryResult(RecoveryStatus.RECOVERY_REQUIRED, Optional.empty(), Optional.of(live),
                List.of(text.toString()));
    }

    private static RecoveryResult disabled(String message) {
        return new RecoveryResult(RecoveryStatus.DISABLED, Optional.empty(), Optional.empty(), List.of(message));
    }

    private static void closeQuietly(TransactionLock.TxnLease lease) {
        try {
            lease.close();
        } catch (IOException e) {
            // The lock is released in-process before the OS lock; the OS lock ends with the process.
        }
    }


    /** Delay between attempts to take {@code txn.lock} when it is busy at health completion. */
    static final Duration HEALTH_LOCK_RETRY_DELAY = Duration.ofSeconds(1);
    /** Attempts to take {@code txn.lock} at health completion before the stage fails. */
    static final int HEALTH_LOCK_ATTEMPTS = 3;

    /**
     * Waits for the health verdict and then records it (docs/updater.md 12.10 step 7). No lock is held
     * while waiting.
     *
     * <p><b>Thread model.</b> The recording step takes a file lock and does file I/O, so it runs on
     * {@code executor}. The caller must pass a platform worker executor, never the server's main thread.
     * A retry is scheduled on the same executor after {@link #HEALTH_LOCK_RETRY_DELAY}; the JDK delay thread
     * only schedules and never blocks.
     *
     * <p><b>Busy lock.</b> {@code txn.lock} is tried up to {@link #HEALTH_LOCK_ATTEMPTS} times, one second
     * apart. If every attempt fails, the stage completes exceptionally with the lock's
     * {@link UpdateRejectedException}. The caller may retry later. Meanwhile the journal stays
     * {@code BOOT_PENDING}, so the next start counts this boot as attempt two.
     *
     * <p>The stage always completes. Outcomes: {@link HealthCompletion#HEALTHY} deletes the journal and the
     * backups of earlier transactions only; {@link HealthCompletion#FAILED} and {@link HealthCompletion#TIMED_OUT}
     * keep both; {@link HealthCompletion#JOURNAL_CHANGED} touches nothing. A signal that completes
     * exceptionally with {@link TimeoutException} counts as a timeout; any other exceptional or {@code false}
     * result counts as failed. Every other failure completes the stage exceptionally with a fixed-message
     * {@link UpdateRejectedException}.
     *
     * @throws NullPointerException on a null argument, and {@link IllegalArgumentException} for a
     *     timeout that is not positive (both are programming errors, raised synchronously)
     */
    static CompletionStage<HealthCompletion> completeHealth(RecoveryContext ctx, RecoveryTicket ticket,
            HealthSignal signal, Duration timeout, Executor executor) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(executor, "executor");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        CompletableFuture<Boolean> bounded = new CompletableFuture<>();
        try {
            signal.awaitHealthy(timeout).whenComplete((healthy, error) -> {
                if (error != null) {
                    bounded.completeExceptionally(error);
                } else {
                    bounded.complete(healthy);
                }
            });
        } catch (RuntimeException e) {
            bounded.completeExceptionally(e);
        }
        bounded.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);

        CompletableFuture<HealthCompletion> out = new CompletableFuture<>();
        bounded.whenComplete((healthy, error) -> {
            HealthCompletion verdict = verdictOf(healthy, error);
            try {
                executor.execute(() -> attemptHealth(ctx, ticket, verdict, executor, out, 1));
            } catch (RuntimeException e) {
                out.completeExceptionally(new UpdateRejectedException(MSG_HEALTH_UNCONFIRMED));
            }
        });
        return out;
    }

    /** One attempt to record the verdict. Always completes {@code out}; rethrows a VirtualMachineError only after that. */
    private static void attemptHealth(RecoveryContext ctx, RecoveryTicket ticket, HealthCompletion verdict,
            Executor executor, CompletableFuture<HealthCompletion> out, int attempt) {
        try {
            TransactionLock.TxnLease lease;
            try {
                lease = TransactionLock.tryAcquire(ctx.updateDir());
            } catch (UpdateRejectedException busy) {
                if (attempt < HEALTH_LOCK_ATTEMPTS) {
                    CompletableFuture.delayedExecutor(HEALTH_LOCK_RETRY_DELAY.toNanos(), TimeUnit.NANOSECONDS,
                            executor).execute(() -> attemptHealth(ctx, ticket, verdict, executor, out, attempt + 1));
                } else {
                    out.completeExceptionally(busy);
                }
                return;
            }
            out.complete(recordHealth(ctx, ticket, verdict, lease));
        } catch (UpdateRejectedException e) {
            out.completeExceptionally(e);
        } catch (Throwable t) {
            out.completeExceptionally(new UpdateRejectedException(MSG_HEALTH_UNCONFIRMED));
            if (t instanceof VirtualMachineError) {
                throw (VirtualMachineError) t;
            }
        }
    }

    /** Records the verdict under the lease. The lease is closed on every path. */
    private static HealthCompletion recordHealth(RecoveryContext ctx, RecoveryTicket ticket,
            HealthCompletion verdict, TransactionLock.TxnLease lease) throws IOException, UpdateRejectedException {
        HealthCompletion result;
        try {
            result = recordUnderLease(ctx, ticket, verdict);
        } catch (Throwable t) {
            closeQuietly(lease);
            throw t;
        }
        if (result == HealthCompletion.HEALTHY) {
            closeQuietly(lease);
        } else {
            lease.close();
        }
        return result;
    }

    private static HealthCompletion recordUnderLease(RecoveryContext ctx, RecoveryTicket ticket,
            HealthCompletion verdict) throws IOException, UpdateRejectedException {
        UpdateDirectory dir = new UpdateDirectory(ctx.updateDir(), ctx.ops());
        dir.requireOwnedContents();
        Optional<JournalRecord> journal = dir.readJournal();
        if (journal.isEmpty() || !journal.get().txid().equals(ticket.txid())
                || journal.get().phase() != JournalPhase.BOOT_PENDING) {
            return HealthCompletion.JOURNAL_CHANGED;
        }
        if (verdict != HealthCompletion.HEALTHY) {
            return verdict;
        }
        dir.deleteStaging(ticket.txid());
        dir.deleteJournal();
        pruneEarlierBackups(dir, ticket.txid());
        return HealthCompletion.HEALTHY;
    }

    private static HealthCompletion verdictOf(Boolean healthy, Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            return cause instanceof TimeoutException ? HealthCompletion.TIMED_OUT : HealthCompletion.FAILED;
        }
        return Boolean.TRUE.equals(healthy) ? HealthCompletion.HEALTHY : HealthCompletion.FAILED;
    }

    /**
     * Deletes backups of earlier transactions. The journal is already gone, so a failure here keeps the
     * remaining backups; a later transaction's health pass prunes them again.
     */
    private static void pruneEarlierBackups(UpdateDirectory dir, String currentTxid) {
        try {
            for (String txid : dir.backupTxids()) {
                if (!txid.equals(currentTxid)) {
                    dir.deleteBackup(txid);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Keep the files. The next healthy transaction prunes every earlier backup.
        }
    }
}
