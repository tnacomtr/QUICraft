// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Pressure ranking for one direction-specific pool (protocol.md 13.12 "Pressure ranking").
 *
 * <p>Tracks, per exact source IP and per authenticated account, decayed byte-seconds and progress, plus
 * connection-local statistics that end with the connection. A connection's score is the worst of its
 * connection-local, IP and account scores. An identity without an exact entry is served by one of
 * {@value PrefixKey#OVERFLOW_BUCKETS} keyed overflow buckets and receives only an equal share of the
 * bucket score (see {@link #overflowScore}). Exact entries are protected while an account recently used
 * them. This ranking never refuses admission: it only orders victims.</p>
 *
 * <p>The caller supplies monotonic nanosecond timestamps. Local executor stalls are reported with
 * {@link #stallStart} and {@link #stallEnd}; their time is excluded from byte-seconds. Thread-safe.</p>
 */
public final class PressureRanking {
    private static final int OVERFLOW = PrefixKey.OVERFLOW_BUCKETS;
    private static final int PROTECTED_IPS_PER_ACCOUNT = 4;

    private final long halfLifeNanos;
    private final int maxTrackedIps;
    private final int maxTrackedAccounts;
    private final byte[] secret;
    private final Map<InetAddress, IdentityHistory> ips = new HashMap<InetAddress, IdentityHistory>();
    private final Map<String, IdentityHistory> accounts = new HashMap<String, IdentityHistory>();
    private final Map<String, List<InetAddress>> accountRecentIps = new HashMap<String, List<InetAddress>>();
    /** Evicted or overflowed IP identities and the time each was last served by its bucket (13.12 continuity). */
    private final Map<InetAddress, Long> overflowMemory = new LinkedHashMap<InetAddress, Long>(16, 0.75f, true);
    /** Accounts turned away from an exact entry, with the time each was last served by its bucket. */
    private final Map<String, Long> accountOverflow = new LinkedHashMap<String, Long>(16, 0.75f, true);
    /** Connections currently in a refused wait, whose refused-wait floor lifts their identities (13.12). */
    private final Set<Connection> refusedWaiters = new HashSet<Connection>();
    private final IdentityHistory[] buckets = new IdentityHistory[OVERFLOW];
    private final List<Map<Object, Integer>> bucketLive = new ArrayList<Map<Object, Integer>>(OVERFLOW);
    private final Map<Object, Connection> connections = new HashMap<Object, Connection>();
    private long stallTotal;
    private long stallBegan = -1;

    /** Creates a ranking with the 13.12 defaults: 60 s half-life, 131072 exact IPs and accounts, random secret. */
    public PressureRanking() {
        this(60_000L, 131_072, 131_072, newSecret());
    }

    /**
     * Creates a ranking.
     *
     * @param halfLifeMillis byte-second half-life (13.12 provisional default 60 s)
     * @param maxTrackedIps exact IP entries kept (raised together with maxOpen at configuration)
     * @param maxTrackedAccounts exact account entries kept
     * @param secret per-process key for the keyed overflow buckets
     */
    public PressureRanking(long halfLifeMillis, int maxTrackedIps, int maxTrackedAccounts, byte[] secret) {
        if (halfLifeMillis <= 0 || maxTrackedIps < 1 || maxTrackedAccounts < 1) throw new IllegalArgumentException("ranking");
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("secret");
        this.halfLifeNanos = halfLifeMillis * 1_000_000L;
        this.maxTrackedIps = maxTrackedIps;
        this.maxTrackedAccounts = maxTrackedAccounts;
        this.secret = secret.clone();
        for (int i = 0; i < OVERFLOW; i++) bucketLive.add(new HashMap<Object, Integer>());
    }

    /**
     * Identity score with an overflow share (13.12 "Overflow rank"):
     * {@code connLocal + min(bucketScore, max(connLocal, bucketScore / liveIdentities))}.
     */
    public static double overflowScore(double connectionLocal, double bucketScore, int liveIdentities) {
        if (connectionLocal < 0 || bucketScore < 0 || liveIdentities < 1) throw new IllegalArgumentException("score");
        double share = Math.min(bucketScore, Math.max(connectionLocal, bucketScore / liveIdentities));
        return connectionLocal + share;
    }

    /** Returns a {@link HolderRank} that scores holders at the time reported by {@code clock}. */
    public HolderRank view(final LongSupplier clock) {
        return new HolderRank() {
            @Override public double score(Object owner) {
                return PressureRanking.this.score(owner, clock.getAsLong());
            }
        };
    }

    /** Marks the start of a local game-executor stall; its time is excluded from byte-seconds. */
    public synchronized void stallStart(long nowNanos) {
        if (stallBegan < 0) stallBegan = nowNanos;
    }

    /** Marks the end of the stall started with {@link #stallStart}. */
    public synchronized void stallEnd(long nowNanos) {
        if (stallBegan >= 0) {
            stallTotal += Math.max(0, nowNanos - stallBegan);
            stallBegan = -1;
        }
    }

    /**
     * Starts a connection's statistics. Identity histories persist across reconnects; only the
     * connection-local statistics restart (13.12).
     *
     * @param account authenticated account name, or {@code null} in offline mode or before authentication
     */
    public synchronized void connect(Object connection, InetAddress ip, String account, long nowNanos) {
        if (connection == null || ip == null) throw new IllegalArgumentException("identity");
        if (connections.containsKey(connection)) throw new IllegalStateException("connection already registered");
        long eff = effStall(nowNanos);
        PrefixKey ipKey = PrefixKey.overflow(ip);
        int ipBucket = ipKey.bucket(secret);
        int accountBucket = account == null ? -1 : PrefixKey.accountBucket(secret, account);
        IdentityHistory ipExact = exactIp(ip, nowNanos, eff);
        IdentityHistory accountExact = account == null ? null : exactAccount(account, nowNanos, eff);
        Connection c = new Connection(connection, new IdentityHistory(halfLifeNanos, nowNanos, eff), nowNanos);

        if (ipExact == null && account != null && accountExact == null) {
            // Both identities overflowed: one charge, to the account bucket, read by both (13.12).
            Read shared = new Read(overflow(accountBucket, nowNanos, eff), accountBucket);
            c.ip = shared;
            c.account = shared;
            c.charged.add(shared.history);
            c.liveBucket = accountBucket;
            c.liveKey = "account:" + account;
        } else if (ipExact == null && account == null) {
            c.ip = new Read(overflow(ipBucket, nowNanos, eff), ipBucket);
            c.charged.add(c.ip.history);
            c.liveBucket = ipBucket;
            c.liveKey = ipKey;
        } else {
            c.ip = ipExact != null ? new Read(ipExact, -1) : new Read(overflow(ipBucket, nowNanos, eff), ipBucket);
            c.charged.add(c.ip.history);
            if (account != null) {
                c.account = accountExact != null ? new Read(accountExact, -1)
                        : new Read(overflow(accountBucket, nowNanos, eff), accountBucket);
                if (c.account.history != c.ip.history) c.charged.add(c.account.history);
            }
            if (ipExact == null) {
                c.liveBucket = ipBucket;
                c.liveKey = ipKey;
            } else if (account != null && accountExact == null) {
                c.liveBucket = accountBucket;
                c.liveKey = "account:" + account;
            }
            if (account != null && accountExact != null && ipExact != null) touchAccountIp(account, ip);
        }
        for (IdentityHistory target : c.charged) target.liveDelta(1);
        if (c.liveBucket >= 0) {
            Map<Object, Integer> live = bucketLive.get(c.liveBucket);
            Integer count = live.get(c.liveKey);
            live.put(c.liveKey, count == null ? 1 : count + 1);
        }
        connections.put(connection, c);
    }

    /** Ends a connection: its retained bytes are integrated to zero and its live counts are released. */
    public synchronized void disconnect(Object connection, long nowNanos) {
        Connection c = connections.remove(connection);
        if (c == null) return;
        refusedWaiters.remove(c);
        if (c.bytes != 0) setBytesInternal(c, 0, nowNanos, effStall(nowNanos));
        for (IdentityHistory target : c.charged) target.liveDelta(-1);
        if (c.liveBucket >= 0) {
            Map<Object, Integer> live = bucketLive.get(c.liveBucket);
            Integer count = live.get(c.liveKey);
            if (count == null) throw new IllegalStateException("live key missing");
            if (count == 1) live.remove(c.liveKey); else live.put(c.liveKey, count - 1);
        }
    }

    /** Sets the connection's absolute retained bytes at {@code nowNanos}; the previous amount is integrated first. */
    public synchronized void setRetained(Object connection, long bytes, long nowNanos) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        setBytesInternal(require(connection), bytes, nowNanos, effStall(nowNanos));
    }

    /** Records newly received or acknowledged progress bytes, counted once per connection and identity. */
    public synchronized void progress(Object connection, long bytes, long nowNanos) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        Connection c = require(connection);
        c.local.progress(bytes, nowNanos);
        for (IdentityHistory target : c.charged) target.progress(bytes, nowNanos);
    }

    /**
     * Starts a refused wait for an inbound requester's own incomplete record (13.12): the connection-local
     * progress rate is floored at the rate over the 10 seconds before this first refusal.
     */
    public synchronized void beginRefusedWait(Object connection, long nowNanos) {
        Connection c = require(connection);
        if (!c.refusedWait) {
            c.refusedWait = true;
            // A connection younger than the window at its first refusal uses the live rate: no floor.
            boolean established = nowNanos - c.startNanos >= IdentityHistory.WINDOW_SECONDS * 1_000_000_000L;
            c.floorRate = established ? c.local.rate(nowNanos, 0) : 0;
            refusedWaiters.add(c);
        }
    }

    /** Ends a refused wait and drops its progress floor. */
    public synchronized void endRefusedWait(Object connection) {
        Connection c = require(connection);
        c.refusedWait = false;
        c.floorRate = 0;
        refusedWaiters.remove(c);
    }

    /** Worst-of pressure score of a connection at {@code nowNanos} (13.12); 0 for an unknown connection. */
    public synchronized double score(Object connection, long nowNanos) {
        Connection c = connections.get(connection);
        if (c == null) return 0;
        long eff = effStall(nowNanos);
        // Refused-wait floor (13.12): this connection's rate is at least its pre-wait rate. The shortfall of
        // every refused waiter charged to an identity lifts that identity's progress, so siblings sharing it
        // cannot hide a stalled holder behind cheap records.
        double local = c.local.score(nowNanos, eff, floorShortfall(c, nowNanos));
        double worst = local;
        worst = Math.max(worst, readScore(c.ip, local, nowNanos, eff, identityShortfall(c.ip.history, nowNanos)));
        if (c.account != null) {
            worst = Math.max(worst, readScore(c.account, local, nowNanos, eff, identityShortfall(c.account.history, nowNanos)));
        }
        return worst;
    }

    /** Refused-wait shortfall of one connection: how far its floor rate lifts its live rate. */
    private static double floorShortfall(Connection c, long nowNanos) {
        return c.refusedWait ? Math.max(0, c.floorRate - c.local.rate(nowNanos, 0)) : 0;
    }

    /** Sum of refused-wait shortfalls of every waiting connection charged to one identity history. */
    private double identityShortfall(IdentityHistory target, long nowNanos) {
        double total = 0;
        for (Connection waiter : refusedWaiters) {
            if (waiter.charged.contains(target)) total += floorShortfall(waiter, nowNanos);
        }
        return total;
    }

    /** Number of exact IP entries currently held. */
    public synchronized int exactIpCount() { return ips.size(); }

    /** Number of exact account entries currently held. */
    public synchronized int exactAccountCount() { return accounts.size(); }

    /** Keyed overflow bucket that an IP address maps to, in [0, 256). */
    public int bucketFor(InetAddress ip) { return PrefixKey.overflow(ip).bucket(secret); }

    /** Keyed overflow bucket that an account maps to, in [0, 256). */
    public int bucketFor(String account) { return PrefixKey.accountBucket(secret, account); }

    /** Distinct identities with a live connection charged to an overflow bucket. */
    public synchronized int liveIdentities(int bucket) { return bucketLive.get(bucket).size(); }

    /** Bytes currently charged to an overflow bucket. */
    public synchronized long bucketBytes(int bucket) {
        return buckets[bucket] == null ? 0 : buckets[bucket].bytes();
    }

    /** Decayed score of an overflow bucket at {@code nowNanos}. */
    public synchronized double bucketScore(int bucket, long nowNanos) {
        return buckets[bucket] == null ? 0 : buckets[bucket].score(nowNanos, effStall(nowNanos), 0);
    }

    private double readScore(Read read, double local, long now, long eff, double extra) {
        double identity = read.history.score(now, eff, extra);
        if (read.bucket < 0) return identity;
        int live = Math.max(1, bucketLive.get(read.bucket).size());
        return overflowScore(local, identity, live);
    }

    private void setBytesInternal(Connection c, long bytes, long now, long eff) {
        long delta = bytes - c.bytes;
        c.local.addBytes(delta, now, eff);
        for (IdentityHistory target : c.charged) target.addBytes(delta, now, eff);
        c.bytes = bytes;
    }

    /** Returns the exact IP entry, or null when the identity is served by its overflow bucket. */
    private IdentityHistory exactIp(InetAddress ip, long now, long eff) {
        IdentityHistory existing = ips.get(ip);
        if (existing != null) return existing;
        Long remembered = overflowMemory.get(ip);
        if (remembered != null && stickyOverflow(remembered, PrefixKey.overflow(ip).bucket(secret), now)) {
            overflowMemory.put(ip, now);
            return null;
        }
        if (ips.size() >= maxTrackedIps && !makeIpRoom(now, eff)) {
            overflowMemory.put(ip, now);
            trimOverflowMemory();
            return null;
        }
        overflowMemory.remove(ip);
        IdentityHistory created = new IdentityHistory(halfLifeNanos, now, eff);
        ips.put(ip, created);
        return created;
    }

    private IdentityHistory exactAccount(String account, long now, long eff) {
        IdentityHistory existing = accounts.get(account);
        if (existing != null) return existing;
        Long remembered = accountOverflow.get(account);
        if (remembered != null && stickyOverflow(remembered, PrefixKey.accountBucket(secret, account), now)) {
            accountOverflow.put(account, now);
            return null;
        }
        if (accounts.size() >= maxTrackedAccounts) {
            removeExpiredAccounts(now);
            if (accounts.size() >= maxTrackedAccounts) {
                accountOverflow.put(account, now);
                trimAccountOverflow();
                return null;
            }
        }
        accountOverflow.remove(account);
        IdentityHistory created = new IdentityHistory(halfLifeNanos, now, eff);
        accounts.put(account, created);
        return created;
    }

    /**
     * An overflowed identity keeps using its bucket (13.12): until its bucket has no live users and its
     * 10-minute inactive history expires, reconnects map back there even when exact slots are free.
     */
    private boolean stickyOverflow(long lastServed, int bucket, long now) {
        return now - lastServed < IdentityHistory.RETENTION_NANOS || !bucketLive.get(bucket).isEmpty();
    }

    private void trimAccountOverflow() {
        while (accountOverflow.size() > maxTrackedAccounts) {
            Iterator<String> oldest = accountOverflow.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Frees one exact IP slot: expired entries first, then fold the lowest-score unprotected inactive entry. */
    private boolean makeIpRoom(long now, long eff) {
        Iterator<Map.Entry<InetAddress, IdentityHistory>> it = ips.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InetAddress, IdentityHistory> entry = it.next();
            if (entry.getValue().expired(now)) {
                it.remove();
                forgetFromAccounts(entry.getKey());
            }
        }
        if (ips.size() < maxTrackedIps) return true;
        InetAddress victim = null;
        double victimScore = Double.MAX_VALUE;
        for (Map.Entry<InetAddress, IdentityHistory> entry : ips.entrySet()) {
            IdentityHistory history = entry.getValue();
            if (history.live() != 0 || isProtected(entry.getKey())) continue;
            double score = history.score(now, eff, 0);
            if (victim == null || score < victimScore) {
                victim = entry.getKey();
                victimScore = score;
            }
        }
        if (victim == null) return false;
        IdentityHistory evicted = ips.remove(victim);
        int bucket = PrefixKey.overflow(victim).bucket(secret);
        // The evicted identity keeps its bucket on reconnect; its history is already folded into that bucket.
        overflowMemory.put(victim, now);
        trimOverflowMemory();
        overflow(bucket, now, eff).absorb(evicted, now, eff);
        return true;
    }

    private void removeExpiredAccounts(long now) {
        Iterator<Map.Entry<String, IdentityHistory>> it = accounts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, IdentityHistory> entry = it.next();
            if (entry.getValue().expired(now)) {
                it.remove();
                accountRecentIps.remove(entry.getKey());
            }
        }
    }

    private boolean isProtected(InetAddress ip) {
        for (List<InetAddress> recent : accountRecentIps.values()) if (recent.contains(ip)) return true;
        return false;
    }

    private void forgetFromAccounts(InetAddress ip) {
        for (List<InetAddress> recent : accountRecentIps.values()) recent.remove(ip);
    }

    private void touchAccountIp(String account, InetAddress ip) {
        List<InetAddress> recent = accountRecentIps.get(account);
        if (recent == null) {
            recent = new ArrayList<InetAddress>();
            accountRecentIps.put(account, recent);
        }
        recent.remove(ip);
        recent.add(0, ip);
        while (recent.size() > PROTECTED_IPS_PER_ACCOUNT) recent.remove(recent.size() - 1);
    }

    private void trimOverflowMemory() {
        while (overflowMemory.size() > maxTrackedIps) {
            Iterator<InetAddress> oldest = overflowMemory.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private IdentityHistory overflow(int bucket, long now, long eff) {
        if (buckets[bucket] == null) buckets[bucket] = new IdentityHistory(halfLifeNanos, now, eff);
        return buckets[bucket];
    }

    private long effStall(long now) {
        return stallTotal + (stallBegan >= 0 ? Math.max(0, now - stallBegan) : 0);
    }

    private Connection require(Object connection) {
        Connection c = connections.get(connection);
        if (c == null) throw new IllegalStateException("unknown connection");
        return c;
    }

    private static byte[] newSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /** One identity's read target: an exact history ({@code bucket} -1) or an overflow bucket index. */
    private static final class Read {
        final IdentityHistory history;
        final int bucket;

        Read(IdentityHistory history, int bucket) {
            this.history = history;
            this.bucket = bucket;
        }
    }

    /** Per-connection ranking state; identity histories persist beyond it. */
    private static final class Connection {
        final IdentityHistory local;
        final List<IdentityHistory> charged = new ArrayList<IdentityHistory>(2);
        Read ip;
        Read account;
        int liveBucket = -1;
        Object liveKey;
        long bytes;
        boolean refusedWait;
        double floorRate;

        final long startNanos;

        Connection(Object id, IdentityHistory local, long startNanos) {
            this.local = local;
            this.startNanos = startNanos;
        }
    }
}
