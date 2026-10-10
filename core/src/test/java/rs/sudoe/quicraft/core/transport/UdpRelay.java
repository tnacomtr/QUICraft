// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A loopback UDP relay between one client and a server that shapes both directions like the
 * testkit's netem reorder profile: each packet is delayed by {@code delayMillis}, except a
 * {@code skipFraction} that goes straight through and so overtakes the delayed ones. Nothing is
 * dropped.
 */
final class UdpRelay implements AutoCloseable {
    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final ScheduledExecutorService delay = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "udp-relay-delay");
        t.setDaemon(true);
        return t;
    });
    private final Thread pump;
    private volatile SocketAddress client;

    UdpRelay(InetSocketAddress server, long delayMillis, double skipFraction, long seed) throws IOException {
        this.server = server;
        this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Random random = new Random(seed);
        pump = new Thread(() -> {
            byte[] buf = new byte[65536];
            try {
                while (!socket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                    SocketAddress to;
                    if (!p.getSocketAddress().equals(server)) {
                        client = p.getSocketAddress();
                        to = server;
                    } else if (client != null) {
                        to = client;
                    } else {
                        continue;
                    }
                    if (random.nextDouble() < skipFraction) {
                        send(data, to);
                    } else {
                        delay.schedule(() -> send(data, to), delayMillis, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (IOException e) {
                // closed
            }
        }, "udp-relay");
        pump.setDaemon(true);
        pump.start();
    }

    InetSocketAddress address() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    private void send(byte[] data, SocketAddress to) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        socket.close();
        delay.shutdownNow();
    }
}
