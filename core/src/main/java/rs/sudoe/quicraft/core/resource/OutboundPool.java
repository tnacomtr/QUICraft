// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Capped outbound byte pool with FIFO retry and binding-cap eligibility (protocol.md 13.12). */
public final class OutboundPool {
    private final NodePools node;
    private final long ipLimit, accountLimit;
    private final Map<Object, Holder> holders = new LinkedHashMap<Object, Holder>();
    private final java.util.List<Request> waiting = new java.util.ArrayList<Request>();

    /** Creates an outbound pool with independent node, source-IP and account limits. */
    public OutboundPool(NodePools node, long ipLimit, long accountLimit) {
        if (node == null || ipLimit < 0 || accountLimit < 0) throw new IllegalArgumentException("limits");
        this.node = node;
        this.ipLimit = Math.min(ipLimit, node.limit(ResourcePools.Pool.OUTBOUND));
        this.accountLimit = Math.min(accountLimit, node.limit(ResourcePools.Pool.OUTBOUND));
    }

    /** Reserves queued or transport-owned bytes; refused requests retain FIFO position. */
    public synchronized boolean reserve(Object owner, Object ip, Object account, long bytes) {
        if (owner == null || ip == null || bytes < 0) throw new IllegalArgumentException("request");
        Holder holder = holders.get(owner);
        if (holder == null) { holder = new Holder(owner, ip, account); holders.put(owner, holder); }
        if (waiting.isEmpty() && fits(holder, bytes) && node.reserve(ResourcePools.Pool.OUTBOUND, owner, bytes)) {
            holder.bytes += bytes;
            return true;
        }
        waiting.add(new Request(holder, bytes));
        return false;
    }

    /** Releases bytes only after the corresponding outbound ownership ends; then retries FIFO. */
    public synchronized void release(Object owner, long bytes) {
        Holder holder = holders.get(owner);
        if (holder == null || bytes < 0 || bytes > holder.bytes) throw new IllegalStateException("release exceeds reservation");
        node.release(ResourcePools.Pool.OUTBOUND, owner, bytes);
        holder.bytes -= bytes;
        if (holder.bytes == 0) holders.remove(owner);
        retry();
    }

    /** Returns currently charged bytes for an owner. */
    public synchronized long reserved(Object owner) { Holder h = holders.get(owner); return h == null ? 0 : h.bytes; }
    /** Returns the number of waiting requests. */
    public synchronized int waitingCount() { return waiting.size(); }

    private boolean fits(Holder request, long bytes) {
        long ip = bytes, account = bytes;
        for (Holder h : holders.values()) {
            if (h.ip.equals(request.ip)) ip = saturatedAdd(ip, h.bytes);
            if (request.account != null && request.account.equals(h.account)) account = saturatedAdd(account, h.bytes);
        }
        return ip <= ipLimit && (request.account == null || account <= accountLimit);
    }
    private void retry() {
        for (int i = 0; i < waiting.size();) {
            Request request = waiting.get(i);
            if (!fits(request.holder, request.bytes) || !node.reserve(ResourcePools.Pool.OUTBOUND, request.holder.owner, request.bytes)) { i++; continue; }
            request.holder.bytes += request.bytes;
            waiting.remove(i);
        }
    }
    private static long saturatedAdd(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
    private static final class Holder {
        final Object owner, ip, account; long bytes;
        Holder(Object owner, Object ip, Object account) { this.owner = owner; this.ip = ip; this.account = account; }
    }
    private static final class Request { final Holder holder; final long bytes; Request(Holder holder, long bytes) { this.holder = holder; this.bytes = bytes; } }
}
