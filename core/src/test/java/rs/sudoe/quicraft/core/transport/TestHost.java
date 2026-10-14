// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Core's hosted transport (docs/protocol.md §11) over plain JDK NIO, for tests: one single-thread
 * executor per {@link HostLoop} and a blocking {@link DatagramChannel} per {@link
 * HostDatagramSocket}, so every call reaches core on the loop's thread and no state is shared
 * between loops. bridge-netty's {@code GameHost} does the same over the game's Netty; core has no
 * other host implementation to test against.
 */
final class TestHost {
    private TestHost() {}

    /** A {@link HostLoop} on one daemon thread of its own. */
    static final class Loop implements HostLoop {
        private final ScheduledThreadPoolExecutor executor;
        private volatile Thread thread;

        Loop(String name) {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread created = new Thread(runnable, name);
                created.setDaemon(true);
                thread = created;
                return created;
            });
        }

        @Override
        public boolean inEventLoop(Thread candidate) {
            return candidate == thread;
        }

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        @Override
        public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            ScheduledFuture<?> scheduled = executor.schedule(task, delay, unit);
            return () -> scheduled.cancel(false);
        }

        void shutdown() {
            executor.shutdownNow();
        }
    }

    /**
     * A bound JDK datagram channel. SO_REUSEPORT is set reflectively (Java 8 bytecode,
     * {@code StandardSocketOptions.SO_REUSEPORT} exists only on JDK 9+); {@link #reusePortAvailable()}
     * reports whether it could be, so a test can skip instead of binding one port twice.
     */
    static final class Socket implements HostDatagramSocket {
        private final Loop loop;
        private final DatagramChannel channel;
        private volatile boolean closed;
        private volatile boolean failOnStart;

        Socket(Loop loop, InetSocketAddress bind) throws IOException {
            this.loop = loop;
            this.channel = DatagramChannel.open();
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
            setReusePort(channel);
            channel.bind(bind);
        }

        /** A socket whose {@link #start} throws, to test a bind that fails on a later socket. */
        void failOnStart(boolean fail) {
            this.failOnStart = fail;
        }

        @Override
        public InetSocketAddress localAddress() {
            try {
                return (InetSocketAddress) channel.getLocalAddress();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void start(Receiver receiver) {
            if (failOnStart) {
                throw new IllegalStateException("this host socket refuses to start");
            }
            Thread reader = new Thread(() -> {
                ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
                while (!closed) {
                    buffer.clear();
                    SocketAddress sender;
                    try {
                        sender = channel.receive(buffer);
                    } catch (IOException socketClosed) {
                        return;
                    }
                    if (sender == null || !(sender instanceof InetSocketAddress)) {
                        continue;
                    }
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    InetSocketAddress from = (InetSocketAddress) sender;
                    // The contract: both callbacks arrive on the loop's thread.
                    loop.execute(() -> receiver.onDatagram(ByteBuffer.wrap(data), from));
                    loop.execute(receiver::onReadComplete);
                }
            }, "test-host-socket");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void write(ByteBuffer data, InetSocketAddress recipient) {
            try {
                channel.send(data, recipient);
            } catch (IOException dropped) {
                // QUIC retransmits; the tests only assert what arrives.
            }
        }

        @Override
        public void flush() {
            // Nothing is queued: every write goes out at once.
        }

        @Override
        public boolean isWritable() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // closing twice
            }
        }

        boolean isClosed() {
            return closed;
        }

        @SuppressWarnings("unchecked")
        private static void setReusePort(DatagramChannel channel) throws IOException {
            try {
                Object option = Class.forName("java.net.StandardSocketOptions").getField("SO_REUSEPORT").get(null);
                channel.setOption((java.net.SocketOption<Boolean>) option, Boolean.TRUE);
            } catch (ReflectiveOperationException | ClassCastException | UnsupportedOperationException unavailable) {
                // JDK 8 or a platform without it: the multi-socket tests skip themselves.
            }
        }
    }

    /** Whether a JDK datagram channel can be bound twice to one port through SO_REUSEPORT. */
    @SuppressWarnings("unchecked")
    static boolean reusePortAvailable() {
        try {
            DatagramChannel channel = DatagramChannel.open();
            try {
                Object option = Class.forName("java.net.StandardSocketOptions").getField("SO_REUSEPORT").get(null);
                channel.setOption((java.net.SocketOption<Boolean>) option, Boolean.TRUE);
                return true;
            } finally {
                channel.close();
            }
        } catch (ReflectiveOperationException | IOException | UnsupportedOperationException unavailable) {
            return false;
        }
    }

    /** Waits for threads named after {@code prefix} to disappear. */
    static void awaitNoThreads(String prefix, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (hasThread(prefix) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    static boolean hasThread(String prefix) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
