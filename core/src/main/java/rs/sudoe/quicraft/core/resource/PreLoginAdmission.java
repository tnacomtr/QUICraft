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
 * bytes per source prefix: IPv6 /48 first, then /64 inside it, and IPv4 /24. A reservation is always admitted
 * while the pool has room (work-conserving). When the pool is full, a prefix within its max-min share (pool
 * capacity divided by the number of active broad prefixes) may shed charges of prefixes over their share. Victims
 * are chosen by the broad prefix's excess over its share first, then the narrow prefix's bytes, then least
 * progress, then a seeded random tie-break. A prefix over its share is refused rather than allowed to shed.</p>
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
            long share = share(broad);
            if (add(prefixBytes(broad), bytes) > share) return false;
            if (!shedUntilRoom(owner, bytes, share)) return false;
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

    /** Current max-min share for a broad prefix: capacity divided by active broad prefixes (including this one). */
    private long share(PrefixKey requester) {
        int active = broadBytes.containsKey(requester) ? broadBytes.size() : broadBytes.size() + 1;
        return pools.limit(ResourcePools.Pool.PRE_LOGIN) / Math.max(1, active);
    }

    private boolean shedUntilRoom(Object requester, long bytes, long share) {
        while (true) {
            Charge victim = victim(requester, share);
            if (victim == null) return false;
            pools.release(ResourcePools.Pool.PRE_LOGIN, victim.owner, victim.bytes);
            removeBytes(victim, victim.bytes);
            shed.add(victim.owner);
            if (pools.reserved(ResourcePools.Pool.PRE_LOGIN) + bytes <= pools.limit(ResourcePools.Pool.PRE_LOGIN)) return true;
        }
    }

    /** Selects a charge of an over-share broad prefix: broad excess, narrow bytes, least progress, random. */
    private Charge victim(Object requester, final long share) {
        List<Charge> candidates = new ArrayList<Charge>();
        for (Charge charge : charges.values()) {
            if (!charge.owner.equals(requester) && charge.bytes > 0 && prefixBytes(charge.broad) > share) candidates.add(charge);
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<Charge>() {
            @Override public int compare(Charge left, Charge right) {
                int byExcess = Long.compare(prefixBytes(right.broad) - share, prefixBytes(left.broad) - share);
                if (byExcess != 0) return byExcess;
                int byNarrow = Long.compare(narrowBytes(right.narrow), narrowBytes(left.narrow));
                if (byNarrow != 0) return byNarrow;
                int byProgress = Long.compare(left.progress, right.progress);
                if (byProgress != 0) return byProgress;
                int byRandom = Long.compare(left.tieBreak, right.tieBreak);
                return byRandom != 0 ? byRandom : Long.compare(left.sequence, right.sequence);
            }
        });
        return candidates.get(0);
    }

    private long prefixBytes(PrefixKey broad) {
        Long value = broadBytes.get(broad);
        return value == null ? 0 : value;
    }

    private long narrowBytes(PrefixKey narrow) {
        long total = 0;
        for (Charge charge : charges.values()) if (charge.narrow.equals(narrow)) total = add(total, charge.bytes);
        return total;
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
