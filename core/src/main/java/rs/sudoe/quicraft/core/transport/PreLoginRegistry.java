// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.profile.ServerProfile;
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

    private final ResourcePools pools;
    private final int sessionCallsPerPrefix;
    /** Cap on declared-length bytes in flight per coarse prefix (12.13); a profile value. */
    private final long declaredBytesPerPrefix;
    private final Random random;
    private final Set<Entry> staged = new HashSet<>();
    /**
     * Declared-length charges that could not be granted at once (12.13 wait set). Drained in priority order
     * (coarse holding, fine holding, arrival, id) whenever bytes are released; a granted waiter keeps its
     * bytes until its connection claims the frame, closes, or expires.
     */
    private final Set<Entry> declaredWaiters = new HashSet<>();
    private final Map<InetAddress, Integer> sessionCalls = new HashMap<>();
    private long nextEntryId;
    private long arrivals;

    PreLoginRegistry(ResourcePools pools) {
        this(pools, ServerProfile.DEFAULT_SESSION_CALLS_PER_PREFIX, ServerProfile.DEFAULT_DECLARED_BYTES_PER_PREFIX,
                new Random());
    }

    PreLoginRegistry(ResourcePools pools, int sessionCallsPerPrefix, Random random) {
        this(pools, sessionCallsPerPrefix, ServerProfile.DEFAULT_DECLARED_BYTES_PER_PREFIX, random);
    }

    /**
     * @param sessionCallsPerPrefix session-server calls in flight per coarse prefix (profile value)
     * @param declaredBytesPerPrefix declared-length bytes in flight per coarse prefix (profile value)
     */
    PreLoginRegistry(ResourcePools pools, int sessionCallsPerPrefix, long declaredBytesPerPrefix, Random random) {
        if (sessionCallsPerPrefix < 1) {
            throw new IllegalArgumentException("sessionCallsPerPrefix must be positive");
        }
        if (declaredBytesPerPrefix < PreLoginTracker.MAX_DECLARED_FRAME_BYTES) {
            throw new IllegalArgumentException("declaredBytesPerPrefix must hold one maximum-size frame");
        }
        this.pools = Objects.requireNonNull(pools, "pools");
        this.sessionCallsPerPrefix = sessionCallsPerPrefix;
        this.declaredBytesPerPrefix = declaredBytesPerPrefix;
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
        boolean shedAny = false;
        try {
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
                // Room freed for this newcomer is not offered to waiting declared charges until it is admitted
                // or refused, so a waiter cannot take it and force a further shed.
                shed(victim, false);
                shedAny = true;
            }
        } finally {
            if (shedAny) {
                drainDeclaredWaiters();
            }
        }
    }

    /**
     * Frees one open-connection slot for a validated newcomer when the listener's global limits are full
     * (12.13): sheds one pre-login victim in the order of {@link #admit}, unless the newcomer's own prefix is
     * already over its fair share. The victim's close gives back its lease synchronously, so the caller can
     * take it at once. Returns false when nobody is sheddable: the caller refuses the newcomer.
     */
    synchronized boolean shedForLease(InetAddress source, int shards) {
        if (overFairShare(coarsePrefix(source), PreLoginTracker.stagedShare(shards))) {
            return false;
        }
        Entry victim = pickVictim();
        if (victim == null) {
            return false;
        }
        LOG.log(Level.FINE, "shedding a staged /3 connection to free an open-connection slot");
        shed(victim, true);
        return true;
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

    /**
     * Stops tracking a connection that promoted or closed, and takes it out of the wait set. Its bytes go
     * with its tracker; the caller drains the wait set after the release. Called by {@link Entry}.
     */
    private synchronized void forget(Entry entry) {
        staged.remove(entry);
        dropDeclared(entry);
    }

    /**
     * Sheds one victim. Only staged connections are shed; the tracker's state guards that. With {@code drain}
     * false the caller drains the wait set itself once its own admission is settled.
     */
    private void shed(Entry victim, boolean drain) {
        staged.remove(victim);
        dropDeclared(victim);
        victim.tracker.onShed();
        if (drain) {
            drainDeclaredWaiters();
        }
        try {
            victim.closeConnection.run();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "closing a shed /3 connection failed", e);
        }
    }

    /** Declared-length bytes in flight for {@code coarse}, over its staged connections. */
    private long declaredInFlight(InetAddress coarse) {
        long bytes = 0;
        for (Entry e : staged) {
            if (e.coarse.equals(coarse)) {
                bytes += e.declaredExtra;
            }
        }
        return bytes;
    }

    /** Declared-length bytes in flight for {@code fine}, over its staged connections (12.13 wait-set order). */
    private Map<InetAddress, Long> bytesByFine() {
        Map<InetAddress, Long> bytes = new HashMap<>();
        for (Entry e : staged) {
            bytes.merge(e.fine, e.charged(), Long::sum);
        }
        return bytes;
    }

    /** Adds {@code e} to the wait set for its declared charge; a waiter already in the set keeps its arrival. */
    private void enqueueDeclared(Entry e, long declaredBytes, long framingBytes, long total) {
        if (e.pendingTotal < 0) {
            e.arrival = ++arrivals;
            declaredWaiters.add(e);
        }
        e.pendingDeclared = declaredBytes;
        e.pendingFraming = framingBytes;
        e.pendingTotal = total;
        e.pendingGranted = false;
    }

    /** Takes {@code e} out of the wait set without touching any bytes (a claimed, closed or shed charge). */
    private void dropDeclared(Entry e) {
        declaredWaiters.remove(e);
        e.pendingTotal = -1;
        e.pendingGranted = false;
    }

    /**
     * Takes {@code e} out of the wait set and gives back a grant it was made but never claimed (an abandoned
     * or expired frame), so the bytes return to the pool at once rather than at close.
     */
    private void abandonDeclared(Entry e) {
        if (e.pendingTotal < 0) {
            return;
        }
        if (e.pendingGranted) {
            e.tracker.releaseExtra(e.pendingTotal);
            e.declaredExtra -= Math.min(e.pendingTotal, e.declaredExtra);
        }
        dropDeclared(e);
    }

    /**
     * Grants waiting declared-length charges in priority order (12.13): each waiter whose prefix is within the
     * per-prefix cap and whose bytes fit the pool is granted; the others are skipped, so room is never left
     * idle while a waiter fits. Expired or closed waiters leave the set without being granted. Priority is
     * the hierarchical holding (IPv6 /48 then /64, IPv4 /24), then arrival order, then connection id.
     * Called after every release.
     */
    private void drainDeclaredWaiters() {
        if (declaredWaiters.isEmpty()) {
            return;
        }
        for (Entry e : new ArrayList<>(declaredWaiters)) {
            if (e.tracker.state() != PreLoginTracker.State.STAGED || e.tracker.expired()) {
                abandonDeclared(e);
            }
        }
        if (declaredWaiters.isEmpty()) {
            return;
        }
        Map<InetAddress, Long> byCoarse = bytesByCoarse();
        Map<InetAddress, Long> byFine = bytesByFine();
        List<Entry> order = new ArrayList<>(declaredWaiters);
        order.sort(Comparator.<Entry>comparingLong(e -> byCoarse.getOrDefault(e.coarse, 0L))
                .thenComparingLong(e -> byFine.getOrDefault(e.fine, 0L))
                .thenComparingLong(e -> e.arrival)
                .thenComparingLong(e -> e.id));
        for (Entry e : order) {
            if (e.pendingGranted || declaredInFlight(e.coarse) + e.pendingTotal > declaredBytesPerPrefix) {
                continue;
            }
            if (e.tracker.chargeDeclaredFrame(e.pendingDeclared, e.pendingFraming)
                    == PreLoginTracker.ChargeResult.CHARGED) {
                e.declaredExtra += e.pendingTotal;
                e.pendingGranted = true;
            }
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
     * A staged connection as the registry sees it. It is also the {@link PreLoginConnection} the platform
     * and the V3 read path drive. Its methods lock the registry, which is the only lock the shedding path
     * takes, so a shed on another event loop never races a charge or a pause here.
     */
    final class Entry implements PreLoginConnection, AutoCloseable {
        private final PreLoginTracker tracker;
        private final InetAddress source;
        private final InetAddress coarse;
        private final InetAddress fine;
        private final Runnable closeConnection;
        private int waitDepth;
        private int sessionCalls;
        private long progress;
        /** Declared-length charges this connection holds beyond its staged share (12.13 per-prefix cap). */
        private long declaredExtra;
        /** Connection id: the last tie-break of the wait-set order. */
        private final long id;
        /** Order in which this connection joined the wait set; set when it joins. */
        private long arrival;
        /** The declared-length charge waiting (or granted and unclaimed) for this connection; -1 when none. */
        private long pendingDeclared;
        private long pendingFraming;
        private long pendingTotal = -1;
        private boolean pendingGranted;

        private Entry(PreLoginTracker tracker, InetAddress source, InetAddress coarse, Runnable closeConnection) {
            this.tracker = tracker;
            this.source = source;
            this.coarse = coarse;
            this.fine = finePrefix(source);
            this.closeConnection = Objects.requireNonNull(closeConnection, "closeConnection");
            this.id = nextEntryId++;
        }

        /** The underlying accounting. Package-private: outside code uses the {@link PreLoginConnection} view. */
        PreLoginTracker tracker() {
            synchronized (PreLoginRegistry.this) {
                return tracker;
            }
        }

        /** Records progress (bytes consumed): the least-progressed connection is shed first. */
        @Override
        public void recordProgress(long bytes) {
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
            synchronized (PreLoginRegistry.this) {
                return tracker.expired();
            }
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
            promoteIfNeeded(tracker::onLoginSuccess);
        }

        @Override
        public void onActivation() {
            promoteIfNeeded(tracker::onActivation);
        }

        @Override
        public boolean retryPromotion() {
            AtomicBoolean promoted = new AtomicBoolean();
            promoteIfNeeded(() -> promoted.set(tracker.retryPromotion()));
            return promoted.get();
        }

        @Override
        public PreLoginTracker.ChargeResult chargeDeclaredFrame(long declaredBytes, long framingBytes) {
            synchronized (PreLoginRegistry.this) {
                // Validation, oversize and a non-staged connection take the tracker's own answer.
                if (declaredBytes < 0 || framingBytes < 0 || declaredBytes > PreLoginTracker.MAX_DECLARED_FRAME_BYTES
                        || tracker.state() != PreLoginTracker.State.STAGED) {
                    return tracker.chargeDeclaredFrame(declaredBytes, framingBytes);
                }
                long total = Math.addExact(declaredBytes, framingBytes);
                if (total <= tracker.stagedShare()) {
                    return PreLoginTracker.ChargeResult.CHARGED;
                }
                if (pendingTotal >= 0 && (pendingDeclared != declaredBytes || pendingFraming != framingBytes)) {
                    // The caller moved on to another frame: the earlier request is abandoned.
                    abandonDeclared(this);
                }
                if (tracker.expired()) {
                    // 12.13: the deadlines still run; an expired connection is refused and never granted.
                    abandonDeclared(this);
                    return PreLoginTracker.ChargeResult.WAIT;
                }
                if (pendingTotal >= 0 && pendingGranted) {
                    // The grant made while this frame waited is claimed; its bytes are already charged.
                    dropDeclared(this);
                    return PreLoginTracker.ChargeResult.CHARGED;
                }
                // Grant at once while the pool has room, the prefix is under its per-prefix cap and no other charge
                // is waiting (fair share is not checked against an empty pool, which would stall two equally charged
                // prefixes). Otherwise the charge joins the wait set, and the pass grants it in priority order when
                // bytes free up (12.13). Waiting never sheds anyone else.
                if (declaredWaiters.isEmpty() && declaredInFlight(coarse) + total <= declaredBytesPerPrefix) {
                    PreLoginTracker.ChargeResult result = tracker.chargeDeclaredFrame(declaredBytes, framingBytes);
                    if (result == PreLoginTracker.ChargeResult.CHARGED) {
                        declaredExtra += total;
                        return result;
                    }
                }
                enqueueDeclared(this, declaredBytes, framingBytes, total);
                drainDeclaredWaiters();
                if (pendingGranted) {
                    dropDeclared(this);
                    return PreLoginTracker.ChargeResult.CHARGED;
                }
                return PreLoginTracker.ChargeResult.WAIT;
            }
        }

        @Override
        public boolean chargeEarlyData(long bytes) {
            synchronized (PreLoginRegistry.this) {
                return tracker.chargeEarlyData(bytes);
            }
        }

        @Override
        public void releaseExtra(long bytes) {
            synchronized (PreLoginRegistry.this) {
                // Declared-frame charges are released first, so the per-prefix cap follows completed frames.
                if (bytes > 0) {
                    declaredExtra -= Math.min(bytes, declaredExtra);
                }
                tracker.releaseExtra(bytes);
                drainDeclaredWaiters();
            }
        }

        @Override
        public boolean tryBeginSessionCall() {
            synchronized (PreLoginRegistry.this) {
                if (tracker.state() != PreLoginTracker.State.STAGED || !PreLoginRegistry.this.tryBeginSessionCall(source)) {
                    return false;
                }
                sessionCalls++;
                return true;
            }
        }

        @Override
        public void endSessionCall() {
            synchronized (PreLoginRegistry.this) {
                if (sessionCalls > 0) {
                    sessionCalls--;
                    PreLoginRegistry.this.endSessionCall(source);
                }
            }
        }

        /** Sheds this connection under pool pressure (12.13). Only staged connections are ever in the set. */
        @Override
        public void onShed() {
            synchronized (PreLoginRegistry.this) {
                shed(this, true);
            }
        }

        /** Releases the connection's charge, from either pool, and any session call it still holds. Idempotent. */
        @Override
        public void close() {
            synchronized (PreLoginRegistry.this) {
                while (sessionCalls > 0) {
                    endSessionCall();
                }
                forget(this);
                tracker.close();
                drainDeclaredWaiters();
            }
        }

        private void promoteIfNeeded(Runnable promotion) {
            synchronized (PreLoginRegistry.this) {
                promotion.run();
                if (tracker.state() != PreLoginTracker.State.STAGED) {
                    forget(this);
                    drainDeclaredWaiters();
                }
            }
        }
    }
}
