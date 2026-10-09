// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** A real loopback TCP listener plus a {@link ConnectionRace.Tcp} that records what it did. */
final class TcpFixture implements ConnectionRace.Tcp<Socket>, AutoCloseable {
    final ServerSocket server;
    final List<Socket> opened = new CopyOnWriteArrayList<>();
    final List<Socket> closed = new CopyOnWriteArrayList<>();
    final AtomicLong startedAtNanos = new AtomicLong();
    private final long connectDelayMillis;
    private final int port;

    TcpFixture(long connectDelayMillis) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.port = server.getLocalPort();
        this.connectDelayMillis = connectDelayMillis;
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    server.accept();
                } catch (IOException e) {
                    return;
                }
            }
        }, "tcp-fixture-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** A Tcp that connects to a port nobody listens on. */
    static ConnectionRace.Tcp<Socket> refused() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        return new ConnectionRace.Tcp<Socket>() {
            @Override
            public CompletableFuture<Socket> connect() {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return new Socket(InetAddress.getLoopbackAddress(), port);
                    } catch (IOException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                });
            }

            @Override
            public void close(Socket connection) {}
        };
    }

    boolean started() {
        return startedAtNanos.get() != 0;
    }

    @Override
    public CompletableFuture<Socket> connect() {
        startedAtNanos.compareAndSet(0, System.nanoTime());
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(connectDelayMillis);
                Socket s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
                opened.add(s);
                return s;
            } catch (IOException | InterruptedException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    @Override
    public void close(Socket connection) {
        try {
            connection.close();
        } catch (IOException ignored) {
            // test fixture
        }
        closed.add(connection);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : opened) {
            s.close();
        }
    }
}
