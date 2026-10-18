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
import rs.sudoe.quicraft.core.time.StallClock;

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
 * <p>The caller supplies monotonic nanosecond timestamps to the timestamped operations, and local
 * executor stalls are reported with {@link #stallStart} and {@link #stallEnd}; their time is excluded
 * from byte-seconds. A ranking constructed with a {@link StallClock} instead takes one coherent reading
 * of that source per operation ({@link #connect(Object, InetAddress, String)}, {@link #disconnect(Object)},
 * {@link #setRetained(Object, long)}, {@link #progress(Object, long)}, {@link #beginRefusedWait(Object)},
 * {@link #score(Object)}, {@link #bucketScore(int)} and {@link #view()}), and its byte-second decay is
 * excluded through the source's cumulative stall counter. The two modes never mix: timestamped
 * operations, {@link #view(LongSupplier)}, {@link #stallStart(long)} and {@link #stallEnd(long)} throw
 * {@link IllegalStateException} on a cumulative ranking, and the operations without a timestamp throw on
 * a timestamped ranking. Untimed inspection and {@link #endRefusedWait(Object)} work in both modes.
 * Progress windows always use transport time; only byte-second integration and decay exclude stalls.</p>
 *
 * <p>Every cumulative operation acquires this ranking's monitor before it samples the source, validates
 * the reading by subtraction (backwards time, a backwards counter and an excluded delta larger than the
 * elapsed delta are rejected; signed wrap is fine), and then uses that one pair for every history it
 * touches. An invalid or malformed reading throws {@link StallClock.UnavailableException} and mutates
 * nothing. The one exception is {@link #disconnect(Object)}: releasing a known connection may use a
 * coherent invalid reading for its integrate-to-zero, live release and removal, never for scoring or
 * selection, and it commits that reading as the last-used pair so later cleanups cannot validate behind
 * histories that were already mutated. Lock order is always ranking then source; a source never calls a
 * consumer. Thread-safe.</p>
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
    /** Bounded continuity records of overflowed IP identities, access-ordered by last use (13.12). */
    private final Map<InetAddress, Continuity> overflowMemory = new LinkedHashMap<InetAddress, Continuity>(16, 0.75f, true);
    /** Bounded continuity records of accounts turned away from an exact entry, access-ordered by last use (13.12). */
    private final Map<String, Continuity> accountOverflow = new LinkedHashMap<String, Continuity>(16, 0.75f, true);
    /** Connections currently in a refused wait, whose refused-wait floor lifts their identities (13.12). */
    private final Set<Connection> refusedWaiters = new HashSet<Connection>();
    private final IdentityHistory[] buckets = new IdentityHistory[OVERFLOW];
    private final List<Map<Object, Integer>> bucketLive = new ArrayList<Map<Object, Integer>>(OVERFLOW);
    private final Map<Object, Connection> connections = new HashMap<Object, Connection>();
    /** Cumulative stall source; null on a timestamped ranking. One source is one incarnation. */
    private final StallClock source;
    private long stallTotal;
    private long stallBegan = -1;
    /** Cumulative mode: whether a validated reading is the reference for later differences. */
    private boolean clockInitialized;
    private long lastNowNanos;
    private long lastExcludedNanos;

    /** Creates a ranking with the 13.12 defaults: 60 s half-life, 131072 exact IPs and accounts, random secret. */
    public PressureRanking() {
        this(60_000L, 131_072, 131_072, newSecret());
    }

    /**
     * Creates a timestamped ranking whose stalls are reported with {@link #stallStart} and {@link #stallEnd}.
     *
     * @param halfLifeMillis byte-second half-life (13.12 provisional default 60 s)
     * @param maxTrackedIps exact IP entries kept (raised together with maxOpen at configuration)
     * @param maxTrackedAccounts exact account entries kept
     * @param secret per-process key for the keyed overflow buckets
     */
    public PressureRanking(long halfLifeMillis, int maxTrackedIps, int maxTrackedAccounts, byte[] secret) {
        this(halfLifeMillis, maxTrackedIps, maxTrackedAccounts, secret, null, false);
    }

    /**
     * Creates a cumulative ranking that takes one reading of {@code source} per operation, with the
     * 13.12 defaults.
     *
     * @param source the stall accounting source of the executor incarnation, never replaced
     */
    public PressureRanking(StallClock source) {
        this(60_000L, 131_072, 131_072, newSecret(), source);
    }

    /**
     * Creates a cumulative ranking.
     *
     * @param halfLifeMillis byte-second half-life (13.12 provisional default 60 s)
     * @param maxTrackedIps exact IP entries kept (raised together with maxOpen at configuration)
     * @param maxTrackedAccounts exact account entries kept
     * @param secret per-process key for the keyed overflow buckets
     * @param source the stall accounting source of the executor incarnation, never replaced
     */
    public PressureRanking(long halfLifeMillis, int maxTrackedIps, int maxTrackedAccounts, byte[] secret,
            StallClock source) {
        this(halfLifeMillis, maxTrackedIps, maxTrackedAccounts, secret, source, true);
    }

    private PressureRanking(long halfLifeMillis, int maxTrackedIps, int maxTrackedAccounts, byte[] secret,
            StallClock source, boolean cumulative) {
        if (halfLifeMillis <= 0 || maxTrackedIps < 1 || maxTrackedAccounts < 1) throw new IllegalArgumentException("ranking");
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("secret");
        if (cumulative && source == null) throw new IllegalArgumentException("source");
        this.halfLifeNanos = halfLifeMillis * 1_000_000L;
        this.maxTrackedIps = maxTrackedIps;
        this.maxTrackedAccounts = maxTrackedAccounts;
        this.secret = secret.clone();
        this.source = source;
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
        requireTimestamped("view(LongSupplier)");
        return new HolderRank() {
            @Override public double score(Object owner) {
                long now = clock.getAsLong();
                synchronized (PressureRanking.this) {
                    return scoreInternal(owner, now, effStall(now));
                }
            }
        };
    }

    /**
     * Returns a {@link HolderRank} that takes one fresh cumulative reading per score: the returned rank
     * never captures or reuses a sample, so every victim choice sees current accounting.
     */
    public HolderRank view() {
        requireCumulative("view()");
        return new HolderRank() {
            @Override public double score(Object owner) {
                return PressureRanking.this.score(owner);
            }
        };
    }

    /** Marks the start of a local game-executor stall; its time is excluded from byte-seconds. */
    public synchronized void stallStart(long nowNanos) {
        requireTimestamped("stallStart(long)");
        if (stallBegan < 0) stallBegan = nowNanos;
    }

    /** Marks the end of the stall started with {@link #stallStart}. */
    public synchronized void stallEnd(long nowNanos) {
        requireTimestamped("stallEnd(long)");
        if (stallBegan >= 0) {
            stallTotal += Math.max(0, nowNanos - stallBegan);
            stallBegan = -1;
        }
    }

    /**
     * Starts a connection's statistics. Identity histories persist across reconnects; only the
     * connection-local statistics restart (13.12). Timestamped form.
     *
     * @param account authenticated account name, or {@code null} in offline mode or before authentication
     */
    public synchronized void connect(Object connection, InetAddress ip, String account, long nowNanos) {
        requireTimestamped("connect(Object, InetAddress, String, long)");
        connectInternal(connection, ip, account, nowNanos, effStall(nowNanos));
    }

    /**
     * Starts a connection's statistics from one cumulative reading of the stall source. Identity
     * histories persist across reconnects; only the connection-local statistics restart (13.12). A
     * duplicate or null argument is rejected before the reading, so a refused call leaves both the
     * validation pair and the accounting untouched.
     *
     * @param account authenticated account name, or {@code null} in offline mode or before authentication
     */
    public synchronized void connect(Object connection, InetAddress ip, String account) {
        requireCumulative("connect(Object, InetAddress, String)");
        if (connection == null || ip == null) throw new IllegalArgumentException("identity");
        if (connections.containsKey(connection)) throw new IllegalStateException("connection already registered");
        StallClock.Sample sample = takeSample();
        connectInternal(connection, ip, account, sample.nowNanos(), sample.excludedNanos());
    }

    private void connectInternal(Object connection, InetAddress ip, String account, long nowNanos, long eff) {
        if (connection == null || ip == null) throw new IllegalArgumentException("identity");
        if (connections.containsKey(connection)) throw new IllegalStateException("connection already registered");
        PrefixKey ipKey = PrefixKey.overflow(ip);
        int ipBucket = ipKey.bucket(secret);
        int accountBucket = account == null ? -1 : PrefixKey.accountBucket(secret, account);
        IdentityHistory ipExact = exactIp(ip, nowNanos, eff);
        IdentityHistory accountExact = account == null ? null : exactAccount(account, nowNanos, eff);
        Connection c = new Connection(connection, new IdentityHistory(halfLifeNanos, nowNanos, eff), nowNanos);
        c.addr = ip;
        c.acct = account;

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
        if (ipExact == null) {
            c.ipRecord = overflowMemory.get(ip);
            if (c.ipRecord != null) c.ipRecord.live++;
        }
        if (account != null && accountExact == null) {
            c.acctRecord = accountOverflow.get(account);
            if (c.acctRecord != null) c.acctRecord.live++;
        }
        if (c.liveBucket >= 0) {
            Map<Object, Integer> live = bucketLive.get(c.liveBucket);
            Integer count = live.get(c.liveKey);
            live.put(c.liveKey, count == null ? 1 : count + 1);
        }
        connections.put(connection, c);
    }

    /** Ends a connection: its retained bytes are integrated to zero and its live counts are released. */
    public synchronized void disconnect(Object connection, long nowNanos) {
        requireTimestamped("disconnect(Object, long)");
        disconnectInternal(connection, nowNanos, effStall(nowNanos));
    }

    /**
     * Ends a connection from one cumulative reading. The reading is taken once, inside this ranking's
     * monitor. A known connection is released even from a coherent invalid reading, because releasing
     * already-mutated state must stay possible; that reading is then committed as the last-used pair so
     * later cleanups cannot validate behind the histories just mutated. A malformed reading releases
     * nothing, and an unknown connection is a no-op that takes no reading.
     */
    public synchronized void disconnect(Object connection) {
        requireCumulative("disconnect(Object)");
        if (!connections.containsKey(connection)) {
            return;
        }
        StallClock.Sample sample = readSample(true);
        disconnectInternal(connection, sample.nowNanos(), sample.excludedNanos());
        commitSample(sample);
    }

    private void disconnectInternal(Object connection, long nowNanos, long eff) {
        Connection c = connections.remove(connection);
        if (c == null) return;
        refusedWaiters.remove(c);
        if (c.bytes != 0) setBytesInternal(c, 0, nowNanos, eff);
        touchContinuity(c, nowNanos);
        if (c.ipRecord != null) c.ipRecord.live--;
        if (c.acctRecord != null) c.acctRecord.live--;
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
        requireTimestamped("setRetained(Object, long, long)");
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        setBytesInternal(require(connection), bytes, nowNanos, effStall(nowNanos));
    }

    /**
     * Sets the connection's absolute retained bytes from one cumulative reading: the previous amount is
     * integrated through that reading's pair before the new amount is installed.
     */
    public synchronized void setRetained(Object connection, long bytes) {
        requireCumulative("setRetained(Object, long)");
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        Connection c = require(connection);
        StallClock.Sample sample = takeSample();
        setBytesInternal(c, bytes, sample.nowNanos(), sample.excludedNanos());
    }

    /** Records newly received or acknowledged progress bytes, counted once per connection and identity. */
    public synchronized void progress(Object connection, long bytes, long nowNanos) {
        requireTimestamped("progress(Object, long, long)");
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        progressInternal(require(connection), bytes, nowNanos);
    }

    /**
     * Records newly received or acknowledged progress bytes from one cumulative reading. Progress
     * windows stay on transport time: they are not stall-excluded.
     */
    public synchronized void progress(Object connection, long bytes) {
        requireCumulative("progress(Object, long)");
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        Connection c = require(connection);
        StallClock.Sample sample = takeSample();
        progressInternal(c, bytes, sample.nowNanos());
    }

    private void progressInternal(Connection c, long bytes, long nowNanos) {
        c.local.progress(bytes, nowNanos);
        for (IdentityHistory target : c.charged) target.progress(bytes, nowNanos);
    }

    /**
     * Starts a refused wait for an inbound requester's own incomplete record (13.12): the connection-local
     * progress rate is floored at the rate over the 10 seconds before this first refusal. Timestamped form.
     */
    public synchronized void beginRefusedWait(Object connection, long nowNanos) {
        requireTimestamped("beginRefusedWait(Object, long)");
        beginRefusedWaitInternal(require(connection), nowNanos);
    }

    /** Starts a refused wait from one cumulative reading of the stall source. */
    public synchronized void beginRefusedWait(Object connection) {
        requireCumulative("beginRefusedWait(Object)");
        Connection c = require(connection);
        StallClock.Sample sample = takeSample();
        beginRefusedWaitInternal(c, sample.nowNanos());
    }

    private void beginRefusedWaitInternal(Connection c, long nowNanos) {
        if (!c.refusedWait) {
            c.refusedWait = true;
            // A connection younger than the window at its first refusal uses the live rate: no floor.
            boolean established = nowNanos - c.startNanos >= IdentityHistory.WINDOW_SECONDS * 1_000_000_000L;
            c.floorRate = established ? c.local.rate(nowNanos, 0) : 0;
            refusedWaiters.add(c);
        }
    }

    /** Ends a refused wait and drops its progress floor. Works in both modes: it needs no timestamp. */
    public synchronized void endRefusedWait(Object connection) {
        Connection c = require(connection);
        c.refusedWait = false;
        c.floorRate = 0;
        refusedWaiters.remove(c);
    }

    /** True while the connection is in a refused wait; false for an unknown connection. */
    public synchronized boolean inRefusedWait(Object connection) {
        Connection c = connections.get(connection);
        return c != null && c.refusedWait;
    }

    /** Worst-of pressure score of a connection at {@code nowNanos} (13.12); 0 for an unknown connection. */
    public synchronized double score(Object connection, long nowNanos) {
        requireTimestamped("score(Object, long)");
        return scoreInternal(connection, nowNanos, effStall(nowNanos));
    }

    /**
     * Worst-of pressure score of a connection from one cumulative reading (13.12); 0 for an unknown
     * connection, which takes no reading at all.
     */
    public synchronized double score(Object connection) {
        requireCumulative("score(Object)");
        Connection c = connections.get(connection);
        if (c == null) return 0;
        StallClock.Sample sample = takeSample();
        return scoreInternal(connection, sample.nowNanos(), sample.excludedNanos());
    }

    private double scoreInternal(Object connection, long nowNanos, long eff) {
        Connection c = connections.get(connection);
        if (c == null) return 0;
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

    /** Continuity records kept for overflowed IP and account identities; bounded by the tracking capacities. */
    synchronized int continuityEntries() { return overflowMemory.size() + accountOverflow.size(); }

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
        requireTimestamped("bucketScore(int, long)");
        return bucketScoreInternal(bucket, nowNanos, effStall(nowNanos));
    }

    /** Decayed score of an overflow bucket from one cumulative reading. */
    public synchronized double bucketScore(int bucket) {
        requireCumulative("bucketScore(int)");
        requireBucket(bucket);
        StallClock.Sample sample = takeSample();
        return bucketScoreInternal(bucket, sample.nowNanos(), sample.excludedNanos());
    }

    private double bucketScoreInternal(int bucket, long nowNanos, long eff) {
        return buckets[bucket] == null ? 0 : buckets[bucket].score(nowNanos, eff, 0);
    }

    /**
     * Rejects a bucket index outside the keyed bucket array before any reading is taken, with the same
     * exception type the out-of-range array access would raise, so a refused call cannot advance the
     * validation pair.
     */
    private static void requireBucket(int bucket) {
        if (bucket < 0 || bucket >= OVERFLOW) {
            throw new ArrayIndexOutOfBoundsException(bucket);
        }
    }

    /**
     * Reads one sample of the cumulative source and validates it against the last accepted reading.
     * Differences are plain subtractions, so signed clock and counter wrap are accepted and only
     * backwards or self-contradicting readings are rejected. Nothing is committed here: the caller
     * commits after its own preconditions passed.
     *
     * @param cleanup true only for release of a known connection: a coherent invalid reading is then
     *        usable, while a malformed one still throws
     */
    private StallClock.Sample readSample(boolean cleanup) {
        StallClock.Sample sample = source.sample();
        if (!sample.valid() && !cleanup) {
            throw new StallClock.UnavailableException("stall source is not valid");
        }
        long now = sample.nowNanos();
        long excluded = sample.excludedNanos();
        if (clockInitialized) {
            long elapsed = now - lastNowNanos;
            long delta = excluded - lastExcludedNanos;
            if (elapsed < 0 || delta < 0 || delta > elapsed) {
                throw new StallClock.UnavailableException("stall source went backwards or excluded more time than it spent");
            }
        }
        return sample;
    }

    /** One valid, validated cumulative reading, committed as the last-used pair before it is used. */
    private StallClock.Sample takeSample() {
        StallClock.Sample sample = readSample(false);
        commitSample(sample);
        return sample;
    }

    /**
     * Makes a reading the reference for later differences. Cleanup commits the same pair as its
     * mutation, so a later validation can never reach behind already-mutated histories.
     */
    private void commitSample(StallClock.Sample sample) {
        clockInitialized = true;
        lastNowNanos = sample.nowNanos();
        lastExcludedNanos = sample.excludedNanos();
    }

    /** Rejects a timestamped operation on a cumulative ranking before anything can be mutated. */
    private void requireTimestamped(String operation) {
        if (source != null) {
            throw new IllegalStateException(operation + " is timestamped; this ranking samples its stall source");
        }
    }

    /** Rejects a cumulative operation on a timestamped ranking before anything can be mutated. */
    private void requireCumulative(String operation) {
        if (source == null) {
            throw new IllegalStateException(operation + " needs a ranking constructed with a stall source");
        }
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
        if (delta != 0) touchContinuity(c, now);
    }

    /**
     * Refreshes the last-use time of a connection's overflowed identities (13.12): the 10-minute retention counts
     * from last use, so a long-lived overflow connection keeps its continuity record while its charge changes.
     */
    private void touchContinuity(Connection c, long now) {
        if (c.ipRecord != null) c.ipRecord.lastUse = now;
        if (c.acctRecord != null) c.acctRecord.lastUse = now;
    }

    /** Returns the exact IP entry, or null when the identity is served by its overflow bucket. */
    private IdentityHistory exactIp(InetAddress ip, long now, long eff) {
        IdentityHistory existing = ips.get(ip);
        if (existing != null) return existing;
        Continuity remembered = overflowMemory.get(ip);
        if (remembered != null && stickyOverflow(remembered, now)) {
            remembered.lastUse = now;
            return null;
        }
        if (ips.size() >= maxTrackedIps && !makeIpRoom(now, eff)) {
            overflowMemory.put(ip, new Continuity(PrefixKey.overflow(ip).bucket(secret), now));
            trimContinuity(overflowMemory, maxTrackedIps, now);
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
        Continuity remembered = accountOverflow.get(account);
        if (remembered != null && stickyOverflow(remembered, now)) {
            remembered.lastUse = now;
            return null;
        }
        if (accounts.size() >= maxTrackedAccounts) {
            removeExpiredAccounts(now);
            if (accounts.size() >= maxTrackedAccounts) {
                accountOverflow.put(account, new Continuity(PrefixKey.accountBucket(secret, account), now));
                trimContinuity(accountOverflow, maxTrackedAccounts, now);
                return null;
            }
        }
        accountOverflow.remove(account);
        IdentityHistory created = new IdentityHistory(halfLifeNanos, now, eff);
        accounts.put(account, created);
        return created;
    }

    /**
     * An overflowed identity keeps using its bucket (13.12): while it has a live connection there, and until its
     * bucket has no live users and its 10-minute inactive history expires, reconnects map back there even when
     * exact slots are free.
     */
    private boolean stickyOverflow(Continuity record, long now) {
        return record.live > 0 || now - record.lastUse < IdentityHistory.RETENTION_NANOS
                || !bucketLive.get(record.bucket).isEmpty();
    }

    /**
     * Trims a continuity map to its bound (13.12): the oldest expired record first, then the oldest record whose
     * bucket has live users. Only when neither exists (every record is recent and idle) is the oldest one
     * trimmed, so the bound always holds. Under churn beyond the bound, overflowed identities can lose continuity.
     */
    private <K> void trimContinuity(Map<K, Continuity> map, int bound, long now) {
        while (map.size() > bound) {
            K victim = null;
            for (Map.Entry<K, Continuity> entry : map.entrySet()) {
                if (!stickyOverflow(entry.getValue(), now)) {
                    victim = entry.getKey();
                    break;
                }
            }
            if (victim == null) {
                for (Map.Entry<K, Continuity> entry : map.entrySet()) {
                    if (!bucketLive.get(entry.getValue().bucket).isEmpty()) {
                        victim = entry.getKey();
                        break;
                    }
                }
            }
            if (victim == null) victim = map.keySet().iterator().next();
            map.remove(victim);
        }
    }

    /** Frees one exact IP slot: expired entries first, then evict the lowest-score unprotected inactive entry. */
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
        // The evicted identity keeps its bucket on reconnect; its history is already absorbed into that bucket.
        overflowMemory.put(victim, new Continuity(bucket, now));
        trimContinuity(overflowMemory, maxTrackedIps, now);
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

    /** Bounded continuity record of an overflowed identity: its bucket and last use (13.12). */
    private static final class Continuity {
        final int bucket;
        long lastUse;
        /** Live connections of this identity served by the bucket: while above zero the record stays sticky. */
        int live;

        Continuity(int bucket, long lastUse) {
            this.bucket = bucket;
            this.lastUse = lastUse;
        }
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
        InetAddress addr;
        String acct;
        /** Continuity records this connection holds live (13.12); null when the identity has an exact entry. */
        Continuity ipRecord;
        Continuity acctRecord;
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
