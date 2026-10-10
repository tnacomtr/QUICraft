// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A UDP relay in front of a server for tests: adds a one-way delay each way, notes when the
 * latest client sent its first datagram, and can capture client datagrams, send copies of them
 * from another socket ahead of the originals (a replay that wins the race), or drop what the
 * server sends.
 */
final class DelayRelay implements AutoCloseable {
    private final InetSocketAddress server;
    private final long oneWayMillis;
    private final DatagramSocket socket;
    private final DatagramSocket copier;
    private final ScheduledExecutorService delay = Executors.newScheduledThreadPool(2);
    private final AtomicLong firstClientDatagram = new AtomicLong();
    private final List<byte[]> captured = new ArrayList<>();
    private volatile SocketAddress client;
    private volatile boolean capture;
    private volatile boolean copyFirst;
    private volatile boolean dropFromServer;
    private volatile boolean dropFromClient;

    DelayRelay(InetSocketAddress server, long oneWayMillis) throws IOException {
        this.server = server;
        this.oneWayMillis = oneWayMillis;
        this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.copier = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread pump = new Thread(this::pump, "delay-relay");
        pump.setDaemon(true);
        pump.start();
    }

    InetSocketAddress address() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    /** Starts timing the next client: {@link #firstClientDatagramNanos()} is its first datagram. */
    void reset() {
        firstClientDatagram.set(0);
    }

    long firstClientDatagramNanos() {
        return firstClientDatagram.get();
    }

    long rttMillis() {
        return 2 * oneWayMillis;
    }

    /** Client datagrams from now on are kept, for {@link #replay}. */
    void capture(boolean on) {
        synchronized (captured) {
            captured.clear();
        }
        capture = on;
    }

    /** Client datagrams go to the server first as copies from another socket, the originals not at all. */
    void copyFirst(boolean on) {
        copyFirst = on;
    }

    void dropFromServer(boolean on) {
        dropFromServer = on;
    }

    void dropFromClient(boolean on) {
        dropFromClient = on;
    }

    /** Sends the captured client datagrams to the server again, from another socket. */
    void replay() throws IOException {
        List<byte[]> copy;
        synchronized (captured) {
            copy = new ArrayList<>(captured);
        }
        for (byte[] d : copy) {
            copier.send(new DatagramPacket(d, d.length, server));
        }
    }

    private void pump() {
        byte[] buf = new byte[65536];
        try {
            while (!socket.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                boolean fromServer = p.getSocketAddress().equals(server);
                if (fromServer) {
                    if (!dropFromServer && client != null) {
                        send(data, client);
                    }
                    continue;
                }
                client = p.getSocketAddress();
                firstClientDatagram.compareAndSet(0, System.nanoTime());
                if (capture) {
                    synchronized (captured) {
                        captured.add(data);
                    }
                }
                if (copyFirst) {
                    copier.send(new DatagramPacket(data, data.length, server));
                } else if (!dropFromClient) {
                    send(data, server);
                }
            }
        } catch (IOException ignored) {
            // closed
        }
    }

    private void send(byte[] data, SocketAddress to) {
        Runnable task = () -> {
            try {
                socket.send(new DatagramPacket(data, data.length, to));
            } catch (IOException ignored) {
                // closed
            }
        };
        if (oneWayMillis == 0) {
            task.run();
        } else {
            delay.schedule(task, oneWayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        socket.close();
        copier.close();
        delay.shutdownNow();
    }
}
