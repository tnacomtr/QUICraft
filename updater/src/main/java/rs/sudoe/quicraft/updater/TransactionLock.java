// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The transaction lock {@code txn.lock} (docs/updater.md 12.9): held for a whole update transaction
 * and by startup recovery.
 *
 * <p><b>Lock order: txn before state.</b> A thread may take this lock and then state locks (through
 * {@link StateStore#withLockedSnapshot} or a commit). A thread that already holds a state lock must
 * not take this lock; that raises {@link IllegalStateException}.
 *
 * <p>In-process exclusion uses a per-directory {@link Semaphore} acquired with {@code tryAcquire},
 * not a {@link java.util.concurrent.locks.ReentrantLock}: a same-thread {@code tryLock} on a
 * reentrant lock would succeed, and a second acquire on one thread must be refused. The OS lock
 * ({@link FileChannel#tryLock}) then excludes other processes. Neither waits.
 */
public final class TransactionLock {

    private static final ConcurrentHashMap<Path, Semaphore> IN_PROCESS = new ConcurrentHashMap<>();

    private TransactionLock() {
    }

    /** An acquired transaction lock. Closing it releases both the OS lock and the in-process guard. */
    public interface TxnLease extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    /**
     * Acquires {@code txn.lock} in {@code updateDir} without waiting.
     *
     * @throws UpdateRejectedException "an update transaction is already active" when this JVM already
     *     holds it
     * @throws StoreBusyException when another process holds it
     * @throws IllegalStateException when the calling thread holds a state lock
     */
    public static TxnLease tryAcquire(Path updateDir) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(updateDir, "updateDir");
        if (StateStore.holdsStateLock()) {
            throw new IllegalStateException("txn lock must be taken before the state lock");
        }
        Path dir = updateDir.toAbsolutePath().normalize();
        StateStore.prepareDirectory(dir);
        Semaphore guard = IN_PROCESS.computeIfAbsent(dir.toRealPath(), k -> new Semaphore(1));
        if (!guard.tryAcquire()) {
            throw new UpdateRejectedException("an update transaction is already active");
        }
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = StateStore.openLock(dir, StateStore.TXN_LOCK);
            FileLock osLock;
            try {
                osLock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new StoreBusyException();
            }
            if (osLock == null) {
                throw new StoreBusyException();
            }
            acquired = true;
            return new Lease(channel, osLock, guard);
        } finally {
            if (!acquired) {
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } finally {
                    guard.release();
                }
            }
        }
    }

    static final class Lease implements TxnLease {
        private final FileChannel channel;
        private final FileLock osLock;
        private final Semaphore guard;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Lease(FileChannel channel, FileLock osLock, Semaphore guard) {
            this.channel = channel;
            this.osLock = osLock;
            this.guard = guard;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                try {
                    osLock.release();
                } finally {
                    channel.close();
                }
            } finally {
                guard.release();
            }
        }
    }
}
