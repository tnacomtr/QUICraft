// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Load spike client: opens {@code --count} real QUIC connections to a {@link LoadServer}, each
 * from its own loopback source IP, and holds them. Transport only; no Minecraft protocol.
 *
 * <p>The source-address connect is package-private in core ({@code QuicClient.connect(local,
 * remote, fingerprint, config, early)}), so it is reached reflectively here; core is not changed.
 */
public final class LoadClient {
    private static final long ATTEMPT_TIMEOUT_SEC = 30;
    private static final long MIN_MEM_AVAILABLE_KB = 8L * 1024 * 1024;
    private static final int GUARD_CHECK_MS = 1000;

    private final AtomicLong started = new AtomicLong();
    private final AtomicLong connected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Queue<QuicByteStream> held = new ConcurrentLinkedQueue<>();
    private final CountDownLatch allDone;

    private LoadClient(int count) {
        this.allDone = new CountDownLatch(count);
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
        if (count < 1 || (sourceBase >>> 24) != 127) {
            throw new IllegalArgumentException("need --count >= 1 and a 127/8 --source-base");
        }

        TransportConfig config = TransportConfig.builder()
                .maxIdleTimeout(idleMin, TimeUnit.MINUTES)
                .sessionResumption(resumption)
                .build();
        MethodHandle connectFrom = connectFromLocal();
        LoadClient load = new LoadClient(count);
        System.out.println("PID " + ProcessHandle.current().pid());

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
            InetSocketAddress local = new InetSocketAddress(longToIpv4(sourceBase + i + 1), 0);
            load.started.incrementAndGet();
            Thread.ofVirtual().start(() -> load.attempt(connectFrom, local, remote, fingerprint, config));
        }

        load.allDone.await();
        System.out.println("ALL_ATTEMPTS_RESOLVED seconds=" + (System.nanoTime() - t0) / 1_000_000_000L);
        Thread.sleep(TimeUnit.SECONDS.toMillis(holdSec));
        monitor.shutdownNow();
        System.out.println("SUMMARY started=" + load.started.get() + " connected=" + load.connected.get()
                + " failed=" + load.failed.get() + " rss_mb=" + ProcStats.rssMb());
        return 0;
    }

    /** One attempt on its own virtual thread. Failures are counted, never retried. */
    private void attempt(MethodHandle connectFrom, InetSocketAddress local, InetSocketAddress remote,
            Fingerprint fingerprint, TransportConfig config) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<QuicByteStream> future = (CompletableFuture<QuicByteStream>) connectFrom
                    .invoke(local, remote, fingerprint, config, (EarlyFlight) null);
            try {
                held.add(future.get(ATTEMPT_TIMEOUT_SEC, TimeUnit.SECONDS));
                connected.incrementAndGet();
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                failed.incrementAndGet();
            }
        } catch (Throwable t) {
            failed.incrementAndGet();
        } finally {
            allDone.countDown();
        }
    }

    private void status() {
        long s = started.get();
        long c = connected.get();
        long f = failed.get();
        System.out.println("STAT started=" + s + " connected=" + c + " failed=" + f + " active=" + (s - c - f)
                + " rss_mb=" + ProcStats.rssMb() + " threads=" + ProcStats.threads() + " cpu_ticks="
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
