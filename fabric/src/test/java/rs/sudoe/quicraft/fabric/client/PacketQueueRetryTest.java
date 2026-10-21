// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class PacketQueueRetryTest {
    @Test void refusalBacksOffAndNeverRunsMarkerOnTheRetryExecutor() {
        ArrayDeque<Runnable> retries = new ArrayDeque<>();
        ArrayDeque<Runnable> game = new ArrayDeque<>();
        List<Long> delays = new ArrayList<>();
        boolean[] accepting = {false};
        int[] ran = {0};
        Runnable marker = () -> ran[0]++;
        new PacketQueueRetry(marker, () -> true, task -> {
            assertSame(marker, task);
            if (!accepting[0]) return false;
            game.add(task);
            return true;
        }, (task, delay) -> { retries.add(task); delays.add(delay); }, error -> fail(error)).start();
        for (int i = 0; i < 12; i++) retries.remove().run();
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 32L, 64L, 100L, 100L, 100L, 100L, 100L, 100L), delays);
        assertEquals(0, ran[0]);
        accepting[0] = true;
        retries.remove().run();
        assertTrue(retries.isEmpty());
        assertEquals(0, ran[0], "acceptance only queues; it does not execute the marker");
        game.remove().run();
        assertEquals(1, ran[0]);
    }

    @Test void closeStopsRetriesWithoutOfferingOrApplying() {
        ArrayDeque<Runnable> retries = new ArrayDeque<>();
        boolean[] open = {true};
        new PacketQueueRetry(() -> fail("marker ran"), () -> open[0], task -> {
            fail("offered after close"); return false;
        }, (task, delay) -> retries.add(task), error -> fail(error)).start();
        open[0] = false;
        retries.remove().run();
        assertTrue(retries.isEmpty());
    }

    @Test void executorShutdownDoesNotRetryOnAnotherQueue() {
        ArrayDeque<Runnable> retries = new ArrayDeque<>();
        new PacketQueueRetry(() -> fail("marker ran"), () -> true, task -> {
            throw new RejectedExecutionException();
        }, (task, delay) -> retries.add(task), error -> fail(error)).start();
        retries.remove().run();
        assertTrue(retries.isEmpty());
    }
}
