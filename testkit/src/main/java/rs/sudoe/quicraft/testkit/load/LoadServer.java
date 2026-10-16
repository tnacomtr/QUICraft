// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.V3Listener;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Load spike listener: the real {@link QuicServer} with admission limits raised for many clients
 * from one box. {@code --shipped-limits} skips the raise, so the spike measures the shipped
 * defaults instead. {@code --sockets N} binds N SO_REUSEPORT sockets, one loop each, as the
 * platforms do with {@code udp-sockets} (core falls back to one outside Linux). Transport only; each accepted stream is held open until the client closes it.
 *
 * <p>{@code --mode v3} binds a {@code /3} listener (one test protocol number, {@link #PROTOCOL}) with
 * multi-stream on and a node pending-bytes budget of {@code --node-budget-mb} MiB. {@code --lanes}
 * (v3 only) also activates each admitted connection's lanes with the idle game in
 * {@link IdleLaneGame}, once its handshake is complete. The /3 acceptor gets the connection only
 * after admission, so its count is the admitted count.
 *
 * <p>Runs until SIGTERM or SIGINT, then closes the listener.
 */
public final class LoadServer {
    /** Testkit-only SERVER_SWITCH stand-in, sent on stream 0 before server activation. */
    static final byte[] ACTIVATION_MARKER = new byte[] {0x51};
    /** The Minecraft protocol number the /3 test table is for; the spike has no real login. */
    static final int PROTOCOL = 4242;
    static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":" + PROTOCOL + ",\"default\":\"control\",\"routes\":[]}");
    private static final long MIB = 1024L * 1024L;
    private static final long HANDSHAKE_WAIT_MS = 10_000;
    private static final long HANDSHAKE_RETRY_MS = 10;
    /**
     * One shared daemon timer retries the activation check of every pending connection. core has no
     * handshake-complete callback on {@code V3Connection}, so the check has to poll; a thread per
     * connection would not scale to the spike's connection counts.
     */
    private static final ScheduledExecutorService ACTIVATOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "load-server-activator");
        t.setDaemon(true);
        return t;
    });

    private LoadServer() {}

    public static int run(Args args) throws Exception {
        InetSocketAddress bind = hostPort(args.string("bind", "127.0.0.1:65530"));
        int statusSec = args.integer("status-interval", 5);
        int idleMin = args.integer("idle-minutes", 10);
        int sockets = args.integer("sockets", 1);
        boolean v3 = "v3".equals(args.string("mode", "v2"));
        boolean lanes = args.flag("lanes");
        if (!v3 && lanes) {
            throw new IllegalArgumentException("--lanes needs --mode v3");
        }
        int nodeBudgetMb = args.integer("node-budget-mb", 256);

        ServerIdentity identity = ServerIdentity.generate();
        TransportConfig.Builder builder = TransportConfig.builder()
                .maxIdleTimeout(idleMin, TimeUnit.MINUTES);
        if (v3) {
            builder.multistream(true).nodePendingBytes(nodeBudgetMb * MIB);
        }
        if (!args.flag("shipped-limits")) {
            builder.maxOpen(100_000)
                    // v3 spike clients all come from one loopback address (connectV3 has no source
                    // address), so the per-IP cap must not be the limit being measured.
                    .maxOpenPerIp(v3 ? 100_000 : 64)
                    .maxTrackedIps(300_000)
                    .globalAttemptsPerSecond(100_000)
                    // Same reason for the per-IP attempt and ingress Initial budgets: every /3 dial is
                    // one Initial stream from the same address, and a fixed 1 s window of 1000 drops
                    // Initials (ingress guard, silently) once the ramp passes about 1000 per second.
                    .perIpAttemptsPerSecond(v3 ? 100_000 : 1_000)
                    .retryThresholdPerSecond(100_000)
                    .ingressInitialsPerSecond(100_000)
                    .ingressInitialsPerIpPerSecond(v3 ? 100_000 : 1_000);
        }
        TransportConfig config = builder.build();

        AtomicLong accepted = new AtomicLong();
        AtomicLong lanesActive = new AtomicLong();
        AtomicLong lanesFailed = new AtomicLong();
        Set<QuicByteStream> open = ConcurrentHashMap.newKeySet();
        Refusals refusals = new Refusals();
        QuicServer server;
        if (v3) {
            refusals.install();
            V3Listener listener = protocol -> protocol == PROTOCOL ? TABLE : null;
            server = QuicServer.bind(bind, sockets, identity, config, stream -> {
                accepted.incrementAndGet();
                if (lanes) {
                    activateWhenHandshaken((V3Connection) stream, lanesActive, lanesFailed);
                }
            }, listener);
        } else {
            server = QuicServer.bind(bind, sockets, identity, config, stream -> {
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
        }

        InetSocketAddress local = server.localAddress();
        System.out.println("PID " + ProcessHandle.current().pid());
        System.out.println("BOUND " + local.getAddress().getHostAddress() + " " + local.getPort());
        System.out.println("FINGERPRINT " + identity.fingerprint());
        System.out.println("SOCKETS requested=" + sockets);
        System.out.println("MODE " + (v3 ? "v3 node_budget_mb=" + nodeBudgetMb + " lanes=" + lanes : "v2"));

        QuicServer serverForStats = server;
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-server-status");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> System.out.println("STAT connections=" + accepted.get()
                + (v3 ? " admitted=" + accepted.get() + " lanes_active=" + lanesActive.get()
                        + " lanes_failed=" + lanesFailed.get()
                        + " retries=" + retriesSent(serverForStats) + refusals.fields()
                        : " streams=" + open.size())
                + " rss_mb=" + ProcStats.rssMb() + " threads=" + ProcStats.threads()
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

    /**
     * Activates a /3 connection's lanes once its handshake is complete (activation fails closed
     * before that, 12.7). The acceptor returns at once: the first check runs inline and a pending
     * connection is re-checked on the shared {@link #ACTIVATOR} timer until its handshake deadline.
     * A deadline miss or a thrown activation call counts as {@code lanes_failed}; a failure inside
     * core's event loop is closed by core and is not visible through {@code V3Connection}.
     */
    private static void activateWhenHandshaken(V3Connection connection, AtomicLong lanesActive,
            AtomicLong lanesFailed) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_WAIT_MS);
        attemptActivation(connection, lanesActive, lanesFailed, deadline);
    }

    private static void attemptActivation(V3Connection connection, AtomicLong lanesActive,
            AtomicLong lanesFailed, long deadline) {
        try {
            if (!connection.handshakeComplete()) {
                if (System.nanoTime() > deadline) {
                    lanesFailed.incrementAndGet();
                    return;
                }
                ACTIVATOR.schedule(() -> attemptActivation(connection, lanesActive, lanesFailed, deadline),
                        HANDSHAKE_RETRY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            // Mirror /3 cutover ordering: the harness marker substitutes for SERVER_SWITCH. The
            // peer observes it on stream 0 before enabling its lane parser.
            connection.write(java.nio.ByteBuffer.wrap(ACTIVATION_MARKER));
            connection.flush();
            connection.detachForActivation(leftover -> { });
            connection.activate(new IdleLaneGame((code, cause) -> {}), PROTOCOL, null);
            lanesActive.incrementAndGet();
        } catch (Throwable t) {
            lanesFailed.incrementAndGet();
        }
    }

    /** Retry packets the listener has sent (core's package-private counter, read reflectively). */
    static long retriesSent(QuicServer server) {
        try {
            Method m = QuicServer.class.getDeclaredMethod("retriesSent");
            m.setAccessible(true);
            return (long) m.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Counts core's FINE-level refusal logs by reason. The QUICraft logger is raised to FINE for
     * this process only; the console handler stays at INFO, so nothing new is printed.
     */
    static final class Refusals extends Handler {
        private static final String[][] REASONS = {
            {"over the open-connection limits", "open_limit"},
            {"over the node pending-bytes budget", "budget"},
            {"early token check failed", "early_token"},
            {"QUIC connection error", "connection_error"},
            {"stream acceptor failed", "acceptor_failed"},
            {"admission check failed", "admission_check_failed"},
            {"ingress guard failed", "ingress_guard_failed"},
        };
        private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

        void install() {
            Logger logger = Logger.getLogger("QUICraft");
            logger.setLevel(Level.FINE);
            setLevel(Level.FINE);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message == null) {
                return;
            }
            for (String[] reason : REASONS) {
                if (message.contains(reason[0])) {
                    counts.computeIfAbsent(reason[1], k -> new AtomicLong()).incrementAndGet();
                    return;
                }
            }
        }

        /** Space-separated counters, e.g. " refused_open_limit=3"; empty when nothing counted. */
        String fields() {
            StringBuilder out = new StringBuilder();
            for (String[] reason : REASONS) {
                AtomicLong n = counts.get(reason[1]);
                if (n != null) {
                    out.append(" refused_").append(reason[1]).append('=').append(n.get());
                }
            }
            return out.toString();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
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
