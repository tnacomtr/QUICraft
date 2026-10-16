// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.List;

/**
 * Capped outbound byte pool (protocol.md 13.12 "Shared accounting" and "Pressure ranking").
 *
 * <p>Queued, packet-held and transport-owned outbound bytes are charged here, never against the inbound
 * pool or its admission shares. Node, per-source-IP, per-account and per-connection caps all apply.
 * Refused requests wait FIFO; released capacity is retried in arrival order before new charges. Victims are
 * holders of outbound bytes only, chosen by the same ranking and eligibility as inbound excess, but without
 * executor-stall exclusion. Outbound caps are not clamped to E.</p>
 *
 * <p>Thread-safe: every call synchronises on the shared ledger.</p>
 */
public final class OutboundPool {
    private final CappedLedger ledger;

    /**
     * Creates the outbound pool.
     *
     * @param node shared pools; the OUTBOUND capacity is the node-wide cap
     * @param ipLimit per-source-IP cap (13.12 provisional default 32 MiB)
     * @param accountLimit per-account cap (13.12 provisional default 32 MiB)
     * @param perConnectionLimit per-connection unsent-byte cap (13.12: 16 MiB)
     * @param rank current pressure scores used to choose victims
     */
    public OutboundPool(NodePools node, long ipLimit, long accountLimit, long perConnectionLimit, HolderRank rank) {
        if (node == null || rank == null || ipLimit < 0 || accountLimit < 0 || perConnectionLimit < 0) {
            throw new IllegalArgumentException("limits");
        }
        long nodeCap = node.limit(ResourcePools.Pool.OUTBOUND);
        this.ledger = new CappedLedger(node, ResourcePools.Pool.OUTBOUND,
                Math.min(ipLimit, nodeCap), Math.min(accountLimit, nodeCap), perConnectionLimit, rank);
    }

    /** Reserves queued or transport-owned outbound bytes; true when charged now, else queued FIFO. */
    public synchronized boolean reserve(Object owner, Object ip, Object account, long bytes) {
        return ledger.reserve(owner, ip, account, bytes, false);
    }

    /** Releases bytes once QUIC has acknowledged or otherwise released them; then retries FIFO. */
    public synchronized void release(Object owner, long bytes) { ledger.release(owner, bytes); }

    /** Ends an owner after it was shed with 0x12: releases its bytes and drops its waits. */
    public synchronized void cancel(Object owner) { ledger.cancel(owner); }

    /** Returns and clears owners selected for local 0x12 closure. */
    public synchronized List<Object> drainShedOwners() { return ledger.drainShed(); }

    /** Charged outbound bytes for one owner. */
    public synchronized long reserved(Object owner) { return ledger.reserved(owner); }

    /** Number of queued refused requests. */
    public synchronized int waitingCount() { return ledger.waitingCount(); }
}
