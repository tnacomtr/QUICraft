// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inbound excess accounting, cap eligibility and FIFO retry queue (protocol.md 13.12). */
public final class InboundExcessPool {
    private final NodePools node;
    private final long perIpLimit, perAccountLimit;
    private final Map<Object, Holder> holders = new LinkedHashMap<Object, Holder>();
    private final List<Request> waiting = new ArrayList<Request>();

    /** Creates an inbound excess pool with independent node, IP and account caps. */
    public InboundExcessPool(NodePools node, long perIpLimit, long perAccountLimit) {
        if (node == null || perIpLimit < node.excessLimit() || perAccountLimit < node.excessLimit())
            throw new IllegalArgumentException("caps must admit one E");
        this.node = node; this.perIpLimit = Math.min(perIpLimit, node.limit(ResourcePools.Pool.INBOUND_EXCESS));
        this.perAccountLimit = Math.min(perAccountLimit, node.limit(ResourcePools.Pool.INBOUND_EXCESS));
    }

    /** Attempts to reserve; ineligible refusals are retained in FIFO order for retry. */
    public synchronized boolean reserve(Object owner, Object ip, Object account, long bytes, double rank, boolean localStall) {
        if (owner == null || ip == null || bytes < 0) throw new IllegalArgumentException("request");
        Holder h = holders.get(owner);
        if (h == null) { h = new Holder(owner, ip, account); holders.put(owner, h); }
        if (fits(h, bytes) && waiting.isEmpty() && node.reserve(ResourcePools.Pool.INBOUND_EXCESS, owner, bytes)) {
            h.bytes += bytes; h.rank = rank; h.localStall = localStall; return true;
        }
        waiting.add(new Request(h, bytes));
        reclaim(h, bytes);
        retry();
        return false;
    }

    /** Releases bytes and retries waiting grants FIFO before later charges. */
    public synchronized void release(Object owner, long bytes) {
        Holder h = holders.get(owner);
        if (h == null || bytes < 0 || bytes > h.bytes) throw new IllegalStateException("release exceeds reservation");
        node.release(ResourcePools.Pool.INBOUND_EXCESS, owner, bytes); h.bytes -= bytes;
        if (h.bytes == 0) holders.remove(owner);
        retry();
    }

    /** Returns current FIFO wait count. */
    public synchronized int waitingCount() { return waiting.size(); }
    /** Returns bytes retained by an owner. */
    public synchronized long reserved(Object owner) { Holder h = holders.get(owner); return h == null ? 0 : h.bytes; }

    private boolean fits(Holder request, long bytes) {
        long ip = bytes, account = bytes;
        for (Holder h : holders.values()) {
            if (h.ip.equals(request.ip)) ip += h.bytes;
            if (request.account != null && request.account.equals(h.account)) account += h.bytes;
        }
        return ip <= perIpLimit && (request.account == null || account <= perAccountLimit);
    }
    private void reclaim(Holder request, long bytes) {
        List<Holder> candidates = new ArrayList<Holder>(holders.values());
        candidates.sort(Comparator.comparingDouble((Holder h) -> h.rank).reversed().thenComparingLong(h -> -h.bytes));
        for (Holder h : candidates) {
            if (h == request || h.bytes == 0 || h.localStall || !relieves(h, request)) continue;
            node.release(ResourcePools.Pool.INBOUND_EXCESS, h.owner, h.bytes);
            h.bytes = 0; holders.remove(h.owner);
            if (fits(request, bytes) && bytes <= node.limit(ResourcePools.Pool.INBOUND_EXCESS) - node.reserved(ResourcePools.Pool.INBOUND_EXCESS)) break;
        }
    }
    private boolean relieves(Holder victim, Holder request) {
        return victim.ip.equals(request.ip) || request.account != null && request.account.equals(victim.account);
    }
    private void retry() {
        for (int i = 0; i < waiting.size();) {
            Request r = waiting.get(i);
            if (!fits(r.holder, r.bytes) || !node.reserve(ResourcePools.Pool.INBOUND_EXCESS, r.holder.owner, r.bytes)) { i++; continue; }
            r.holder.bytes += r.bytes; waiting.remove(i);
        }
    }
    private static final class Holder {
        final Object owner, ip, account; long bytes; double rank; boolean localStall;
        Holder(Object owner, Object ip, Object account) { this.owner=owner; this.ip=ip; this.account=account; }
    }
    private static final class Request { final Holder holder; final long bytes; Request(Holder h,long b){holder=h;bytes=b;} }
}
