// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Event-loop confined retry of a refused FIFO marker. Never runs the marker on another executor. */
final class PacketQueueRetry {
    static final long MAX_DELAY_MILLIS = 100;
    private final Runnable marker;
    private final BooleanSupplier open;
    private final Predicate<Runnable> offer;
    private final BiConsumer<Runnable, Long> schedule;
    private final Consumer<Throwable> failed;
    private long delay = 1;

    PacketQueueRetry(Runnable marker, BooleanSupplier open, Predicate<Runnable> offer,
                     BiConsumer<Runnable, Long> schedule, Consumer<Throwable> failed) {
        this.marker = marker;
        this.open = open;
        this.offer = offer;
        this.schedule = schedule;
        this.failed = failed;
    }

    void start() {
        try {
            if (!open.getAsBoolean()) return;
            long next = delay;
            delay = Math.min(MAX_DELAY_MILLIS, delay * 2);
            schedule.accept(this::retry, next);
        } catch (RejectedExecutionException closing) {
            // The game/event loop is shutting down; no acknowledgement is due.
        } catch (Throwable error) {
            failed.accept(error);
        }
    }

    private void retry() {
        try {
            if (!open.getAsBoolean() || offer.test(marker)) return;
            start();
        } catch (RejectedExecutionException closing) {
            // No alternate executor, and no retry after shutdown.
        } catch (Throwable error) {
            failed.accept(error);
        }
    }
}
