// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Byte-based, prefix-fair staged admission backed by the PRE_LOGIN pool (protocol.md 12, pre-login block).
 *
 * <p>Staged bytes of each connection are charged to {@link ResourcePools.Pool#PRE_LOGIN}. Fair share is in
 * bytes per source prefix, computed as hierarchical max-min (water-filling): the pool capacity is split over
 * the broad prefixes (IPv6 /48, IPv4 /24), and each broad prefix's allocation is split over its narrow prefixes
 * (IPv6 /64, IPv4 /24). A reservation is always admitted while the pool has room (work-conserving). When the
 * pool is full, a requester whose narrow prefix is within its allowance may shed charges that are over their
 * share at either level, including other /64s inside its own full /48. Victims are ordered by broad excess
 * first, then narrow excess, then least progress, then a seeded random tie-break. A requester over its narrow
 * allowance is refused rather than allowed to shed. The allocation is computed only on the full-pool path.</p>
 *
 * <p>Promotion from PRE_LOGIN to NODE is atomic, via {@link NodePools#promote}. Thread-safe.</p>
 *
 * <p>Not implemented here (integration owns them): per-prefix declared-length caps, session-server call
 * limits, deadline clocks and the global validated-attempt budget.</p>
 */
public final class PreLoginAdmission {
    private final NodePools pools;
    private final Random random;
    private final Map<Object, Charge> charges = new HashMap<Object, Charge>();
    private final Map<PrefixKey, Long> broadBytes = new HashMap<PrefixKey, Long>();
    private final List<Object> shed = new ArrayList<Object>();
    private long sequence;

    /** Creates the controller with a non-deterministic tie-break. */
    public PreLoginAdmission(NodePools pools) {
        this(pools, new Random());
    }

    /** Creates the controller with an injected tie-break source (tests pass a seeded generator). */
    public PreLoginAdmission(NodePools pools, Random random) {
        if (pools == null || random == null) throw new IllegalArgumentException("pools");
        this.pools = pools;
        this.random = random;
    }

    /**
     * Charges staged bytes for an owner at {@code address}, shedding over-share prefixes if the pool is full.
     *
     * @return true when the bytes are charged; false when the pool is full and the requester's prefix is
     *     at or over its own share (the caller refuses or retries, never sheds itself)
     */
    public synchronized boolean reserve(Object owner, InetAddress address, long bytes) {
        if (owner == null || address == null || bytes < 0) throw new IllegalArgumentException("reservation");
        PrefixKey broad = PrefixKey.broad(address);
        PrefixKey narrow = PrefixKey.narrow(address);
        Charge existing = charges.get(owner);
        if (existing != null && (!existing.broad.equals(broad) || !existing.narrow.equals(narrow))) {
            throw new IllegalArgumentException("owner source prefix changed");
        }
        if (!pools.reserve(ResourcePools.Pool.PRE_LOGIN, owner, bytes)) {
            if (!snapshot(broad, narrow, bytes).admits(broad, narrow)) return false;
            if (!shedUntilRoom(owner, broad, narrow, bytes)) return false;
            if (!pools.reserve(ResourcePools.Pool.PRE_LOGIN, owner, bytes)) return false;
        }
        Charge charge = existing;
        if (charge == null) {
            charge = new Charge(owner, broad, narrow, sequence++, random.nextLong());
            charges.put(owner, charge);
        }
        charge.bytes = add(charge.bytes, bytes);
        adjustBroad(broad, bytes);
        return true;
    }

    /**
     * Atomically moves {@code bytes} of an owner's charge from PRE_LOGIN to NODE (12 pre-login promotion).
     *
     * @return false when the node budget cannot take the bytes; the PRE_LOGIN charge then stays unchanged
     * @throws IllegalStateException when the owner holds less than {@code bytes}
     */
    public synchronized boolean promote(Object owner, long bytes) {
        Charge charge = charges.get(owner);
        if (charge == null || bytes < 0 || bytes > charge.bytes) throw new IllegalStateException("promotion exceeds reservation");
        if (!pools.promote(ResourcePools.Pool.PRE_LOGIN, ResourcePools.Pool.NODE, owner, bytes)) return false;
        removeBytes(charge, bytes);
        return true;
    }

    /** Releases staged bytes once the connection's staged ownership ends. */
    public synchronized void release(Object owner, long bytes) {
        Charge charge = charges.get(owner);
        if (charge == null || bytes < 0 || bytes > charge.bytes) throw new IllegalStateException("release exceeds reservation");
        pools.release(ResourcePools.Pool.PRE_LOGIN, owner, bytes);
        removeBytes(charge, bytes);
    }

    /** Records completed pre-login work; least-progressed connections are shed first within an over-share prefix. */
    public synchronized void progress(Object owner, long bytes) {
        Charge charge = charges.get(owner);
        if (charge == null || bytes < 0) throw new IllegalArgumentException("progress");
        charge.progress = add(charge.progress, bytes);
    }

    /** Staged bytes charged to one owner. */
    public synchronized long reserved(Object owner) {
        Charge charge = charges.get(owner);
        return charge == null ? 0 : charge.bytes;
    }

    /** Staged bytes charged to a broad (/48 or /24) source prefix. */
    public synchronized long reservedByPrefix(InetAddress address) {
        return prefixBytes(PrefixKey.broad(address));
    }

    /** Returns and clears owners whose staged bytes were released for shedding; the integration closes them. */
    public synchronized List<Object> drainShedOwners() {
        List<Object> result = new ArrayList<Object>(shed);
        shed.clear();
        return result;
    }

    /**
     * Frees charges of over-share prefixes until {@code bytes} fit, or returns false when no victim is left. Only
     * the pool's own releasable bytes are counted, so nothing is shed when the shared budget is full elsewhere.
     */
    private boolean shedUntilRoom(Object requester, PrefixKey broad, PrefixKey narrow, long bytes) {
        if (!pools.hasRoom(ResourcePools.Pool.PRE_LOGIN, bytes, pools.reserved(ResourcePools.Pool.PRE_LOGIN))) return false;
        while (!pools.hasRoom(ResourcePools.Pool.PRE_LOGIN, bytes, 0)) {
            Charge victim = snapshot(broad, narrow, bytes).victim(requester);
            if (victim == null) return false;
            pools.release(ResourcePools.Pool.PRE_LOGIN, victim.owner, victim.bytes);
            removeBytes(victim, victim.bytes);
            shed.add(victim.owner);
        }
        return true;
    }

    /** Hierarchical max-min allocation with the requester's {@code bytes} included in its prefixes' demand. */
    private Snapshot snapshot(PrefixKey requesterBroad, PrefixKey requesterNarrow, long bytes) {
        Map<PrefixKey, Long> broadDemand = new HashMap<PrefixKey, Long>(broadBytes);
        Map<PrefixKey, Map<PrefixKey, Long>> narrowDemand = new HashMap<PrefixKey, Map<PrefixKey, Long>>();
        for (Charge charge : charges.values()) addTo(narrowDemand, charge.broad, charge.narrow, charge.bytes);
        addTo(broadDemand, requesterBroad, bytes);
        addTo(narrowDemand, requesterBroad, requesterNarrow, bytes);
        long capacity = pools.limit(ResourcePools.Pool.PRE_LOGIN);
        Map<PrefixKey, Long> broadAlloc = waterFill(broadDemand, capacity);
        Map<PrefixKey, Map<PrefixKey, Long>> narrowAlloc = new HashMap<PrefixKey, Map<PrefixKey, Long>>();
        for (Map.Entry<PrefixKey, Map<PrefixKey, Long>> entry : narrowDemand.entrySet()) {
            narrowAlloc.put(entry.getKey(), waterFill(entry.getValue(), broadAlloc.get(entry.getKey())));
        }
        return new Snapshot(broadDemand, broadAlloc, narrowDemand, narrowAlloc);
    }

    private static void addTo(Map<PrefixKey, Map<PrefixKey, Long>> map, PrefixKey broad, PrefixKey narrow, long bytes) {
        Map<PrefixKey, Long> inside = map.get(broad);
        if (inside == null) {
            inside = new HashMap<PrefixKey, Long>();
            map.put(broad, inside);
        }
        addTo(inside, narrow, bytes);
    }

    private static void addTo(Map<PrefixKey, Long> map, PrefixKey key, long bytes) {
        Long value = map.get(key);
        map.put(key, value == null ? bytes : add(value, bytes));
    }

    /**
     * Max-min water-filling: in ascending demand order each key gets the smaller of its demand and an equal split
     * of what remains. Integer division leaves at most a few bytes unallocated, which errs on the strict side.
     */
    private static <K> Map<K, Long> waterFill(Map<K, Long> demands, long capacity) {
        List<Map.Entry<K, Long>> sorted = new ArrayList<Map.Entry<K, Long>>(demands.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<K, Long>>() {
            @Override public int compare(Map.Entry<K, Long> left, Map.Entry<K, Long> right) {
                return Long.compare(left.getValue(), right.getValue());
            }
        });
        Map<K, Long> result = new HashMap<K, Long>();
        long remaining = Math.max(0, capacity);
        int left = sorted.size();
        for (Map.Entry<K, Long> entry : sorted) {
            long give = Math.min(entry.getValue(), remaining / left);
            result.put(entry.getKey(), give);
            remaining -= give;
            left--;
        }
        return result;
    }

    /** One allocation: broad and narrow demand (requester included) with their max-min allowances. */
    private final class Snapshot {
        private final Map<PrefixKey, Long> broadDemand;
        private final Map<PrefixKey, Long> broadAlloc;
        private final Map<PrefixKey, Map<PrefixKey, Long>> narrowDemand;
        private final Map<PrefixKey, Map<PrefixKey, Long>> narrowAlloc;

        Snapshot(Map<PrefixKey, Long> broadDemand, Map<PrefixKey, Long> broadAlloc,
                 Map<PrefixKey, Map<PrefixKey, Long>> narrowDemand, Map<PrefixKey, Map<PrefixKey, Long>> narrowAlloc) {
            this.broadDemand = broadDemand;
            this.broadAlloc = broadAlloc;
            this.narrowDemand = narrowDemand;
            this.narrowAlloc = narrowAlloc;
        }

        /** True when the requester's narrow prefix fits its allowance inside its broad prefix's allocation. */
        boolean admits(PrefixKey broad, PrefixKey narrow) {
            return narrowDemand.get(broad).get(narrow) <= narrowAlloc.get(broad).get(narrow);
        }

        /** Most over-share charge: broad excess first, then narrow excess, least progress, random, then age. */
        Charge victim(final Object requester) {
            List<Charge> candidates = new ArrayList<Charge>();
            for (Charge charge : charges.values()) {
                if (charge.owner.equals(requester) || charge.bytes == 0) continue;
                if (broadExcess(charge) > 0 || narrowExcess(charge) > 0) candidates.add(charge);
            }
            if (candidates.isEmpty()) return null;
            Collections.sort(candidates, new Comparator<Charge>() {
                @Override public int compare(Charge left, Charge right) {
                    int byBroad = Long.compare(broadExcess(right), broadExcess(left));
                    if (byBroad != 0) return byBroad;
                    int byNarrow = Long.compare(narrowExcess(right), narrowExcess(left));
                    if (byNarrow != 0) return byNarrow;
                    int byProgress = Long.compare(left.progress, right.progress);
                    if (byProgress != 0) return byProgress;
                    int byRandom = Long.compare(left.tieBreak, right.tieBreak);
                    return byRandom != 0 ? byRandom : Long.compare(left.sequence, right.sequence);
                }
            });
            return candidates.get(0);
        }

        private long broadExcess(Charge charge) {
            return broadDemand.get(charge.broad) - broadAlloc.get(charge.broad);
        }

        private long narrowExcess(Charge charge) {
            return narrowDemand.get(charge.broad).get(charge.narrow) - narrowAlloc.get(charge.broad).get(charge.narrow);
        }
    }

    private long prefixBytes(PrefixKey broad) {
        Long value = broadBytes.get(broad);
        return value == null ? 0 : value;
    }

    private void removeBytes(Charge charge, long bytes) {
        charge.bytes -= bytes;
        adjustBroad(charge.broad, -bytes);
        if (charge.bytes == 0) charges.remove(charge.owner);
    }

    private void adjustBroad(PrefixKey broad, long delta) {
        long next = prefixBytes(broad) + delta;
        if (next < 0) throw new IllegalStateException("prefix bytes went negative");
        if (next == 0) broadBytes.remove(broad); else broadBytes.put(broad, next);
    }

    private static long add(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }

    /** One owner's staged charge with its source prefixes and progress. */
    private static final class Charge {
        final Object owner;
        final PrefixKey broad;
        final PrefixKey narrow;
        final long sequence;
        final long tieBreak;
        long bytes;
        long progress;

        Charge(Object owner, PrefixKey broad, PrefixKey narrow, long sequence, long tieBreak) {
            this.owner = owner;
            this.broad = broad;
            this.narrow = narrow;
            this.sequence = sequence;
            this.tieBreak = tieBreak;
        }
    }
}
