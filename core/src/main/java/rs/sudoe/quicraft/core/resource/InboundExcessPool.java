// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.List;
import java.util.logging.Logger;

/**
 * Inbound refusable-excess accounting (protocol.md 13.12 "Inbound excess").
 *
 * <p>Each connection may hold at most E bytes of excess; per-source-IP and per-authenticated-account
 * caps supplement the node-wide cap. Caller-local, per-IP and per-account caps below E are clamped up to
 * E at construction with a warning, never a startup failure (13.12 "Misconfiguration").</p>
 *
 * <p>Thread-safe: every call synchronises on the shared ledger.</p>
 */
public final class InboundExcessPool {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private final CappedLedger ledger;
    private final long perIpCap;
    private final long perAccountCap;

    /**
     * Creates the inbound excess pool over the shared node pools.
     *
     * @param node shared pools whose INBOUND_EXCESS capacity is the node-wide cap
     * @param perIpLimit configured per-source-IP excess cap, clamped up to E
     * @param perAccountLimit configured per-account excess cap, clamped up to E
     * @param rank current pressure scores used to choose victims
     */
    public InboundExcessPool(NodePools node, long perIpLimit, long perAccountLimit, HolderRank rank) {
        if (node == null || rank == null || perIpLimit < 0 || perAccountLimit < 0) throw new IllegalArgumentException("limits");
        long e = node.connectionExcess();
        this.perIpCap = clampToE(perIpLimit, node, e, "per-IP");
        this.perAccountCap = clampToE(perAccountLimit, node, e, "per-account");
        this.ledger = new CappedLedger(node, ResourcePools.Pool.INBOUND_EXCESS, perIpCap, perAccountCap, e, rank);
    }

    /** Effective per-source-IP cap after the startup clamp. */
    public long perIpCap() { return perIpCap; }

    /** Effective per-account cap after the startup clamp. */
    public long perAccountCap() { return perAccountCap; }

    /**
     * Grants excess bytes for an owner, or queues the request FIFO and reclaims eligible holders.
     *
     * @param localStall true while this owner's bytes wait only on our game executor (never a victim)
     * @return true when the bytes are charged now
     */
    public synchronized boolean reserve(Object owner, Object ip, Object account, long bytes, boolean localStall) {
        return ledger.reserve(owner, ip, account, bytes, localStall);
    }

    /** Releases excess once its ownership has actually ended, then retries waiters FIFO. */
    public synchronized void release(Object owner, long bytes) { ledger.release(owner, bytes); }

    /**
     * Confirms that a shed owner (see {@link #drainShedOwners}) has been closed with 0x12. Its excess stays
     * charged until this call, then is released and waiters are retried FIFO.
     */
    public synchronized void confirmClosed(Object owner) { ledger.confirmClosed(owner); }

    /** Ends an owner after it was closed with 0x12: releases all its excess and drops its waits. */
    public synchronized void cancel(Object owner) { ledger.cancel(owner); }

    /** Returns and clears owners selected for local 0x12 closure; the integration closes them. */
    public synchronized List<Object> drainShedOwners() { return ledger.drainShed(); }

    /** Retained excess for one owner. */
    public synchronized long reserved(Object owner) { return ledger.reserved(owner); }

    /** Retained excess charged to one source IP. */
    public synchronized long reservedByIp(Object ip) { return ledger.reservedByIp(ip); }

    /** Retained excess charged to one authenticated account. */
    public synchronized long reservedByAccount(Object account) { return ledger.reservedByAccount(account); }

    /** Number of queued refused requests. */
    public synchronized int waitingCount() { return ledger.waitingCount(); }

    /**
     * Peer attribution for an inbound refusal (13.12 "Peer attribution before resource classification").
     *
     * @param anyBytesOnExecutor some of this connection's bytes wait on our game executor: never peer-caused
     * @param excessExcludingLocal this connection's retained excess minus bytes held only by our executor
     * @param requested bytes of the refused request
     * @param connectionCap the per-connection cap (E, or a higher caller-local cap after the clamp)
     * @param capHeldOnlyBySelf the refused shared IP/account/node cap is held exclusively by this connection
     * @param bindingCap the shared cap that refused the request; below E it is a local fault
     * @return true when the refusal may close {@code 0x11}; false for a local fault or shared-pressure {@code 0x12}
     */
    public static boolean peerAttributable(boolean anyBytesOnExecutor, long excessExcludingLocal, long requested,
                                           long connectionCap, boolean capHeldOnlyBySelf, long bindingCap, long e) {
        if (anyBytesOnExecutor) return false;
        if (bindingCap < e) return false; // misconfiguration: a local fault, never peer-caused
        if (excessExcludingLocal + requested > connectionCap) return true;
        return capHeldOnlyBySelf && bindingCap >= e;
    }

    private static long clampToE(long configured, NodePools node, long e, String what) {
        long value = Math.min(configured, node.limit(ResourcePools.Pool.INBOUND_EXCESS));
        if (value < e) {
            LOG.warning("QUICraft: " + what + " inbound excess cap " + configured + " raised to E=" + e);
            return e;
        }
        return value;
    }
}
