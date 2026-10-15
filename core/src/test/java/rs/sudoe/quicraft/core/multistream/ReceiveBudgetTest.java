// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The node-level receive budget of docs/protocol.md 13.12. */
class ReceiveBudgetTest {
    @Test
    void theUnlimitedBudgetAdmitsEverythingAndReservesNothing() {
        ReceiveBudget budget = ReceiveBudget.unlimited();
        assertTrue(budget.tryReserve(0));
        assertTrue(budget.tryReserve(Long.MAX_VALUE / 2));
        budget.release(Long.MAX_VALUE / 2);
        assertEquals(0, budget.reserved());
    }

    @Test
    void theBoundedBudgetAdmitsUpToItsCapacityAndThenRefuses() {
        ReceiveBudget budget = ReceiveBudget.bounded(100);
        assertEquals(0, budget.reserved());
        assertTrue(budget.tryReserve(60));
        assertTrue(budget.tryReserve(40));
        assertEquals(100, budget.reserved());
        assertFalse(budget.tryReserve(1), "the budget is full");
        assertEquals(100, budget.reserved());
        budget.release(40);
        assertEquals(60, budget.reserved());
        assertTrue(budget.tryReserve(40));
        assertFalse(budget.tryReserve(41));
        budget.release(100);
        assertEquals(0, budget.reserved());
    }

    @Test
    void aZeroByteReservationIsAlwaysAdmitted() {
        ReceiveBudget full = ReceiveBudget.bounded(0);
        assertTrue(full.tryReserve(0));
        assertFalse(full.tryReserve(1));
        assertEquals(0, full.reserved());
    }

    @Test
    void releasingMoreThanIsReservedIsAProgrammingError() {
        ReceiveBudget budget = ReceiveBudget.bounded(100);
        assertThrows(IllegalStateException.class, () -> budget.release(1));
        budget.tryReserve(10);
        assertThrows(IllegalStateException.class, () -> budget.release(11));
        assertEquals(10, budget.reserved());
        budget.release(10);
        assertEquals(0, budget.reserved());
    }

    @Test
    void negativeCountsAndCapacitiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReceiveBudget.bounded(-1));
        ReceiveBudget budget = ReceiveBudget.bounded(10);
        assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
    }

    @Test
    void manyConnectionsReservingAtOnceNeverOvercommitTheBudget() throws Exception {
        final int capacity = 4096;
        final int threads = 8;
        ReceiveBudget budget = ReceiveBudget.bounded(capacity);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch allReserved = new CountDownLatch(threads);
        final AtomicInteger admitted = new AtomicInteger();
        final List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                final int[] held = new int[1];
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 20000; n++) {
                    if (budget.tryReserve(4)) {
                        held[0] += 4;
                        admitted.incrementAndGet();
                    }
                }
                // Nobody releases before every thread has taken all it can, so the admitted
                // total is the budget's high-water mark.
                allReserved.countDown();
                try {
                    allReserved.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                budget.release(held[0]);
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(0, budget.reserved(), "every reservation is released again");
        assertTrue(admitted.get() * 4L <= capacity, "admitted " + admitted.get() * 4L + " bytes");
    }
}
