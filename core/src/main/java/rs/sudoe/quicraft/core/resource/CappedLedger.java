// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capped, FIFO-retried byte ledger shared by the inbound excess and outbound pools (protocol.md 13.12).
 *
 * <p>A request is granted only when it fits every applicable cap: the node pool, the per-source-IP cap,
 * the per-account cap (when authenticated) and the per-owner cap. Refused requests wait in FIFO order.
 * Under pressure the ledger picks victims: holders whose release relieves at least one binding deficit
 * of the waiting request, excluding the requester and holders blocked only by a local executor stall.
 * Victims are ordered by {@link HolderRank} descending, then retained bytes descending, then oldest
 * acquisition. Deficits and eligibility are recomputed after every reclamation, and reclamation stops as
 * soon as the waiting request fits.</p>
 *
 * <p>New arrivals and retries respect FIFO position: a request is never granted while an earlier waiting
 * request shares its IP or account cap, or while an earlier waiting request is refused for lack of node
 * room. Waiting requests blocked only by unrelated caps are skipped without losing their position.</p>
 *
 * <p>Not thread-safe by itself; the public wrappers synchronise every call.</p>
 */
final class CappedLedger {
    private final NodePools node;
    private final ResourcePools.Pool pool;
    private final long ipCap;
    private final long accountCap;
    private final long ownerCap;
    private final HolderRank rank;
    private final Map<Object, Holder> holders = new LinkedHashMap<Object, Holder>();
    private final List<Request> waiting = new ArrayList<Request>();
    private final List<Object> shed = new ArrayList<Object>();
    private long sequence;
    private long tick;

    CappedLedger(NodePools node, ResourcePools.Pool pool, long ipCap, long accountCap, long ownerCap, HolderRank rank) {
        if (node == null || pool == null || rank == null) throw new IllegalArgumentException("ledger");
        if (ipCap < 0 || accountCap < 0 || ownerCap < 0) throw new IllegalArgumentException("caps");
        this.node = node;
        this.pool = pool;
        this.ipCap = ipCap;
        this.accountCap = accountCap;
        this.ownerCap = ownerCap;
        this.rank = rank;
    }

    /** Grants or queues a request; returns true only when the bytes are charged now (13.12). */
    boolean reserve(Object owner, Object ip, Object account, long bytes, boolean localStall) {
        if (owner == null || ip == null || bytes < 0) throw new IllegalArgumentException("request");
        // A request no cap can ever admit is a local failure, never a wait: queueing it would block FIFO forever.
        if (bytes > ownerCap || bytes > ipCap || (account != null && bytes > accountCap) || bytes > node.limit(pool)) {
            throw new IllegalArgumentException("request of " + bytes + " bytes exceeds a cap that can never admit it");
        }
        Holder holder = holder(owner, ip, account);
        holder.localStall = localStall;
        if (!blockedForNew(holder, bytes) && fitsNow(holder, bytes)) {
            charge(holder, bytes);
            return true;
        }
        Request request = new Request(holder, bytes, ++tick);
        waiting.add(request);
        reclaim();
        return !request.pending;
    }

    /** Releases bytes whose ownership ended, then retries waiters FIFO before new charges (13.12). */
    void release(Object owner, long bytes) {
        Holder holder = holders.get(owner);
        if (holder == null || bytes < 0 || bytes > holder.bytes) throw new IllegalStateException("release exceeds reservation");
        node.release(pool, owner, bytes);
        holder.bytes -= bytes;
        if (holder.bytes == 0 && !hasWait(holder)) holders.remove(owner);
        reclaim();
    }

    /** Ends an owner entirely: releases its bytes and drops its waits (used when a victim is closed). */
    void cancel(Object owner) {
        Holder holder = holders.get(owner);
        if (holder == null) return;
        if (holder.bytes > 0) node.release(pool, owner, holder.bytes);
        holder.bytes = 0;
        removeWaits(holder);
        holders.remove(owner);
        reclaim();
    }

    long reserved(Object owner) {
        Holder holder = holders.get(owner);
        return holder == null ? 0 : holder.bytes;
    }

    long reservedByIp(Object ip) {
        long total = 0;
        for (Holder holder : holders.values()) if (holder.ip.equals(ip)) total = add(total, holder.bytes);
        return total;
    }

    long reservedByAccount(Object account) {
        long total = 0;
        for (Holder holder : holders.values()) if (account.equals(holder.account)) total = add(total, holder.bytes);
        return total;
    }

    int waitingCount() { return waiting.size(); }

    List<Object> drainShed() {
        List<Object> result = new ArrayList<Object>(shed);
        shed.clear();
        return result;
    }

    private void charge(Holder holder, long bytes) {
        if (!node.reserve(pool, holder.owner, bytes)) throw new IllegalStateException("node room checked before charge");
        holder.bytes = add(holder.bytes, bytes);
        holder.lastCharge = ++tick;
    }

    /** A new arrival waits behind any waiter sharing its IP or account, or behind a node-refused waiter. */
    private boolean blockedForNew(Holder holder, long bytes) {
        for (Request other : waiting) {
            if (other.holder.ip.equals(holder.ip)) return true;
            if (holder.account != null && holder.account.equals(other.holder.account)) return true;
            if (!nodeRoom(other.bytes)) return true;
        }
        return false;
    }

    /** Every cap and the node pool have room now, ignoring FIFO position. */
    private boolean fitsNow(Holder holder, long bytes) {
        return fitsCaps(holder, bytes) && nodeRoom(bytes);
    }

    private boolean nodeRoom(long bytes) {
        return bytes <= node.limit(pool) - node.reserved(pool);
    }

    private boolean fitsCaps(Holder holder, long bytes) {
        if (add(holder.bytes, bytes) > ownerCap) return false;
        if (add(reservedByIp(holder.ip), bytes) > ipCap) return false;
        return holder.account == null || add(reservedByAccount(holder.account), bytes) <= accountCap;
    }

    /** Recomputes which waiters can be granted; reclaims for each blocked waiter until it fits. */
    private void reclaim() {
        boolean progress = true;
        while (progress) {
            progress = false;
            retry();
            for (Request request : new ArrayList<Request>(waiting)) {
                if (!request.pending || fitsNow(request.holder, request.bytes)) continue;
                Holder victim = victim(request);
                if (victim != null) {
                    shedHolder(victim);
                    progress = true;
                    break;
                }
            }
        }
        retry();
    }

    /** Grants waiters in FIFO order, skipping any still blocked, without moving their positions. */
    private void retry() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int index = 0; index < waiting.size(); index++) {
                Request request = waiting.get(index);
                if (blockedByEarlier(index, request) || !fitsNow(request.holder, request.bytes)) continue;
                charge(request.holder, request.bytes);
                request.pending = false;
                waiting.remove(index);
                changed = true;
                break;
            }
        }
    }

    private boolean blockedByEarlier(int index, Request request) {
        for (int earlier = 0; earlier < index; earlier++) {
            Request other = waiting.get(earlier);
            if (other.holder.ip.equals(request.holder.ip)) return true;
            if (request.holder.account != null && request.holder.account.equals(other.holder.account)) return true;
            if (!nodeRoom(other.bytes)) return true;
        }
        return false;
    }

    private Holder victim(final Request request) {
        List<Holder> candidates = new ArrayList<Holder>(holders.values());
        Collections.sort(candidates, new Comparator<Holder>() {
            @Override public int compare(Holder left, Holder right) {
                int byRank = Double.compare(rank.score(right.owner), rank.score(left.owner));
                if (byRank != 0) return byRank;
                int byBytes = Long.compare(right.bytes, left.bytes);
                return byBytes != 0 ? byBytes : Long.compare(left.sequence, right.sequence);
            }
        });
        for (Holder candidate : candidates) {
            // A waiter reclaims only from holders charged before it queued: a grant made from the queue after
            // this arrival is protected from it, so retries never shed what FIFO just admitted.
            if (candidate == request.holder || candidate.bytes == 0 || candidate.localStall) continue;
            if (candidate.lastCharge > request.enqueued) continue;
            if (relieves(candidate, request)) return candidate;
        }
        return null;
    }

    /** True when releasing the victim lowers a deficit that currently binds this request. */
    private boolean relieves(Holder victim, Request request) {
        Holder requester = request.holder;
        long bytes = request.bytes;
        boolean nodeDeficit = node.reserved(pool) > node.limit(pool) - bytes;
        boolean ipDeficit = add(reservedByIp(requester.ip), bytes) > ipCap;
        boolean accountDeficit = requester.account != null && add(reservedByAccount(requester.account), bytes) > accountCap;
        return nodeDeficit
                || (ipDeficit && victim.ip.equals(requester.ip))
                || (accountDeficit && requester.account.equals(victim.account));
    }

    private void shedHolder(Holder victim) {
        if (victim.bytes > 0) node.release(pool, victim.owner, victim.bytes);
        victim.bytes = 0;
        removeWaits(victim);
        holders.remove(victim.owner);
        shed.add(victim.owner);
    }

    private void removeWaits(Holder holder) {
        for (int index = waiting.size() - 1; index >= 0; index--) {
            Request request = waiting.get(index);
            if (request.holder == holder) {
                request.pending = false;
                waiting.remove(index);
            }
        }
    }

    private Holder holder(Object owner, Object ip, Object account) {
        Holder result = holders.get(owner);
        if (result == null) {
            result = new Holder(owner, ip, account, sequence++);
            holders.put(owner, result);
        } else if (!result.ip.equals(ip) || (result.account == null ? account != null : !result.account.equals(account))) {
            throw new IllegalArgumentException("owner identity changed");
        }
        return result;
    }

    private boolean hasWait(Holder holder) {
        for (Request request : waiting) if (request.holder == holder) return true;
        return false;
    }

    private static long add(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    /** One owner's retained bytes plus its identity keys. */
    private static final class Holder {
        final Object owner;
        final Object ip;
        final Object account;
        final long sequence;
        long bytes;
        long lastCharge;
        boolean localStall;

        Holder(Object owner, Object ip, Object account, long sequence) {
            this.owner = owner;
            this.ip = ip;
            this.account = account;
            this.sequence = sequence;
        }
    }

    /** A refused request keeping its FIFO position until granted, shed or cancelled. */
    private static final class Request {
        final Holder holder;
        final long bytes;
        final long enqueued;
        boolean pending = true;

        Request(Holder holder, long bytes, long enqueued) {
            this.holder = holder;
            this.bytes = bytes;
            this.enqueued = enqueued;
        }
    }
}
