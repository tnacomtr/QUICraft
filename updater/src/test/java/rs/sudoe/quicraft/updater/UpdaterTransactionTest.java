// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transaction skeleton: the active slot and the cancel state machine (docs/updater.md 12.9). */
class UpdaterTransactionTest {

    @TempDir
    Path tmp;

    /** A transaction over the real store, pipeline and directory, with nothing running yet. */
    private UpdaterTransaction newTransaction() throws Exception {
        Path dir = PipelineFixtures.privateDir(tmp.resolve("upd"));
        StateStore store = new StateStore(dir);
        CheckPipeline pipeline = new CheckPipeline(store,
                PipelineFixtures.collector(new PipelineFixtures.ScriptedFetcher()),
                PipelineFixtures.HOSTS, PipelineFixtures.identity(), PipelineFixtures.CLOCK);
        return new UpdaterTransaction(dir, DurabilityOps.system(), store, pipeline,
                new PipelineFixtures.ScriptedFetcher(), PipelineFixtures.ENDPOINTS, Map.of(),
                FetchTimeouts.defaults(), () -> {
                    throw new IllegalStateException("discovery not used by the skeleton");
                }, () -> true, PipelineFixtures.CLOCK);
    }

    @Test
    void cancelWithNoActiveTransactionAnswersNoActiveAndDeletesNothing() throws Exception {
        UpdaterTransaction txn = newTransaction();
        assertEquals(CancelReply.NO_ACTIVE_TRANSACTION, txn.cancelActive());
    }

    @Test
    void cancelWhileRunningRequestsAndTripsTheToken() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertFalse(mine.token.isCancelled());
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertTrue(mine.token.isCancelled());
        assertEquals(UpdaterTransaction.Phase.CANCEL_REQUESTED, mine.phase.get());
    }

    @Test
    void repeatedCancelWhileCancelRequestedIsIdempotent() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertTrue(mine.token.isCancelled());
    }

    @Test
    void cancelAfterSwapStartedAnswersInstalledAndDoesNotCancel() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertTrue(mine.phase.compareAndSet(UpdaterTransaction.Phase.RUNNING,
                UpdaterTransaction.Phase.SWAPPING));
        assertEquals(CancelReply.INSTALLED_RESTART_PENDING, txn.cancelActive());
        assertFalse(mine.token.isCancelled());
        assertEquals(UpdaterTransaction.Phase.SWAPPING, mine.phase.get());
    }

    @Test
    void cancelAfterTheTransactionLeftAnswersNoActive() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        txn.leave(mine);
        assertEquals(CancelReply.NO_ACTIVE_TRANSACTION, txn.cancelActive());
        assertNotNull(txn.tryEnter());
    }

    @Test
    void cancelDoesNotTakeTheTransactionLock() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        // The transaction thread owns txn.lock; cancel must answer without waiting for it.
        try (TransactionLock.TxnLease ignored = TransactionLock.tryAcquire(tmp.resolve("upd"))) {
            assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        }
        assertTrue(mine.token.isCancelled());
    }

    @Test
    void secondEntryIsRefusedWhileOneIsActive() throws Exception {
        UpdaterTransaction txn = newTransaction();
        UpdaterTransaction.Active first = txn.tryEnter();
        assertNotNull(first);
        assertNull(txn.tryEnter());
    }
}
