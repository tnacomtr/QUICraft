// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * Characterizes connect latency through a relay adding 100 ms each way (200 ms RTT), measured
 * from the client's first Initial to the connect future. Upstream Netty 4.2.19 completed the
 * connect only when the server's next (1-RTT) packet arrived, about 2 RTT (~420 ms here).
 * QUICraft's patched build completes it as soon as the client has sent its Finished, one round
 * trip after the Initial (natives/patches/netty/0002-complete-connect-after-handshake-send.patch,
 * docs/protocol.md §5). Logs the flights and fails outside 0.9–1.6 RTT, so a regression to
 * ~2 RTT (or an implausibly fast connect) gets noticed.
 */
class HandshakeFlightProbeTest {
    @Test
    void connectTakesAboutOneRoundTrip() throws Exception {
        ServerIdentity id = ServerIdentity.generate();
        ScheduledExecutorService delay = Executors.newScheduledThreadPool(2);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), id, TransportConfig.DEFAULT, s -> {});
                DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            long t0 = System.nanoTime();
            SocketAddress[] client = new SocketAddress[1];
            java.util.concurrent.atomic.AtomicLong firstInitial = new java.util.concurrent.atomic.AtomicLong();
            Thread pump = new Thread(() -> {
                byte[] buf = new byte[65536];
                try {
                    while (!relay.isClosed()) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        relay.receive(p);
                        byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
                        boolean fromServer = p.getSocketAddress().equals(server.localAddress());
                        if (!fromServer) {
                            client[0] = p.getSocketAddress();
                            firstInitial.compareAndSet(0, System.nanoTime());
                        }
                        SocketAddress to = fromServer ? client[0] : server.localAddress();
                        System.out.printf("+%4dms %s %5d bytes first=0x%02x%n", (System.nanoTime() - t0) / 1_000_000,
                                fromServer ? "S->C" : "C->S", data.length, data[0]);
                        delay.schedule(() -> {
                            try {
                                relay.send(new DatagramPacket(data, data.length, to));
                            } catch (Exception ignored) {
                            }
                        }, 100, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception ignored) {
                }
            });
            pump.setDaemon(true);
            pump.start();
            CompletableFuture<QuicByteStream> f = QuicClient.connect(
                    (InetSocketAddress) relay.getLocalSocketAddress(), id.fingerprint(), TransportConfig.DEFAULT);
            f.get(10, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - firstInitial.get()) / 1_000_000;
            System.out.printf("connected %d ms after the first Initial (%.2f RTT)%n", millis, millis / 200.0);
            org.junit.jupiter.api.Assertions.assertTrue(millis >= 180 && millis <= 320,
                    "connect took " + millis + " ms after the first Initial at 200 ms RTT");
            f.get().close();
            Thread.sleep(500);
        } finally {
            delay.shutdownNow();
        }
    }
}
