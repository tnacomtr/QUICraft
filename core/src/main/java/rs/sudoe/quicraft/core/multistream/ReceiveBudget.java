// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A node-level receive budget (docs/protocol.md 13.12): the configured aggregate pending-bytes
 * budget that every connection of one listener or node shares.
 *
 * <p>13.12 requires the node to "reserve each admitted connection's initial receive-credit exposure
 * against that budget", to "debit further credit/allocations before granting them" and to "release
 * reservations only when credit exposure or retained bytes are gone", so that "no allocation or
 * advertised receive credit may overcommit this node budget". A receiver therefore debits this
 * budget for every byte it retains (parser buffers, staged bundles, decoded output and unparsed
 * transport chunks) and credits it back when those bytes are consumed or released.</p>
 *
 * <p>Thread-safe: connections on different event loops share one instance, so every operation is a
 * single atomic step. {@link #tryReserve(long)} never blocks and never evicts: a refusal means the
 * caller must not take the bytes, exactly like the per-connection budget, and the caller either
 * withholds the credit that would have carried them or, when the bytes have already arrived and can
 * never fit, closes that one connection with {@code 0x11} (13.12) rather than evicting another
 * connection's data.</p>
 *
 * <p>A release must always match an earlier reservation of the same instance: releasing more than
 * is reserved is a programming error and throws {@link IllegalStateException}.</p>
 */
public abstract class ReceiveBudget {
    /** Shared instance for callers that enforce no node budget; never accounts anything. */
    private static final ReceiveBudget UNLIMITED = new ReceiveBudget() {
        @Override
        public boolean tryReserve(long bytes) {
            require(bytes);
            return true;
        }

        @Override
        public void release(long bytes) {
            require(bytes);
        }

        @Override
        public long reserved() {
            return 0;
        }

        @Override
        public long available() {
            return Long.MAX_VALUE;
        }
    };

    /**
     * Abstract: construct with {@link #unlimited()} or {@link #bounded(long)}. Package-private so a
     * test can supply a budget that records or deviates from the accounting.
     */
    ReceiveBudget() {}

    /** The budget that admits every reservation; {@link #reserved()} stays zero. */
    public static ReceiveBudget unlimited() {
        return UNLIMITED;
    }

    /**
     * A budget of {@code capacity} bytes shared by every connection of the node, with a dedicated
     * configuration of 0 disabling all further admissions by refusing every reservation.
     */
    public static ReceiveBudget bounded(long capacity) {
        return new Bounded(capacity);
    }

    /**
     * Reserves {@code bytes} when the budget can still carry them.
     *
     * @return true when the bytes are now reserved, false when the budget is exhausted; the caller
     *     must not retain the bytes in that case
     */
    public abstract boolean tryReserve(long bytes);

    /**
     * Releases {@code bytes} of an earlier reservation once the retained bytes or the credit
     * exposure they paid for are gone.
     *
     * @throws IllegalStateException when more is released than the budget currently reserves
     */
    public abstract void release(long bytes);

    /** The bytes currently reserved against this budget. */
    public abstract long reserved();

    /**
     * Query only: the bytes this budget could still reserve without exceeding its capacity, never
     * negative. {@link #unlimited()} reports {@link Long#MAX_VALUE}. Unlike a
     * {@link #tryReserve(long)} followed by {@link #release(long)}, it changes nothing, so a probe
     * can never make another connection's real reservation fail.
     */
    public abstract long available();

    private static void require(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative byte count: " + bytes);
        }
    }

    /** The bounded implementation: one atomic reservation counter shared by all connections. */
    private static final class Bounded extends ReceiveBudget {
        private final long capacity;
        private final AtomicLong reserved = new AtomicLong();

        Bounded(long capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("negative capacity: " + capacity);
            }
            this.capacity = capacity;
        }

        @Override
        public boolean tryReserve(long bytes) {
            require(bytes);
            while (true) {
                long current = reserved.get();
                long next = current + bytes;
                if (next < current || next > capacity) {
                    return false;
                }
                if (reserved.compareAndSet(current, next)) {
                    return true;
                }
            }
        }

        @Override
        public void release(long bytes) {
            require(bytes);
            while (true) {
                long current = reserved.get();
                long next = current - bytes;
                if (next < 0) {
                    throw new IllegalStateException(
                            "released " + bytes + " of " + current + " reserved bytes");
                }
                if (reserved.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        @Override
        public long reserved() {
            return reserved.get();
        }

        @Override
        public long available() {
            long free = capacity - reserved.get();
            return free < 0 ? 0 : free;
        }
    }
}
