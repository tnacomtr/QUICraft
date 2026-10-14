// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/** Tests for {@link StateStore#withLockedSnapshot} and its re-entry rule. */
class StateStoreLockingTest {

    @TempDir
    Path tmp;

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);
    private static final String HEX_C = "c".repeat(64);
    private static final String KID_1 = "0123456789abcdef";
    private static final String KID_2 = "fedcba9876543210";

    private Path dir() throws Exception {
        Path dir = StateStoreTest.privateDirFor(tmp.resolve("upd"));
        new StateStore(dir).initialiseIfAbsent(new StateDelta(
                new UpdaterState(1, HEX_A, HEX_B, new CurrentRevocations(0, "", null, null, 0),
                        Map.of(), List.of(), "", List.of(), Map.of()),
                RevokedState.empty()), true);
        return dir;
    }

    private static Snapshot addKid(Snapshot before, String kid, long seq) {
        Map<String, KidSeq> map = new HashMap<>(before.state().seqByKid());
        map.put(kid, new KidSeq(seq, HEX_C, java.time.Instant.parse("2026-11-01T12:00:00Z")));
        UpdaterState s = before.state();
        return new Snapshot(new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(),
                s.revocations(), map, s.withdrawn(), s.lastAnnounced(), s.warnedBad(),
                s.tornSince()), before.revoked());
    }

    private static void awaitQuietly(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IOException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @Test
    void lockedSnapshotSeesEarlierCommit() throws Exception {
        Path dir = dir();
        StateStore store = new StateStore(dir);
        Snapshot committed = store.commit(before -> addKid(before, KID_1, 7));
        Snapshot seen = store.withLockedSnapshot(snapshot -> snapshot);
        assertEquals(committed, seen);
    }

    @Test
    void concurrentCommitBlocksUntilCallbackEnds() throws Exception {
        Path dir = dir();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Snapshot> locked = pool.submit(() -> new StateStore(dir).withLockedSnapshot(
                    snapshot -> {
                        inside.countDown();
                        awaitQuietly(release);
                        return snapshot;
                    }));
            assertTrue(inside.await(30, TimeUnit.SECONDS));
            Future<Snapshot> writer = pool.submit(
                    () -> new StateStore(dir).commit(before -> addKid(before, KID_2, 2)));
            Thread.sleep(300);
            assertFalse(writer.isDone(), "commit must wait for the locked callback");
            release.countDown();
            Snapshot seenByLocked = locked.get(30, TimeUnit.SECONDS);
            assertFalse(seenByLocked.state().seqByKid().containsKey(KID_2));
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertTrue(new StateStore(dir).load().orElseThrow().state().seqByKid().containsKey(KID_2));
    }

    @Test
    void reentryFromCallbackIsRefusedAndStoreRecovers() throws Exception {
        Path dir = dir();
        StateStore store = new StateStore(dir);
        assertThrows(IllegalStateException.class, () -> store.withLockedSnapshot(
                snapshot -> new StateStore(dir).load()));
        assertThrows(IllegalStateException.class, () -> store.withLockedSnapshot(
                snapshot -> store.commit(before -> before)));
        assertThrows(IllegalStateException.class, () -> store.withLockedSnapshot(
                snapshot -> store.withLockedSnapshot(inner -> inner)));
        assertTrue(store.load().isPresent());
    }

    @Test
    void callbackFailureReleasesLocks() throws Exception {
        Path dir = dir();
        StateStore store = new StateStore(dir);
        assertThrows(RuntimeException.class, () -> store.withLockedSnapshot(snapshot -> {
            throw new RuntimeException("boom");
        }));
        assertEquals(1, store.commit(before -> addKid(before, KID_1, 1)).state().seqByKid().size());
    }
}
