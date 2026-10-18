// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.channel.AbstractEventLoop;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Core's (relocated) Netty {@link io.netty.channel.EventLoop} that runs everything on a
 * {@link HostLoop}: the QUIC codec, its connection and stream channels. It does no I/O of its
 * own; {@link HostedDatagramChannel} gets its datagrams from the host's socket.
 *
 * <p>Shutting it down only stops accepting work into this adapter; the host's loop is the
 * platform's and keeps running.
 *
 * <p>Delayed and recurring tasks are chains of host-scheduled runs on the host's thread, never
 * threads of their own. A recurring task follows
 * {@link java.util.concurrent.ScheduledExecutorService}'s semantics: fixed rate keeps its deadline
 * anchored to the schedule (a run that overruns its period makes the next due at once, and the
 * phase never drifts), fixed delay counts from the end of the run, cancelling the returned future
 * stops the chain, and an exception from the run fails the future and suppresses later runs.
 */
final class HostedEventLoop extends AbstractEventLoop {
    private final HostLoop host;
    private final Promise<Void> termination = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);
    private volatile boolean shutdown;

    HostedEventLoop(HostLoop host) {
        this.host = host;
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return host.inEventLoop(thread);
    }

    @Override
    public void execute(Runnable task) {
        // Accepted after shutdown too: closing channels still needs to run its tasks.
        host.execute(task);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(Executors.callable(command, null), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        long delayNanos = unit.toNanos(Math.max(0, delay));
        HostedScheduledFuture<V> future = new HostedScheduledFuture<V>(this, callable,
                System.nanoTime() + delayNanos);
        future.armAt(future.deadlineNanos);
        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }
        return periodic(command, initialDelay, period, unit, true);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
            TimeUnit unit) {
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be positive");
        }
        return periodic(command, initialDelay, delay, unit, false);
    }

    /** A recurring run on the host loop: the run re-arms itself, so there is no thread of its own. */
    private ScheduledFuture<?> periodic(Runnable command, long initialDelay, long interval, TimeUnit unit,
            boolean fixedRate) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        long initialNanos = unit.toNanos(Math.max(0, initialDelay));
        HostedPeriodicFuture future = new HostedPeriodicFuture(this, command,
                System.nanoTime() + initialNanos, unit.toNanos(interval), fixedRate);
        future.armAt(future.deadlineNanos);
        return future;
    }

    @Override
    public ChannelFuture register(Channel channel) {
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) {
        promise.channel().unsafe().register(this, promise);
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        channel.unsafe().register(this, promise);
        return promise;
    }

    @Override
    public boolean isShuttingDown() {
        return shutdown;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        shutdown();
        return termination;
    }

    @Override
    public Future<?> terminationFuture() {
        return termination;
    }

    @Deprecated
    @Override
    public void shutdown() {
        shutdown = true;
        termination.trySuccess(null);
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }

    /**
     * A task scheduled on the host loop, as a Netty {@link ScheduledFuture}: the one-shot
     * {@link HostedScheduledFuture} or the recurring {@link HostedPeriodicFuture}. Both hold a
     * cancellable handle to the host's scheduled task, so cancelling the future stops the work.
     */
    private abstract static class HostedTimedFuture<V> extends DefaultPromise<V>
            implements ScheduledFuture<V>, Runnable {
        private final HostedEventLoop loop;
        /** The scheduled time of the next run; a recurring task re-arms it. */
        volatile long deadlineNanos;
        private volatile HostLoop.Cancellable handle;

        HostedTimedFuture(HostedEventLoop loop, long deadlineNanos) {
            super(loop);
            this.loop = loop;
            this.deadlineNanos = deadlineNanos;
        }

        /** Schedules the next run at {@code deadlineNanos}; a deadline already past runs at once. */
        final void armAt(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
            handle = loop.host.schedule(this, Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            HostLoop.Cancellable h = handle;
            if (h != null) {
                h.cancel();
            }
            return true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    /** A one-shot task on the host loop, as a Netty {@link ScheduledFuture}. */
    private static final class HostedScheduledFuture<V> extends HostedTimedFuture<V> {
        private final Callable<V> task;

        HostedScheduledFuture(HostedEventLoop loop, Callable<V> task, long deadlineNanos) {
            super(loop, deadlineNanos);
            this.task = task;
        }

        @Override
        public void run() {
            if (!setUncancellable()) {
                return;
            }
            try {
                setSuccess(task.call());
            } catch (Throwable t) {
                setFailure(t);
            }
        }
    }

    /**
     * A recurring task on the host loop, as a Netty {@link ScheduledFuture}: the run re-arms itself
     * through {@link HostedTimedFuture#armAt} on the host's thread. Fixed rate advances the anchor
     * by one interval, never by the run's duration (a run that overruns its period makes the next
     * due at once and the phase survives); fixed delay starts from the end of the run. Cancelling
     * the future, from any thread or from inside the run, stops the chain; an exception from the
     * run fails the future and, like {@link java.util.concurrent.ScheduledExecutorService},
     * suppresses every later run.
     */
    private static final class HostedPeriodicFuture extends HostedTimedFuture<Void> {
        private final Runnable command;
        private final long intervalNanos;
        private final boolean fixedRate;

        HostedPeriodicFuture(HostedEventLoop loop, Runnable command, long deadlineNanos, long intervalNanos,
                boolean fixedRate) {
            super(loop, deadlineNanos);
            this.command = command;
            this.intervalNanos = intervalNanos;
            this.fixedRate = fixedRate;
        }

        @Override
        public void run() {
            // A cancel that landed between arming and this run stops the chain here.
            if (isCancelled() || isDone()) {
                return;
            }
            try {
                command.run();
            } catch (Throwable t) {
                setFailure(t);
                return;
            }
            if (isCancelled() || isDone()) {
                return;
            }
            long now = System.nanoTime();
            long next;
            if (fixedRate) {
                next = deadlineNanos > Long.MAX_VALUE - intervalNanos ? Long.MAX_VALUE
                        : deadlineNanos + intervalNanos;
            } else {
                next = now > Long.MAX_VALUE - intervalNanos ? Long.MAX_VALUE : now + intervalNanos;
            }
            armAt(next);
        }
    }
}
