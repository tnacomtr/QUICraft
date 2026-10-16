// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * Acknowledgements on acceptance with the not-yet-run work bounded and accounted (docs/protocol.md
 * 13.10, 13.12): the main thread is a queue the tests run by hand.
 */
class AckBacklogTest {
    private final ArrayDeque<Runnable> mainThread = new ArrayDeque<>();
    private final List<Long> acked = new ArrayList<>();
    private boolean accepting = true;

    private AckBacklog backlog(ReceiveBudget node, long maxPackets, long maxBytes) {
        return new AckBacklog((lane, sequence) -> acked.add(sequence), task -> {
            if (!accepting) {
                return false;
            }
            mainThread.add(task);
            return true;
        }, node, maxPackets, maxBytes);
    }

    private void runMainThread() {
        while (!mainThread.isEmpty()) {
            mainThread.poll().run();
        }
    }

    @Test
    void manyDeliveriesAreAcknowledgedWhileTheMainThreadIsIdle() {
        AckBacklog backlog = backlog(ReceiveBudget.unlimited(), AckBacklog.MAX_PACKETS, AckBacklog.MAX_BYTES);

        for (long sequence = 1; sequence <= 1000; sequence++) {
            backlog.delivered(Lane.CONTROL, sequence, 1, 100);
        }

        assertEquals(1000, acked.size(), "all acknowledged before the main thread ran a single handler");
        assertEquals(1000, mainThread.size(), "the work is still queued, and accounted");
        assertEquals(1000, backlog.pendingPackets());
        assertEquals(100_000, backlog.pendingBytes());
        for (int i = 0; i < acked.size(); i++) {
            assertEquals(i + 1, acked.get(i), "in order");
        }
    }

    @Test
    void aBacklogOverTheBoundHoldsTheAckUntilTheMainThreadDrainsIt() {
        AckBacklog backlog = backlog(ReceiveBudget.unlimited(), 3, 1 << 20);

        backlog.delivered(Lane.CONTROL, 1, 1, 10);
        backlog.delivered(Lane.CONTROL, 2, 1, 10);
        backlog.delivered(Lane.CONTROL, 3, 1, 10);
        assertEquals(List.of(1L, 2L, 3L), acked, "at the bound, still acknowledged");

        backlog.delivered(Lane.CONTROL, 4, 1, 10);
        backlog.delivered(Lane.CONTROL, 5, 1, 10);
        assertEquals(List.of(1L, 2L, 3L), acked, "over the bound: 4 and 5 are held, in order");

        mainThread.poll().run(); // one handler ran: 4 pending, still over the bound of 3
        assertEquals(List.of(1L, 2L, 3L), acked);
        mainThread.poll().run(); // 3 pending: under the bound again
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), acked, "draining releases the held acks, in order");
        runMainThread();
        assertEquals(0, backlog.pendingPackets());
    }

    @Test
    void theByteBoundHoldsToo() {
        AckBacklog backlog = backlog(ReceiveBudget.unlimited(), 1000, 100);

        backlog.delivered(Lane.CONTROL, 1, 1, 80);
        backlog.delivered(Lane.CONTROL, 2, 1, 80);

        assertEquals(List.of(1L), acked, "160 bytes over a 100 byte bound: the second waits");
        mainThread.poll().run();
        assertEquals(List.of(1L, 2L), acked);
    }

    @Test
    void aListenerThatIsNotAcceptingHoldsTheAckInsteadOfDroppingIt() {
        AckBacklog backlog = backlog(ReceiveBudget.unlimited(), AckBacklog.MAX_PACKETS, AckBacklog.MAX_BYTES);
        accepting = false;

        backlog.delivered(Lane.CONTROL, 1, 1, 10);
        backlog.delivered(Lane.CONTROL, 2, 1, 10);

        assertEquals(List.of(), acked, "held, not acknowledged and not lost");
        assertTrue(backlog.hasBlocked());
        backlog.retry();
        assertEquals(List.of(), acked, "still not accepting");

        accepting = true;
        backlog.retry();
        assertEquals(List.of(1L, 2L), acked, "released in order once the listener accepts");
        assertFalse(backlog.hasBlocked());
        assertEquals(2, mainThread.size(), "and the markers are queued now");
    }

    @Test
    void acceptedBytesAreReservedAgainstTheNodeBudgetAndReturnedWhenTheyRun() {
        ReceiveBudget node = ReceiveBudget.bounded(1000);
        AckBacklog backlog = backlog(node, AckBacklog.MAX_PACKETS, AckBacklog.MAX_BYTES);

        backlog.delivered(Lane.CONTROL, 1, 1, 400);
        backlog.delivered(Lane.CONTROL, 2, 1, 400);
        assertEquals(800, node.reserved());
        assertThrows(IllegalStateException.class, () -> backlog.delivered(Lane.CONTROL, 3, 1, 400),
                "a delivery the node cannot carry fails the connection (13.12)");

        mainThread.poll().run();
        assertEquals(400, node.reserved());
        runMainThread();
        assertEquals(0, node.reserved());
    }

    @Test
    void teardownReturnsEveryByteWhetherOrNotTheMainThreadEverRan() {
        ReceiveBudget node = ReceiveBudget.bounded(10_000);
        AckBacklog backlog = backlog(node, 1, 1 << 20);
        backlog.delivered(Lane.CONTROL, 1, 1, 300);
        backlog.delivered(Lane.CONTROL, 2, 1, 300);
        backlog.delivered(Lane.CONTROL, 3, 1, 300);
        mainThread.poll().run();
        assertEquals(600, node.reserved());

        backlog.teardown();
        assertEquals(0, node.reserved());
        runMainThread(); // markers that run after teardown must not release again
        assertEquals(0, node.reserved());
    }
}
