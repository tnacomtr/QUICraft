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
 * Characterizes connect latency through a relay adding 100 ms each way (200 ms RTT). Observed
 * with Netty 4.2.19: the client has finished the TLS handshake after one round trip, but the
 * connect future only completes when the server's next (1-RTT) packet arrives, so connecting
 * takes about 2 RTT, against 1 RTT for TCP connect (docs/protocol.md §5). This test logs the
 * flights and fails only outside 1–2.5 RTT, so a Netty change in either direction gets noticed.
 */
class HandshakeFlightProbeTest {
    @Test
    void connectTakesAboutTwoRoundTripsWithNetty4219() throws Exception {
        ServerIdentity id = ServerIdentity.generate();
        ScheduledExecutorService delay = Executors.newScheduledThreadPool(2);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), id, TransportConfig.DEFAULT, s -> {});
                DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            long t0 = System.nanoTime();
            SocketAddress[] client = new SocketAddress[1];
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
            long start = System.nanoTime();
            f.get(10, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("connected after %d ms (%.2f RTT)%n", millis, millis / 200.0);
            org.junit.jupiter.api.Assertions.assertTrue(millis >= 200 && millis <= 500,
                    "connect took " + millis + " ms at 200 ms RTT");
            f.get().close();
            Thread.sleep(500);
        } finally {
            delay.shutdownNow();
        }
    }
}
