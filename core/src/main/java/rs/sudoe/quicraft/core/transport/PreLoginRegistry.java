// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/**
 * The listener's staged {@code /3} connections and the rules for shedding them (docs/protocol.md
 * 12.13). Shedding is the only way a full pre-login pool gives space to a new honest connection.
 *
 * <p>Victims are chosen in this order: first the source prefix furthest over its fair share of
 * pre-login bytes, at the coarse level (IPv6 /48, IPv4 /24) and then the fine level (IPv6 /64, IPv4
 * address); then the least-progressed connection in that group; then at random. A connection waiting
 * on the server (session-server or platform event in flight) is shed only while its prefix is over its
 * fair share. A new connection whose own prefix is already over its fair share is refused rather than
 * shedding anyone else. Shedding closes a victim with {@code 0x12} before Login Success; the caller
 * supplies that close as a non-blocking {@link Runnable}.
 *
 * <p>Session-server calls in flight are capped per coarse prefix (a profile value). All methods are
 * synchronized: listener sockets run on several event loops.
 */
final class PreLoginRegistry {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** Default cap on session-server calls in flight per coarse prefix (profile value). */
    static final int DEFAULT_SESSION_CALLS_PER_PREFIX = 4;

    private final ResourcePools pools;
    private final int sessionCallsPerPrefix;
    private final Random random;
    private final Set<Entry> staged = new HashSet<>();
    private final Map<InetAddress, Integer> sessionCalls = new HashMap<>();

    PreLoginRegistry(ResourcePools pools) {
        this(pools, DEFAULT_SESSION_CALLS_PER_PREFIX, new Random());
    }

    PreLoginRegistry(ResourcePools pools, int sessionCallsPerPrefix, Random random) {
        if (sessionCallsPerPrefix < 1) {
            throw new IllegalArgumentException("sessionCallsPerPrefix must be positive");
        }
        this.pools = Objects.requireNonNull(pools, "pools");
        this.sessionCallsPerPrefix = sessionCallsPerPrefix;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Charges a new staged connection at its first accepted Initial (12.13). When the pool is full,
     * sheds victims in the order above, unless the new connection's own prefix is already over its
     * fair share. Returns null when the connection cannot be admitted; the caller then refuses it
     * (the client falls back to TCP).
     *
     * @param closeConnection non-blocking close with {@code 0x12}, run when this connection is shed
     */
    synchronized Entry admit(InetAddress source, Object owner, int shards, boolean online, LongSupplier clock,
            Runnable closeConnection) {
        InetAddress coarse = coarsePrefix(source);
        long share = PreLoginTracker.stagedShare(shards);
        while (true) {
            PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, owner, shards, online, clock);
            if (tracker != null) {
                Entry entry = new Entry(tracker, source, coarse, closeConnection);
                staged.add(entry);
                return entry;
            }
            if (overFairShare(coarse, share)) {
                return null;
            }
            Entry victim = pickVictim();
            if (victim == null) {
                return null;
            }
            LOG.log(Level.FINE, "shedding a staged /3 connection under pre-login pool pressure");
            shed(victim);
        }
    }

    /**
     * Reserves one session-server call for {@code source}'s coarse prefix, or returns false when the
     * prefix already has its cap in flight. Pair every true with {@link #endSessionCall}.
     */
    synchronized boolean tryBeginSessionCall(InetAddress source) {
        InetAddress coarse = coarsePrefix(source);
        int inFlight = sessionCalls.getOrDefault(coarse, 0);
        if (inFlight >= sessionCallsPerPrefix) {
            return false;
        }
        sessionCalls.put(coarse, inFlight + 1);
        return true;
    }

    /** Releases a session-server call reserved by {@link #tryBeginSessionCall}. */
    synchronized void endSessionCall(InetAddress source) {
        InetAddress coarse = coarsePrefix(source);
        int inFlight = sessionCalls.getOrDefault(coarse, 0);
        if (inFlight <= 1) {
            sessionCalls.remove(coarse);
        } else {
            sessionCalls.put(coarse, inFlight - 1);
        }
    }

    /** The number of staged connections right now. For tests and metrics. */
    synchronized int stagedCount() {
        return staged.size();
    }

    /** Stops tracking a connection that promoted or closed. Called by {@link Entry}. */
    private synchronized void forget(Entry entry) {
        staged.remove(entry);
    }

    /** Sheds one victim. Only staged connections are shed; the tracker's state guards that. */
    private void shed(Entry victim) {
        staged.remove(victim);
        victim.tracker.onShed();
        try {
            victim.closeConnection.run();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "closing a shed /3 connection failed", e);
        }
    }

    /** True when adding {@code bytes} to {@code coarse}'s pre-login bytes would exceed its fair share. */
    private boolean overFairShare(InetAddress coarse, long bytes) {
        Map<InetAddress, Long> byCoarse = bytesByCoarse();
        long total = 0;
        for (long b : byCoarse.values()) {
            total += b;
        }
        int active = byCoarse.size() + (byCoarse.containsKey(coarse) ? 0 : 1);
        long share = (total + bytes) / Math.max(1, active);
        return byCoarse.getOrDefault(coarse, 0L) + bytes > share;
    }

    /** The victim by the order documented on this class, or null when nobody is eligible. */
    private Entry pickVictim() {
        if (staged.isEmpty()) {
            return null;
        }
        Map<InetAddress, Long> byCoarse = bytesByCoarse();
        long total = 0;
        for (long b : byCoarse.values()) {
            total += b;
        }
        long fairShare = total / Math.max(1, byCoarse.size());

        // Eligible: unwaiting connections, and waiting ones whose coarse prefix is over its share.
        List<Entry> eligible = new ArrayList<>();
        for (Entry e : staged) {
            if (!e.waiting() || byCoarse.getOrDefault(e.coarse, 0L) > fairShare) {
                eligible.add(e);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        // Hierarchy level 1: the coarse prefix furthest over its share, when any is over it.
        InetAddress worstCoarse = null;
        long worstCoarseBytes = fairShare;
        for (Map.Entry<InetAddress, Long> c : byCoarse.entrySet()) {
            if (c.getValue() > worstCoarseBytes) {
                worstCoarse = c.getKey();
                worstCoarseBytes = c.getValue();
            }
        }
        List<Entry> group = eligible;
        if (worstCoarse != null) {
            List<Entry> inCoarse = new ArrayList<>();
            for (Entry e : eligible) {
                if (e.coarse.equals(worstCoarse)) {
                    inCoarse.add(e);
                }
            }
            group = inCoarse;
        }
        // Level 2: within it, the fine prefix (IPv6 /64, IPv4 address) with the most bytes.
        Map<InetAddress, Long> byFine = new HashMap<>();
        for (Entry e : group) {
            byFine.merge(e.fine, e.charged(), Long::sum);
        }
        InetAddress worstFine = null;
        long worstFineBytes = -1;
        for (Map.Entry<InetAddress, Long> f : byFine.entrySet()) {
            if (f.getValue() > worstFineBytes) {
                worstFine = f.getKey();
                worstFineBytes = f.getValue();
            }
        }
        List<Entry> fine = new ArrayList<>();
        for (Entry e : group) {
            if (e.fine.equals(worstFine)) {
                fine.add(e);
            }
        }
        // Level 3: least progressed, then random.
        long leastProgress = Long.MAX_VALUE;
        for (Entry e : fine) {
            leastProgress = Math.min(leastProgress, e.progress);
        }
        List<Entry> tied = new ArrayList<>();
        for (Entry e : fine) {
            if (e.progress == leastProgress) {
                tied.add(e);
            }
        }
        return tied.get(random.nextInt(tied.size()));
    }

    private Map<InetAddress, Long> bytesByCoarse() {
        Map<InetAddress, Long> bytes = new HashMap<>();
        for (Entry e : staged) {
            bytes.merge(e.coarse, e.charged(), Long::sum);
        }
        return bytes;
    }

    /**
     * The coarse source prefix used for fair share and session-call caps: IPv6 /48, IPv4 /24 (an
     * IPv4-mapped address counts as IPv4). Fair share is measured in pool bytes per prefix (12.13).
     */
    static InetAddress coarsePrefix(InetAddress address) {
        byte[] raw = address.getAddress();
        boolean v6 = address instanceof Inet6Address && !isIpv4Mapped(raw);
        byte[] out;
        try {
            if (v6) {
                out = new byte[16];
                System.arraycopy(raw, 0, out, 0, 6);
                return InetAddress.getByAddress(out);
            }
            byte[] v4 = raw.length == 4 ? raw : new byte[] {raw[12], raw[13], raw[14], raw[15]};
            return InetAddress.getByAddress(new byte[] {v4[0], v4[1], v4[2], 0});
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The fine prefix: IPv6 /64 (12.13). IPv4 has no finer level than its /24, so the fine prefix of an
     * IPv4 source is its coarse prefix and the hierarchy has one level for it.
     */
    static InetAddress finePrefix(InetAddress address) {
        InetAddress key = ConnectionAdmission.limitKey(address);
        return key instanceof Inet6Address ? key : coarsePrefix(address);
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }

    /**
     * A staged connection as the registry sees it. It is also the {@link PreLoginStage} the platform
     * drives: pausing the client wait around session-server and platform waits makes it waiting, which
     * protects it while its prefix is within its share.
     */
    final class Entry implements PreLoginStage, AutoCloseable {
        private final PreLoginTracker tracker;
        private final InetAddress coarse;
        private final InetAddress fine;
        private final Runnable closeConnection;
        private int waitDepth;
        private long progress;

        private Entry(PreLoginTracker tracker, InetAddress source, InetAddress coarse, Runnable closeConnection) {
            this.tracker = tracker;
            this.coarse = coarse;
            this.fine = finePrefix(source);
            this.closeConnection = Objects.requireNonNull(closeConnection, "closeConnection");
        }

        /** The underlying accounting, for the caller's read and frame charges. */
        PreLoginTracker tracker() {
            return tracker;
        }

        /** Records progress (bytes consumed): the least-progressed connection is shed first. */
        void recordProgress(long bytes) {
            synchronized (PreLoginRegistry.this) {
                if (bytes > 0) {
                    progress += bytes;
                }
            }
        }

        /** Whether a session-server or platform wait is in flight. */
        boolean waiting() {
            return waitDepth > 0;
        }

        /** Bytes this connection holds in the pre-login pool. */
        long charged() {
            return tracker.charged();
        }

        /** True once the client-wait deadline or the 30 s hard cap has passed. */
        boolean expired() {
            return tracker.expired();
        }

        @Override
        public void pauseClientWait() {
            synchronized (PreLoginRegistry.this) {
                waitDepth++;
                tracker.pauseClientWait();
            }
        }

        @Override
        public void resumeClientWait() {
            synchronized (PreLoginRegistry.this) {
                if (waitDepth > 0) {
                    waitDepth--;
                    tracker.resumeClientWait();
                }
            }
        }

        @Override
        public void onLoginSuccess() {
            promoteIfNeeded(() -> tracker.onLoginSuccess());
        }

        @Override
        public void onActivation() {
            promoteIfNeeded(() -> tracker.onActivation());
        }

        @Override
        public void onShed() {
            synchronized (PreLoginRegistry.this) {
                shed(this);
            }
        }

        /** Releases the connection's charge, from either pool, and stops tracking it. Idempotent. */
        @Override
        public void close() {
            synchronized (PreLoginRegistry.this) {
                forget(this);
                tracker.close();
            }
        }

        private void promoteIfNeeded(Runnable promotion) {
            synchronized (PreLoginRegistry.this) {
                promotion.run();
                if (tracker.state() != PreLoginTracker.State.STAGED) {
                    forget(this);
                }
            }
        }
    }
}
