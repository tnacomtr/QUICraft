// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe shared node accounting (protocol.md 12 pre-login block and 13.12 shared accounting).
 *
 * <p>Every pool is a byte budget with per-owner charges. All operations take one monitor, so a
 * {@link #promote} moves a charge atomically: a concurrent observer sees the bytes in exactly one
 * pool, never in both and never in neither.</p>
 *
 * <p>The per-connection inbound excess size E (13.12: {@code E = L + F + 16 KiB}) is held separately
 * from the node-wide inbound excess cap. The node cap is a plain budget; only caller-local, per-IP,
 * per-account and sole-holder caps are clamped up to E (see {@link InboundExcessPool}).</p>
 */
public final class NodePools implements ResourcePools {
    /**
     * Conservative upper bound of E (13.12): {@code 2097212 + 8 MiB + 16 KiB}. Used when the caller
     * does not supply the exact E of its selected routing tables.
     */
    public static final long MIN_EXCESS = 2_097_212L + 8L * 1024 * 1024 + 16L * 1024;

    private final Map<Pool, Long> limits = new EnumMap<Pool, Long>(Pool.class);
    private final Map<Pool, Long> totals = new EnumMap<Pool, Long>(Pool.class);
    private final Map<Pool, Map<Object, Long>> charges = new EnumMap<Pool, Map<Object, Long>>(Pool.class);
    /**
     * Pools that share the node budget (protocol.md 12 pre-login block and 13.12 shared accounting): their
     * combined totals never exceed {@code nodeLimit}. Inbound excess and outbound have their own caps.
     */
    private static final EnumSet<Pool> BUDGETED = EnumSet.of(Pool.NODE, Pool.INFLATER, Pool.PRE_LOGIN);
    private final long nodeLimit;
    private final long connectionExcess;
    private final boolean reserveDeflaterAtAdmission;

    /**
     * Builds the shared pools.
     *
     * @param nodeLimit node budget in bytes; the PRE_LOGIN pool is {@code preLoginPercent} of it
     * @param inboundExcessLimit node-wide inbound excess cap (13.12 provisional default 64 MiB)
     * @param outboundLimit node-wide outbound queue cap (13.12 provisional default 256 MiB)
     * @param preLoginPercent PRE_LOGIN pool size as a percentage of the node budget (default 10)
     * @param reserveDeflaterAtAdmission deflater memory policy, see {@link #deflaterPool()}
     * @param connectionExcess per-connection inbound excess E ({@code L + F + 16 KiB} for the selected
     *     routing tables, or {@link #MIN_EXCESS} as the conservative bound)
     */
    public NodePools(long nodeLimit, long inboundExcessLimit, long outboundLimit,
                     int preLoginPercent, boolean reserveDeflaterAtAdmission, long connectionExcess) {
        nonnegative(nodeLimit, "nodeLimit");
        nonnegative(inboundExcessLimit, "inboundExcessLimit");
        nonnegative(outboundLimit, "outboundLimit");
        if (preLoginPercent < 0 || preLoginPercent > 100) throw new IllegalArgumentException("preLoginPercent");
        if (connectionExcess < 0) throw new IllegalArgumentException("connectionExcess");
        this.nodeLimit = nodeLimit;
        this.connectionExcess = connectionExcess;
        this.reserveDeflaterAtAdmission = reserveDeflaterAtAdmission;
        limits.put(Pool.NODE, nodeLimit);
        limits.put(Pool.INFLATER, nodeLimit);
        limits.put(Pool.INBOUND_EXCESS, inboundExcessLimit);
        limits.put(Pool.OUTBOUND, outboundLimit);
        limits.put(Pool.PRE_LOGIN, nodeLimit / 100 * preLoginPercent + nodeLimit % 100 * preLoginPercent / 100);
        for (Pool pool : Pool.values()) {
            totals.put(pool, 0L);
            charges.put(pool, new HashMap<Object, Long>());
        }
    }

    /** Builds pools with the conservative E bound ({@link #MIN_EXCESS}) and a 10% PRE_LOGIN share. */
    public NodePools(long nodeLimit) {
        this(nodeLimit, 64L * 1024 * 1024, nodeLimit, 10, false, MIN_EXCESS);
    }

    /** Returns the per-connection inbound excess E this pool was built with (13.12). */
    public long connectionExcess() { return connectionExcess; }

    /**
     * Returns the pool that carries outbound Deflater memory (13.12 leaves the choice to the
     * benchmark): the node INFLATER pool when reserved at admission, else the OUTBOUND pool.
     */
    public Pool deflaterPool() { return reserveDeflaterAtAdmission ? Pool.INFLATER : Pool.OUTBOUND; }

    /** Returns the capacity of a pool in bytes. */
    public long limit(Pool pool) { return limits.get(pool); }

    /** Returns total bytes reserved in a pool. */
    public synchronized long reserved(Pool pool) { return totals.get(pool); }

    /** Returns bytes reserved for one owner in a pool. */
    public synchronized long reserved(Pool pool, Object owner) {
        Long value = charges.get(pool).get(owner);
        return value == null ? 0 : value;
    }

    /** Reserves bytes atomically, returning false (and changing nothing) if the pool cannot take them. */
    @Override public synchronized boolean reserve(Pool pool, Object owner, long bytes) {
        check(pool, owner, bytes);
        if (!fits(pool, bytes)) return false;
        Map<Object, Long> map = charges.get(pool);
        map.put(owner, value(map, owner) + bytes);
        totals.put(pool, totals.get(pool) + bytes);
        return true;
    }

    /** Releases an existing charge, rejecting underflow. */
    @Override public synchronized void release(Pool pool, Object owner, long bytes) {
        check(pool, owner, bytes);
        Map<Object, Long> map = charges.get(pool);
        Long old = map.get(owner);
        if (old == null || bytes > old) throw new IllegalStateException("release exceeds reservation");
        if (bytes == old) map.remove(owner); else map.put(owner, old - bytes);
        totals.put(pool, totals.get(pool) - bytes);
    }

    /**
     * Moves an existing charge between pools atomically (12 pre-login: promotion from PRE_LOGIN to
     * NODE). On refusal the source and target are both unchanged.
     *
     * @throws IllegalStateException when the owner holds less than {@code bytes} in {@code from}
     */
    @Override public synchronized boolean promote(Pool from, Pool to, Object owner, long bytes) {
        check(from, owner, bytes);
        check(to, owner, bytes);
        if (from == to) return true;
        Map<Object, Long> source = charges.get(from);
        Long old = source.get(owner);
        if (old == null || bytes > old) throw new IllegalStateException("promotion exceeds reservation");
        // A move inside the node budget keeps the budget total; only a move into it can overcommit.
        boolean entersBudget = BUDGETED.contains(to) && !BUDGETED.contains(from);
        if (entersBudget ? !fits(to, bytes) : bytes > limits.get(to) - totals.get(to)) return false;
        if (bytes == old) source.remove(owner); else source.put(owner, old - bytes);
        totals.put(from, totals.get(from) - bytes);
        Map<Object, Long> target = charges.get(to);
        target.put(owner, value(target, owner) + bytes);
        totals.put(to, totals.get(to) + bytes);
        return true;
    }

    /** True when the pool has room, and for budgeted pools the shared node budget has room too. */
    private boolean fits(Pool pool, long bytes) {
        if (bytes > limits.get(pool) - totals.get(pool)) return false;
        return !BUDGETED.contains(pool) || bytes <= nodeLimit - budgeted();
    }

    /** Combined bytes of the pools that share the node budget. */
    private long budgeted() {
        long total = 0;
        for (Pool pool : BUDGETED) total += totals.get(pool);
        return total;
    }

    private static long value(Map<Object, Long> map, Object owner) {
        Long value = map.get(owner);
        return value == null ? 0 : value;
    }

    private static void check(Pool pool, Object owner, long bytes) {
        if (pool == null || owner == null || bytes < 0) throw new IllegalArgumentException("invalid reservation");
    }

    private static long nonnegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }
}
