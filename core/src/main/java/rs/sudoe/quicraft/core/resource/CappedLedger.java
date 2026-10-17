// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capped, FIFO-retried byte ledger shared by the inbound excess and outbound pools (protocol.md 13.12).
 *
 * <p>A request is granted only when it fits every applicable cap: the node pool, the per-source-IP cap,
 * the per-account cap (when authenticated) and the per-owner cap. Refused requests wait in FIFO order.
 * Under pressure the ledger picks victims: holders whose release relieves at least one binding deficit
 * of the waiting request, excluding the requester, holders blocked only by a local executor stall and holders
 * holding nothing beyond a protected reservation (the /3 REALTIME slice).
 * Victims are ordered by {@link HolderRank} descending, then retained bytes descending, then oldest
 * acquisition. Deficits and eligibility are recomputed after every reclamation, and reclamation stops as
 * soon as the waiting request fits.</p>
 *
 * <p>New arrivals and retries respect FIFO position: a request waits behind an earlier waiter only when that
 * waiter is blocked by a reason the later request also faces: node room, or the IP or account cap the two
 * share. A waiter blocked only by its own owner cap does not block anyone else. Waiting requests blocked by
 * unrelated caps are skipped without losing their position.</p>
 *
 * <p>A shed victim stays charged to its caps and to the node pool until {@link #confirmClosed} is called.
 * Its bytes are never granted to waiters before that confirmation, and no further victims are picked for a
 * waiter while a shed holder that relieves the same deficit is still unconfirmed.</p>
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
    /** Tracked tickets granted and not yet taken by their caller, mapped to their holder. */
    private final Map<Long, Holder> grants = new HashMap<Long, Holder>();
    private long sequence;
    private long tick;
    private long nextTicket;

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
        return !enqueue(owner, ip, account, bytes, localStall, false, false).pending;
    }

    /**
     * Grants or queues a request and returns its ticket. The ticket is granted through {@link #takeGrant}
     * once its bytes are charged, whether that happened now or later from the queue.
     */
    long reserveTicket(Object owner, Object ip, Object account, long bytes) {
        return enqueue(owner, ip, account, bytes, false, true, false).ticket;
    }

    /**
     * As {@link #reserveTicket}, for a protected reservation (the /3 REALTIME outbound slice, 13.12). Once
     * granted, these bytes never make their holder a shed victim: a holder holding only protected bytes is
     * skipped by victim selection. They are released only by {@link #cancel}.
     */
    long reserveProtectedTicket(Object owner, Object ip, Object account, long bytes) {
        return enqueue(owner, ip, account, bytes, false, true, true).ticket;
    }

    /** True once, for a tracked ticket that has been charged and not yet taken; removes the grant. */
    boolean takeGrant(long ticket) {
        return grants.remove(ticket) != null;
    }

    /** True when a request of {@code bytes} could ever be admitted under the owner, IP, account and node caps. */
    boolean admissible(Object account, long bytes) {
        return bytes <= ownerCap && bytes <= ipCap && (account == null || bytes <= accountCap)
                && bytes <= node.limit(pool);
    }

    /**
     * The smallest cap that can ever constrain a request of this account: owner, IP, account (when set) and node.
     * Callers that need headroom beside a fixed amount of bytes compare against this value.
     */
    long smallestCap(Object account) {
        long smallest = Math.min(ownerCap, Math.min(ipCap, node.limit(pool)));
        return account == null ? smallest : Math.min(smallest, accountCap);
    }

    /** Takes this owner's shed notice only; other owners' notices stay for their own callers. */
    boolean takeShed(Object owner) {
        return shed.remove(owner);
    }

    private Request enqueue(Object owner, Object ip, Object account, long bytes, boolean localStall, boolean tracked,
            boolean protect) {
        if (owner == null || ip == null || bytes < 0) throw new IllegalArgumentException("request");
        // A request no cap can ever admit is a local failure, never a wait: queueing it would block FIFO forever.
        if (!admissible(account, bytes)) {
            throw new IllegalArgumentException("request of " + bytes + " bytes exceeds a cap that can never admit it");
        }
        Holder holder = holder(owner, ip, account);
        Request request = new Request(holder, bytes, ++tick, tracked ? ++nextTicket : 0, tracked, protect);
        // An owner already selected for closure takes no new bytes; it is refused without queueing.
        if (holder.shed) return request;
        holder.localStall = localStall;
        if (!blockedForNew(holder, bytes) && fitsNow(holder, bytes)) {
            grant(request);
            return request;
        }
        waiting.add(request);
        reclaim();
        return request;
    }

    private void grant(Request request) {
        charge(request.holder, request.bytes);
        request.pending = false;
        if (request.protect) request.holder.protectedBytes = add(request.holder.protectedBytes, request.bytes);
        if (request.tracked) grants.put(request.ticket, request.holder);
    }

    /** Releases bytes whose ownership ended, then retries waiters FIFO before new charges (13.12). */
    void release(Object owner, long bytes) {
        Holder holder = holders.get(owner);
        if (holder == null || bytes < 0 || bytes > holder.bytes) throw new IllegalStateException("release exceeds reservation");
        node.release(pool, owner, bytes);
        holder.bytes -= bytes;
        if (holder.bytes == 0 && !hasWait(holder) && !holder.shed && !holdsGrant(holder)) holders.remove(owner);
        reclaim();
    }

    /**
     * Confirms that a shed victim was closed: releases its bytes, drops its waits and retries waiters. Bytes
     * of a shed holder stay charged until this call (13.12 reclaim ownership).
     */
    void confirmClosed(Object owner) {
        Holder holder = holders.get(owner);
        if (holder == null) return;
        if (!holder.shed) throw new IllegalStateException("owner was not shed");
        cancel(owner);
    }

    /** Ends an owner entirely: releases its bytes and drops its waits. */
    void cancel(Object owner) {
        Holder holder = holders.get(owner);
        if (holder == null) return;
        if (holder.bytes > 0) node.release(pool, owner, holder.bytes);
        holder.bytes = 0;
        removeWaits(holder);
        removeGrants(holder);
        shed.remove(owner);
        holders.remove(owner);
        reclaim();
    }

    /** The per-owner (per-connection) cap this ledger charges against. */
    long ownerCap() {
        return ownerCap;
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

    /** A new arrival waits behind any earlier waiter that blocks it (see {@link #blocksLater}). */
    private boolean blockedForNew(Holder holder, long bytes) {
        for (Request other : waiting) {
            if (blocksLater(other, holder)) return true;
        }
        return false;
    }

    /**
     * True when an earlier waiter must keep its FIFO place ahead of a later request from {@code later}. The
     * waiter blocks only for a reason the later request also faces: node room, or the IP or account cap it
     * would share. A waiter blocked only by its own owner cap blocks nobody.
     */
    private boolean blocksLater(Request other, Holder later) {
        if (!nodeRoom(other.bytes)) return true;
        if (other.holder.ip.equals(later.ip) && add(reservedByIp(later.ip), other.bytes) > ipCap) return true;
        return later.account != null && later.account.equals(other.holder.account)
                && add(reservedByAccount(later.account), other.bytes) > accountCap;
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
                if (awaitingShedRelief(request)) continue;
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
                waiting.remove(index);
                grant(request);
                changed = true;
                break;
            }
        }
    }

    private boolean blockedByEarlier(int index, Request request) {
        for (int earlier = 0; earlier < index; earlier++) {
            if (blocksLater(waiting.get(earlier), request.holder)) return true;
        }
        return false;
    }

    /** True while a shed holder that relieves this request's deficit is still charged, awaiting confirmation. */
    private boolean awaitingShedRelief(Request request) {
        for (Holder holder : holders.values()) {
            if (holder.shed && relieves(holder, request)) return true;
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
            if (candidate == request.holder || candidate.bytes <= candidate.protectedBytes || candidate.localStall
                    || candidate.shed) continue;
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

    /** Selects a victim for closure. Its bytes stay charged until {@link #confirmClosed}. */
    private void shedHolder(Holder victim) {
        victim.shed = true;
        removeWaits(victim);
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

    private boolean holdsGrant(Holder holder) {
        return grants.containsValue(holder);
    }

    private void removeGrants(Holder holder) {
        for (Iterator<Map.Entry<Long, Holder>> it = grants.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue() == holder) it.remove();
        }
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
        /** Granted protected bytes (the REALTIME slice); a holder with no more than these is never a victim. */
        long protectedBytes;
        long lastCharge;
        boolean localStall;
        boolean shed;

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
        final long ticket;
        final boolean tracked;
        final boolean protect;
        boolean pending = true;

        Request(Holder holder, long bytes, long enqueued, long ticket, boolean tracked, boolean protect) {
            this.holder = holder;
            this.bytes = bytes;
            this.enqueued = enqueued;
            this.ticket = ticket;
            this.tracked = tracked;
            this.protect = protect;
        }
    }
}
