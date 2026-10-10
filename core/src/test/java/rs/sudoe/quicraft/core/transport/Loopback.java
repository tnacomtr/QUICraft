// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** Test helpers: an echo server and a byte collector. */
final class Loopback {
    private Loopback() {}

    static InetSocketAddress anyLocal() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    static QuicServer echoServer(ServerIdentity identity, CompletableFuture<QuicByteStream> accepted) throws Exception {
        return echoServer(anyLocal(), identity, accepted);
    }

    static QuicServer echoServer(InetSocketAddress address, ServerIdentity identity,
            CompletableFuture<QuicByteStream> accepted) throws Exception {
        return QuicServer.bind(address, identity, TransportConfig.DEFAULT, stream -> {
            accepted.complete(stream);
            stream.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    stream.write(data);
                    stream.flush();
                }

                @Override
                public void onWritabilityChanged(boolean writable) {}

                @Override
                public void onClosed(Throwable cause) {}
            });
            stream.setAutoRead(true);
        });
    }

    /** Collects received bytes; {@link #await} waits for a byte count. */
    static final class Collector implements QuicByteStream.Listener {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Throwable closeCause;

        @Override
        public synchronized void onData(ByteBuffer data) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            bytes.write(chunk, 0, chunk.length);
            notifyAll();
        }

        @Override
        public void onWritabilityChanged(boolean writable) {}

        @Override
        public void onClosed(Throwable cause) {
            closeCause = cause;
            closed.countDown();
        }

        synchronized byte[] await(int count, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (bytes.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return bytes.toByteArray();
        }
    }
}
