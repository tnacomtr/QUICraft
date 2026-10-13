// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/** Tests for {@link TransactionLock} and its lock order against the state lock. */
class TransactionLockTest {

    @TempDir
    Path tmp;

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);

    private Path dir() throws Exception {
        return StateStoreTest.privateDirFor(tmp.resolve("upd"));
    }

    private static StateDelta seed() {
        UpdaterState s = new UpdaterState(1, HEX_A, HEX_B,
                new CurrentRevocations(0, "", null, null, 0),
                Map.of(), List.of(), "", List.of(), Map.of());
        return new StateDelta(s, RevokedState.empty());
    }

    @Test
    void secondAcquireInSameJvmIsRefusedUntilClosed() throws Exception {
        Path dir = dir();
        TransactionLock.TxnLease first = TransactionLock.tryAcquire(dir);
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> TransactionLock.tryAcquire(dir));
        assertEquals("an update transaction is already active", e.getMessage());
        first.close();
        TransactionLock.TxnLease again = TransactionLock.tryAcquire(dir);
        again.close();
        again.close();
    }

    @Test
    void lockHeldByAnotherProcessIsRefused() throws Exception {
        Path dir = dir();
        StateStoreTest.writePrivate(dir.resolve(StateStore.TXN_LOCK), new byte[0]);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolderMain.class.getName(), dir.toString(), StateStore.TXN_LOCK)
                .redirectErrorStream(true).start();
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", out.readLine());
            assertThrows(StoreBusyException.class, () -> TransactionLock.tryAcquire(dir));
        } finally {
            child.getOutputStream().close();
            child.waitFor();
        }
        TransactionLock.tryAcquire(dir).close();
    }

    @Test
    void stateLockHeldThenTxnIsRefused() throws Exception {
        Path dir = dir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seed());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.withLockedSnapshot((snapshot) -> TransactionLock.tryAcquire(dir)));
        assertEquals("txn lock must be taken before the state lock", e.getMessage());
        // The failed attempt released its state lock, so the store still works.
        assertTrue(store.load().isPresent());
    }

    @Test
    void txnThenStateLockIsAllowed() throws Exception {
        Path dir = dir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seed());
        TransactionLock.TxnLease lease = TransactionLock.tryAcquire(dir);
        try {
            StateRecords.Snapshot seen = store.withLockedSnapshot(snapshot -> snapshot);
            assertEquals(store.load().orElseThrow(), seen);
        } finally {
            lease.close();
        }
    }

    @Test
    void guardIsFreedAndChannelClosedWhenOsLockReleaseFails() throws Exception {
        Path file = tmp.resolve("release-fails.lock");
        Files.write(file, new byte[1]);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock failing = new FileLock(channel, 0, 1, false) {
            @Override
            public void release() throws IOException {
                throw new IOException("release failed");
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        Semaphore guard = new Semaphore(0);
        TransactionLock.Lease lease = new TransactionLock.Lease(channel, failing, guard);
        assertThrows(IOException.class, lease::close);
        assertFalse(channel.isOpen());
        assertEquals(1, guard.availablePermits());
    }
}
