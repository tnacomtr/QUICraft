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
        HostedScheduledFuture<V> future = new HostedScheduledFuture<V>(this, callable,
                System.nanoTime() + unit.toNanos(Math.max(0, delay)));
        future.handle = host.schedule(future, Math.max(0, delay), unit);
        return future;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("not used by the QUIC codec");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
            TimeUnit unit) {
        throw new UnsupportedOperationException("not used by the QUIC codec");
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

    /** A task scheduled on the host loop, as a Netty {@link ScheduledFuture}. */
    private static final class HostedScheduledFuture<V> extends DefaultPromise<V>
            implements ScheduledFuture<V>, Runnable {
        private final Callable<V> task;
        private final long deadlineNanos;
        volatile HostLoop.Cancellable handle;

        HostedScheduledFuture(HostedEventLoop loop, Callable<V> task, long deadlineNanos) {
            super(loop);
            this.task = task;
            this.deadlineNanos = deadlineNanos;
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
}
