// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Load spike listener: the real {@link QuicServer} with admission limits raised for many clients
 * from one box. {@code --shipped-limits} skips the raise, so the spike measures the shipped
 * defaults instead. {@code --sockets N} binds N SO_REUSEPORT sockets, one loop each, as the
 * platforms do with {@code udp-sockets} (core falls back to one outside Linux). Transport only; each accepted stream is held open until the client closes it.
 * Runs until SIGTERM or SIGINT, then closes the listener.
 */
public final class LoadServer {
    private LoadServer() {}

    public static int run(Args args) throws Exception {
        InetSocketAddress bind = hostPort(args.string("bind", "127.0.0.1:65530"));
        int statusSec = args.integer("status-interval", 5);
        int idleMin = args.integer("idle-minutes", 10);
        int sockets = args.integer("sockets", 1);

        ServerIdentity identity = ServerIdentity.generate();
        TransportConfig.Builder builder = TransportConfig.builder()
                .maxIdleTimeout(idleMin, TimeUnit.MINUTES);
        if (!args.flag("shipped-limits")) {
            builder.maxOpen(100_000)
                    .maxOpenPerIp(64)
                    .maxTrackedIps(300_000)
                    .globalAttemptsPerSecond(100_000)
                    .perIpAttemptsPerSecond(1_000)
                    .retryThresholdPerSecond(100_000)
                    .ingressInitialsPerSecond(100_000)
                    .ingressInitialsPerIpPerSecond(1_000);
        }
        TransportConfig config = builder.build();

        AtomicLong accepted = new AtomicLong();
        Set<QuicByteStream> open = ConcurrentHashMap.newKeySet();
        QuicServer server = QuicServer.bind(bind, sockets, identity, config, stream -> {
            accepted.incrementAndGet();
            open.add(stream);
            stream.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(java.nio.ByteBuffer data) {
                    // Clients send nothing; nothing to drain.
                }

                @Override
                public void onWritabilityChanged(boolean writable) {}

                @Override
                public void onClosed(Throwable cause) {
                    open.remove(stream);
                }
            });
            stream.setAutoRead(true);
        });

        InetSocketAddress local = server.localAddress();
        System.out.println("PID " + ProcessHandle.current().pid());
        System.out.println("BOUND " + local.getAddress().getHostAddress() + " " + local.getPort());
        System.out.println("FINGERPRINT " + identity.fingerprint());
        System.out.println("SOCKETS requested=" + sockets);

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-server-status");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> System.out.println("STAT connections=" + accepted.get()
                + " streams=" + open.size() + " rss_mb=" + ProcStats.rssMb() + " threads=" + ProcStats.threads()
                + " cpu_ticks=" + ProcStats.cpuTicks()), statusSec, statusSec, TimeUnit.SECONDS);

        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            try {
                closed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        stop.await();
        server.close();
        System.out.println("CLOSED connections=" + accepted.get());
        closed.countDown();
        return 0;
    }

    /** Parses {@code host:port} with a numeric or resolvable host; the last colon splits. */
    static InetSocketAddress hostPort(String text) {
        int colon = text.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("expected host:port, got " + text);
        }
        return new InetSocketAddress(text.substring(0, colon), Integer.parseInt(text.substring(colon + 1)));
    }
}
