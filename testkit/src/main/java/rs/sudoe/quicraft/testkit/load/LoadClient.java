// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Load spike client: opens {@code --count} real QUIC connections to a {@link LoadServer}, each
 * from its own loopback source IP, and holds them. Transport only; no Minecraft protocol.
 *
 * <p>The source-address connect is package-private in core ({@code QuicClient.connect(local,
 * remote, fingerprint, config, early)}), so it is reached reflectively here; core is not changed.
 *
 * <p>{@code --mode v3} dials with {@code QuicClient.connectV3} (public API, no source address, so
 * every connection comes from the default loopback address). A /3 connection the server refuses at
 * admission still completes the handshake and is then closed, so the client counts a connection as
 * held only until its close arrives: {@code live} = connected minus closed. {@code --lanes} also
 * activates the lanes with the idle game once connected.
 */
public final class LoadClient {
    private static final long ATTEMPT_TIMEOUT_SEC = 30;
    private static final long MIN_MEM_AVAILABLE_KB = 8L * 1024 * 1024;
    private static final int GUARD_CHECK_MS = 1000;

    private final AtomicLong started = new AtomicLong();
    private final AtomicLong connected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong lanesActive = new AtomicLong();
    private final AtomicLong preMarkerReads = new AtomicLong();
    private final AtomicLong lanesFailed = new AtomicLong();
    private final Map<String, AtomicLong> closeCauses = new ConcurrentHashMap<>();
    /** Failed dials by normalized cause (root exception class and message, digits folded). */
    private final Map<String, AtomicLong> causes = new ConcurrentHashMap<>();
    private final Queue<Object> held = new ConcurrentLinkedQueue<>();
    private final CountDownLatch allDone;
    private final boolean v3;

    private LoadClient(int count, boolean v3) {
        this.allDone = new CountDownLatch(count);
        this.v3 = v3;
    }

    /** Starts one dial; the future completes with the connection. */
    private interface Dial {
        CompletableFuture<?> start() throws Throwable;
    }

    public static int run(Args args) throws Exception {
        InetSocketAddress remote = LoadServer.hostPort(args.required("remote"));
        Fingerprint fingerprint = Fingerprint.parse(args.required("fingerprint"));
        int count = args.integer("count", 1000);
        double rate = args.decimal("rate", 0);
        long sourceBase = ipv4ToLong(InetAddress.getByName(args.string("source-base", "127.1.0.0")));
        int holdSec = args.integer("hold-sec", 120);
        int idleMin = args.integer("idle-minutes", 10);
        int statusSec = args.integer("status-interval", 5);
        long maxRssMb = args.integer("max-rss-mb", 12288);
        int maxFailPct = args.integer("max-fail-pct", 5);
        boolean resumption = args.flag("session-resumption");
        boolean v3 = "v3".equals(args.string("mode", "v2"));
        boolean lanes = args.flag("lanes");
        if (count < 1 || (sourceBase >>> 24) != 127) {
            throw new IllegalArgumentException("need --count >= 1 and a 127/8 --source-base");
        }
        if (!v3 && lanes) {
            throw new IllegalArgumentException("--lanes needs --mode v3");
        }

        TransportConfig config = TransportConfig.builder()
                .maxIdleTimeout(idleMin, TimeUnit.MINUTES)
                .sessionResumption(resumption)
                .build();
        MethodHandle connectFrom = v3 ? null : connectFromLocal();
        LoadClient load = new LoadClient(count, v3);
        System.out.println("PID " + ProcessHandle.current().pid());
        System.out.println("MODE " + (v3 ? "v3 lanes=" + lanes : "v2"));

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-client-monitor");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(() -> load.guard(maxRssMb, maxFailPct), GUARD_CHECK_MS, GUARD_CHECK_MS,
                TimeUnit.MILLISECONDS);
        monitor.scheduleAtFixedRate(load::status, statusSec, statusSec, TimeUnit.SECONDS);

        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (rate > 0) {
                // Token bucket with one token: start i is due at t0 + i / rate.
                long due = t0 + (long) (i * 1e9 / rate);
                long wait;
                while ((wait = due - System.nanoTime()) > 0) {
                    LockSupport.parkNanos(wait);
                }
            }
            load.started.incrementAndGet();
            if (v3) {
                Dial dial = () -> QuicClient.connectV3(remote, fingerprint, LoadServer.TABLE, config, null, false);
                Consumer<Object> hook = connection -> load.onV3Connected(connection, lanes);
                Thread.ofVirtual().start(() -> load.attempt(dial, hook));
            } else {
                InetSocketAddress local = new InetSocketAddress(longToIpv4(sourceBase + i + 1), 0);
                Dial dial = () -> (CompletableFuture<?>) connectFrom.invoke(local, remote, fingerprint, config,
                        (EarlyFlight) null);
                Thread.ofVirtual().start(() -> load.attempt(dial, null));
            }
        }

        load.allDone.await();
        System.out.println("ALL_ATTEMPTS_RESOLVED seconds=" + (System.nanoTime() - t0) / 1_000_000_000L);
        long closedAtHoldStart = load.closed.get();
        Thread.sleep(TimeUnit.SECONDS.toMillis(holdSec));
        monitor.shutdownNow();
        System.out.println("SUMMARY started=" + load.started.get() + " connected=" + load.connected.get()
                + " failed=" + load.failed.get() + load.v3Fields()
                + (v3 ? " closed_during_hold=" + (load.closed.get() - closedAtHoldStart) : "")
                + " rss_mb=" + ProcStats.rssMb());
        load.printCauses();
        return 0;
    }

    /** One attempt on its own virtual thread. Failures are counted, never retried. */
    private void attempt(Dial dial, Consumer<Object> onConnected) {
        try {
            CompletableFuture<?> future = dial.start();
            try {
                Object connection = future.get(ATTEMPT_TIMEOUT_SEC, TimeUnit.SECONDS);
                held.add(connection);
                connected.incrementAndGet();
                if (onConnected != null) {
                    onConnected.accept(connection);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                recordFailure("attempt timed out after " + ATTEMPT_TIMEOUT_SEC + " s (no handshake result)");
            }
        } catch (Throwable t) {
            recordFailure(normalizeCause(t));
        } finally {
            allDone.countDown();
        }
    }

    private void recordFailure(String cause) {
        failed.incrementAndGet();
        causes.computeIfAbsent(cause, k -> new AtomicLong()).incrementAndGet();
    }

    /** Root cause as "class: message", digits folded to N so per-connection numbers group together. */
    static String normalizeCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().replace('\n', ' ');
        message = message.replaceAll("[0-9]+", "N");
        if (message.length() > 160) {
            message = message.substring(0, 160);
        }
        String text = root.getClass().getName() + ": " + message;
        return root == t ? text : t.getClass().getSimpleName() + " <- " + text;
    }

    /** One FAIL_CAUSE line per distinct cause, most frequent first. */
    private void printCauses() {
        List<Map.Entry<String, AtomicLong>> entries = new ArrayList<>(causes.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        for (Map.Entry<String, AtomicLong> e : entries) {
            System.out.println("FAIL_CAUSE count=" + e.getValue().get() + " " + e.getKey());
        }
        List<Map.Entry<String, AtomicLong>> closes = new ArrayList<>(closeCauses.entrySet());
        closes.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        for (Map.Entry<String, AtomicLong> e : closes) {
            String key = e.getKey();
            String code = key.contains("0x11") || key.contains("rule 13.4") ? "0x11" : "other";
            System.out.println("CLOSE_CAUSE count=" + e.getValue().get() + " code=" + code + " " + key);
        }
    }

    /** Installs the listener and waits for the test server's marker before activating lanes. */
    private void onV3Connected(Object connection, boolean lanes) {
        V3Connection v3 = (V3Connection) connection;
        byte marker = LoadServer.ACTIVATION_MARKER[0];
        AtomicBoolean activated = new AtomicBoolean();
        v3.setListener(new QuicByteStream.Listener() {
            @Override
            public void onData(ByteBuffer data) {
                if (!lanes || activated.get() || !data.hasRemaining()) {
                    return;
                }
                if (data.get(data.position()) != marker) {
                    // Bytes before the harness marker: count them so a run shows whether the
                    // marker can arrive behind other stream-0 data in the same read.
                    preMarkerReads.incrementAndGet();
                    return;
                }
                if (!activated.compareAndSet(false, true)) {
                    return;
                }
                try {
                    data.get(); // Consume the harness SERVER_SWITCH marker, never a lane record.
                    ByteBuffer suffix = data.hasRemaining() ? data.slice() : null;
                    v3.detachForActivation(leftover -> { });
                    v3.activate(IdleLaneGame.INSTANCE, suffix);
                    lanesActive.incrementAndGet();
                } catch (Throwable t) {
                    lanesFailed.incrementAndGet();
                }
            }

            @Override
            public void onWritabilityChanged(boolean writable) {}

            @Override
            public void onClosed(Throwable cause) {
                closed.incrementAndGet();
                String key = cause == null ? "normal" : normalizeCause(cause);
                closeCauses.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            }
        });
        // The connection stops reading stream 0 after the preamble until the owner asks for
        // data; without this the marker can stay unread in quiche or parked behind the gate.
        v3.setAutoRead(true);
    }

    /**
     * The /3 counters: closed, the held count live = connected - closed, lanes activated, and lanes
     * whose activation call threw. A failure inside the event loop is closed by core and is not
     * visible here.
     */
    private String v3Fields() {
        if (!v3) {
            return "";
        }
        return " closed=" + closed.get() + " live=" + (connected.get() - closed.get())
                + " lanes_active=" + lanesActive.get() + " pre_marker_reads=" + preMarkerReads.get() + " lanes_failed=" + lanesFailed.get();
    }

    private void status() {
        long s = started.get();
        long c = connected.get();
        long f = failed.get();
        System.out.println("STAT started=" + s + " connected=" + c + " failed=" + f + " active=" + (s - c - f)
                + v3Fields() + " rss_mb=" + ProcStats.rssMb() + " threads=" + ProcStats.threads() + " cpu_ticks="
                + ProcStats.cpuTicks());
    }

    /** Aborts the run (exit 3) on RSS, host memory or failure-rate limits. */
    private void guard(long maxRssMb, int maxFailPct) {
        long rss = ProcStats.rssMb();
        if (rss > maxRssMb) {
            System.out.println("ABORT RSS=" + rss);
            System.exit(3);
        }
        long availKb = ProcStats.memAvailableKb();
        if (availKb >= 0 && availKb < MIN_MEM_AVAILABLE_KB) {
            System.out.println("ABORT MEMAVAIL");
            System.exit(3);
        }
        long s = started.get();
        long f = failed.get();
        if (s >= 100 && f * 100 > (long) maxFailPct * s) {
            System.out.println("ABORT FAILS failed=" + f + " started=" + s);
            System.exit(3);
        }
    }

    /** The package-private source-address connect in core, found by reflection (core unchanged). */
    private static MethodHandle connectFromLocal() throws ReflectiveOperationException {
        Method m = QuicClient.class.getDeclaredMethod("connect", InetSocketAddress.class, InetSocketAddress.class,
                Fingerprint.class, TransportConfig.class, EarlyFlight.class);
        m.setAccessible(true);
        return MethodHandles.lookup().unreflect(m);
    }

    private static long ipv4ToLong(InetAddress address) {
        long value = 0;
        for (byte b : address.getAddress()) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    private static InetAddress longToIpv4(long value) throws java.net.UnknownHostException {
        return InetAddress.getByAddress(new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }
}
