// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Transport benchmark server: TCP on {@code --port}, QUIC with RENO, CUBIC and BBR on the next
 * three ports. Each connection gets the same Minecraft-shaped session: a login burst, a chunk
 * burst, then 20 Hz entity updates while echoing the client's pings.
 */
public final class TransportServer {
    static final int LOGIN_BYTES = 200 * 1024;
    static final int LOGIN_MESSAGE = 32 * 1024;
    static final int CHUNK_BYTES = 2 * 1024 * 1024;
    static final int CHUNK_MESSAGE = 4 * 1024;
    static final int ENTITY_MESSAGE = 200;

    private static final ScheduledExecutorService TICKS = Executors.newScheduledThreadPool(2);

    private TransportServer() {}

    public static int run(Args args) throws Exception {
        int port = args.integer("port", 30000);
        Path identityDir = Path.of(args.string("identity-dir", "/tmp/quicraft-bench-identity"));
        ServerIdentity identity = ServerIdentity.loadOrCreate(identityDir);
        String fpFile = args.string("fingerprint-file", null);
        if (fpFile != null) {
            Files.createDirectories(Path.of(fpFile).toAbsolutePath().getParent());
            Files.writeString(Path.of(fpFile), identity.fingerprint().toString(), StandardCharsets.US_ASCII);
        }

        ServerSocket tcp = new ServerSocket();
        tcp.bind(new InetSocketAddress(port));
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Conn conn = Conn.tcp(tcp.accept());
                    Thread.ofVirtual().start(() -> serve(conn));
                } catch (IOException e) {
                    return;
                }
            }
        });
        TransportConfig.CongestionControl[] ccs = TransportConfig.CongestionControl.values();
        for (int i = 0; i < ccs.length; i++) {
            TransportConfig config = TransportConfig.builder().congestionControl(ccs[i]).build();
            QuicServer.bind(new InetSocketAddress(port + 1 + ccs[i].ordinal()), identity, config,
                    stream -> Thread.ofVirtual().start(() -> serve(Conn.quic(stream))));
        }
        System.out.println("transport bench server: tcp :" + port + ", quic reno :" + (port + 1)
                + ", cubic :" + (port + 2) + ", bbr :" + (port + 3) + ", fp " + identity.fingerprint());
        new CountDownLatch(1).await();
        return 0;
    }

    private static void serve(Conn conn) {
        ScheduledFuture<?> entities = null;
        try (conn) {
            byte[] hello = conn.receive();
            if (hello[0] != Conn.HELLO) {
                return;
            }
            Random random = new Random(1); // incompressible-ish, same bytes every run
            byte[] login = new byte[LOGIN_MESSAGE];
            random.nextBytes(login);
            for (int sent = 0; sent < LOGIN_BYTES; sent += login.length) {
                conn.send(Conn.LOGIN, login);
            }
            byte[] chunk = new byte[CHUNK_MESSAGE];
            random.nextBytes(chunk);
            for (int sent = 0; sent < CHUNK_BYTES; sent += chunk.length) {
                conn.send(Conn.CHUNK, chunk);
            }
            conn.send(Conn.END_BURST, new byte[0]);
            byte[] entity = new byte[ENTITY_MESSAGE];
            entities = TICKS.scheduleAtFixedRate(() -> {
                try {
                    conn.send(Conn.ENTITY, entity);
                } catch (IOException ignored) {
                    // connection ending
                }
            }, 50, 50, TimeUnit.MILLISECONDS);
            while (true) {
                byte[] frame = conn.receive();
                if (frame[0] == Conn.PING) {
                    byte[] payload = new byte[frame.length - 1];
                    System.arraycopy(frame, 1, payload, 0, payload.length);
                    conn.send(Conn.PONG, payload);
                } else if (frame[0] == Conn.BYE) {
                    return;
                }
            }
        } catch (IOException e) {
            // client went away
        } finally {
            if (entities != null) {
                entities.cancel(false);
            }
        }
    }
}
