// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Thread-safe shared node accounting. */
public final class NodePools implements ResourcePools {
    public static final long MIN_EXCESS = 2_097_212L + 8L * 1024 * 1024 + 16L * 1024;
    private final Map<Pool, Long> limits = new EnumMap<Pool, Long>(Pool.class);
    private final Map<Pool, Map<Object, Long>> charges = new EnumMap<Pool, Map<Object, Long>>(Pool.class);
    private final long excessLimit;
    private final boolean reserveDeflaterAtAdmission;

    /** Builds shared pools; configured per-holder excess is clamped to one legal E. */
    public NodePools(long nodeLimit, long inboundExcessLimit, long outboundLimit,
                     long preLoginPercent, boolean reserveDeflaterAtAdmission) {
        nonnegative(nodeLimit, "nodeLimit");
        nonnegative(outboundLimit, "outboundLimit");
        if (preLoginPercent < 0 || preLoginPercent > 100) throw new IllegalArgumentException("preLoginPercent");
        this.excessLimit = Math.max(MIN_EXCESS, nonnegative(inboundExcessLimit, "inboundExcessLimit"));
        this.reserveDeflaterAtAdmission = reserveDeflaterAtAdmission;
        limits.put(Pool.NODE, nodeLimit);
        limits.put(Pool.INBOUND_EXCESS, excessLimit);
        limits.put(Pool.OUTBOUND, outboundLimit);
        limits.put(Pool.PRE_LOGIN, nodeLimit / 100 * preLoginPercent + nodeLimit % 100 * preLoginPercent / 100);
        limits.put(Pool.INFLATER, nodeLimit);
        for (Pool pool : Pool.values()) charges.put(pool, new HashMap<Object, Long>());
    }

    /** Builds pools with default shares. */
    public NodePools(long nodeLimit) { this(nodeLimit, MIN_EXCESS, nodeLimit, 10, false); }

    /** Returns clamped per-holder inbound excess E. */
    public long excessLimit() { return excessLimit; }
    /** Returns the configured deflater admission reservation policy. */
    public boolean reserveDeflaterAtAdmission() { return reserveDeflaterAtAdmission; }
    /** Returns the capacity of a pool. */
    public long limit(Pool pool) { return limits.get(pool); }
    /** Returns total bytes reserved in a pool. */
    public synchronized long reserved(Pool pool) { return total(pool); }
    /** Returns bytes reserved for one owner in a pool. */
    public synchronized long reserved(Pool pool, Object owner) {
        Long value = charges.get(pool).get(owner); return value == null ? 0 : value;
    }

    /** Reserves bytes atomically, returning false if the pool is full. */
    @Override public synchronized boolean reserve(Pool pool, Object owner, long bytes) {
        check(pool, owner, bytes);
        Map<Object, Long> map = charges.get(pool);
        long old = value(map, owner);
        if (bytes > limits.get(pool) - total(pool) || bytes > Long.MAX_VALUE - old) return false;
        map.put(owner, old + bytes);
        return true;
    }

    /** Releases an existing charge, rejecting underflow. */
    @Override public synchronized void release(Pool pool, Object owner, long bytes) {
        check(pool, owner, bytes);
        Map<Object, Long> map = charges.get(pool);
        Long old = map.get(owner);
        if (old == null || bytes > old) throw new IllegalStateException("release exceeds reservation");
        if (bytes == old) map.remove(owner); else map.put(owner, old - bytes);
    }

    /** Moves a charge atomically between pools, leaving the source unchanged on failure. */
    @Override public synchronized boolean promote(Pool from, Pool to, Object owner, long bytes) {
        check(from, owner, bytes); check(to, owner, bytes);
        if (from == to) return true;
        Map<Object, Long> source = charges.get(from), target = charges.get(to);
        Long old = source.get(owner);
        if (old == null || bytes > old) throw new IllegalStateException("promotion exceeds reservation");
        long targetOld = value(target, owner);
        if (bytes > limits.get(to) - total(to) || bytes > Long.MAX_VALUE - targetOld) return false;
        if (bytes == old) source.remove(owner); else source.put(owner, old - bytes);
        target.put(owner, targetOld + bytes);
        return true;
    }

    private long total(Pool pool) {
        long result = 0;
        for (Long value : charges.get(pool).values()) result += value;
        return result;
    }
    private static long value(Map<Object, Long> map, Object owner) {
        Long value = map.get(owner); return value == null ? 0 : value;
    }
    private static void check(Pool pool, Object owner, long bytes) {
        if (pool == null || owner == null || bytes < 0) throw new IllegalArgumentException("invalid reservation");
    }
    private static long nonnegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative"); return value;
    }
}
