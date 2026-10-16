// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sender-local pending store for ACK_JOIN records (docs/protocol.md 13.19). The adapter registers
 * a prediction acknowledgement with its covered authoritative updates. The ack is assigned only
 * after every covered update that is not suppressed has been assigned, so it never names a
 * frontier that does not exist yet. Pending acks live outside the sequenced queues and never block
 * the assignment of the updates they cover.
 *
 * <p>Limits: 128 entries and 64 KiB per connection, whichever is hit first (13.19). A pending ack
 * expires 10 seconds after creation on the monotonic transport clock. Expiry and every protocol
 * state, epoch or disconnect transition drop it, counted and without packet contents, unless
 * coverage is already complete. Callers must charge {@link #pendingBytes()} to the outbound pool.
 *
 * <p>Not thread-safe beyond its own monitor. The caller must pass the same monotonic clock to every
 * method.
 */
public final class AckJoinTracker {
    /** Pending ACK_JOIN entry limit per connection (13.19). */
    public static final int MAX_ENTRIES = 128;
    /** Pending ACK_JOIN byte limit per connection, charged from creation (13.19). */
    public static final int MAX_BYTES = 64 * 1024;
    /** Pending deadline measured from creation on the monotonic transport clock (13.19). */
    public static final long DEADLINE_NANOS = 10_000_000_000L;

    private final Map<Long, Pending> pending = new LinkedHashMap<Long, Pending>();
    private final Map<Long, Set<Long>> waiters = new HashMap<Long, Set<Long>>();
    private int bytes;
    private long droppedCount;

    /** One ack ready for sequencing, with its dependencies in the sender's logical order (13.19). */
    public static final class Ready {
        private final long id;
        private final Record.Dependency[] dependencies;

        private Ready(long id, Record.Dependency[] dependencies) {
            this.id = id;
            this.dependencies = dependencies;
        }

        /** The ack identifier supplied at creation. */
        public long id() {
            return id;
        }

        /** Sparse, nonzero, strictly backward dependencies for {@code assignJoin(2, ...)}. */
        public Record.Dependency[] dependencies() {
            return dependencies.clone();
        }
    }

    /**
     * Creates a pending ack and charges its bytes from creation (13.19). Returns false, without
     * storing anything, when the entry or byte limit would be exceeded; the caller must then pause
     * the ack producer or resource-abort 0x12, never drop silently.
     *
     * @throws IllegalArgumentException for a duplicate id or a negative size or clock
     */
    public synchronized boolean create(long id, int chargedBytes, long nowNanos) {
        if (chargedBytes < 0 || nowNanos < 0) {
            throw new IllegalArgumentException("ack");
        }
        if (pending.containsKey(id)) {
            throw new IllegalArgumentException("duplicate ack");
        }
        if (pending.size() >= MAX_ENTRIES || (long) bytes + chargedBytes > MAX_BYTES) {
            return false;
        }
        pending.put(id, new Pending(chargedBytes, nowNanos));
        bytes += chargedBytes;
        return true;
    }

    /**
     * Adds an update that was already assigned when it was covered. Its actual frontier joins the
     * ack's dependencies at once (13.19).
     */
    public synchronized void coverAssigned(long id, int stream, long sequence) {
        if (stream < 0 || sequence <= 0) {
            throw new IllegalArgumentException("coverage");
        }
        require(id).merge(stream, sequence);
    }

    /**
     * Adds a covered update that has not been assigned yet. The ack waits until
     * {@link #assignedPending} or {@link #suppressPending} resolves it (13.19).
     */
    public synchronized void coverPending(long id, long updateToken) {
        Pending value = require(id);
        if (value.unassigned.add(updateToken)) {
            Set<Long> ids = waiters.get(updateToken);
            if (ids == null) {
                ids = new HashSet<Long>();
                waiters.put(updateToken, ids);
            }
            ids.add(id);
        }
    }

    /**
     * Records the assignment of a covered update in every pending ack that waits on it (13.19).
     * Tokens that no pending ack waits on are ignored.
     */
    public synchronized void assignedPending(long updateToken, int stream, long sequence) {
        if (stream < 0 || sequence <= 0) {
            throw new IllegalArgumentException("coverage");
        }
        Set<Long> ids = waiters.remove(updateToken);
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            Pending value = pending.get(id);
            if (value != null) {
                value.unassigned.remove(updateToken);
                value.merge(stream, sequence);
            }
        }
    }

    /**
     * Removes a suppressed or never-sent update from coverage. It has no wire frontier and is
     * never awaited (13.19).
     */
    public synchronized void suppressPending(long updateToken) {
        Set<Long> ids = waiters.remove(updateToken);
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            Pending value = pending.get(id);
            if (value != null) {
                value.unassigned.remove(updateToken);
            }
        }
    }

    /**
     * Marks the ack as lacking exact causal coverage. Once every non-suppressed covered update is
     * assigned, it resolves to the caller's safe superset instead of its partial frontier (13.19).
     */
    public synchronized void markInexact(long id) {
        require(id).exact = false;
    }

    /**
     * Resolves a ready ack and releases its charge, so the caller can sequence it. Returns null
     * while coverage is unresolved. An ack whose coverage is complete resolves even after its
     * deadline; an unresolved ack past its deadline is dropped, counted, and returns null. The result
     * carries the ack's frontier plus the CONTROL frontier snapshot at the logical point, which 13.19
     * takes conservatively. An inexact ack uses {@code safeSuperset} (see
     * {@link StreamSequencer#safeSupersetDependencies()}).
     */
    public synchronized Ready take(long id, long nowNanos, long controlFrontier,
            Record.Dependency[] safeSuperset) {
        Pending value = pending.get(id);
        if (value == null) {
            return null;
        }
        if (!value.unassigned.isEmpty()) {
            if (expired(value, nowNanos)) {
                drop(id, value);
            }
            return null;
        }
        Record.Dependency[] result = resolve(value, controlFrontier, safeSuperset);
        forgetWaiters(id, value);
        pending.remove(id);
        bytes -= value.size;
        return new Ready(id, result);
    }

    /**
     * Settles every pending ack at a protocol-state, epoch or disconnect transition (13.19). Acks
     * whose coverage is complete are returned as ready so the caller can sequence them in the old
     * state and epoch. The others are dropped and counted. Every entry is removed; nothing carries
     * across the transition.
     */
    public synchronized List<Ready> settleForTransition(long controlFrontier, Record.Dependency[] safeSuperset) {
        List<Ready> ready = new ArrayList<Ready>();
        for (Map.Entry<Long, Pending> entry : new ArrayList<Map.Entry<Long, Pending>>(pending.entrySet())) {
            Pending value = entry.getValue();
            if (value.unassigned.isEmpty()) {
                ready.add(new Ready(entry.getKey(), resolve(value, controlFrontier, safeSuperset)));
            } else {
                droppedCount++;
            }
        }
        pending.clear();
        waiters.clear();
        bytes = 0;
        return ready;
    }

    /**
     * Settles the acks whose deadline has passed (13.19). Complete ones are returned as ready to be
     * sequenced in the current state; the still-unassigned ones are dropped and counted. Acks that
     * have not reached their deadline are untouched.
     */
    public synchronized List<Ready> expire(long nowNanos, long controlFrontier, Record.Dependency[] safeSuperset) {
        List<Ready> ready = new ArrayList<Ready>();
        for (Iterator<Map.Entry<Long, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Pending> entry = it.next();
            Pending value = entry.getValue();
            if (!expired(value, nowNanos)) {
                continue;
            }
            if (value.unassigned.isEmpty()) {
                ready.add(new Ready(entry.getKey(), resolve(value, controlFrontier, safeSuperset)));
            } else {
                droppedCount++;
            }
            forgetWaiters(entry.getKey(), value);
            bytes -= value.size;
            it.remove();
        }
        return ready;
    }

    /** Charged bytes of pending acks, to be charged to the outbound pool (13.19). */
    public synchronized int pendingBytes() {
        return bytes;
    }

    /** Number of pending acks (13.19). */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Number of acks dropped by deadline or transition, a counter without packet contents (13.19). */
    public synchronized long droppedCount() {
        return droppedCount;
    }

    private Record.Dependency[] resolve(Pending value, long controlFrontier, Record.Dependency[] safeSuperset) {
        TreeMap<Integer, Long> merged = new TreeMap<Integer, Long>();
        if (value.exact) {
            merged.putAll(value.frontier);
        } else {
            if (safeSuperset == null) {
                throw new IllegalArgumentException("safe superset");
            }
            for (Record.Dependency dependency : safeSuperset) {
                mergeInto(merged, dependency.streamIndex(), dependency.sequence());
            }
        }
        if (controlFrontier > 0) {
            mergeInto(merged, LogicalStream.CONTROL, controlFrontier);
        }
        Record.Dependency[] result = new Record.Dependency[merged.size()];
        int i = 0;
        for (Map.Entry<Integer, Long> entry : merged.entrySet()) {
            result[i++] = new Record.Dependency(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void mergeInto(Map<Integer, Long> map, int stream, long sequence) {
        Long old = map.get(stream);
        if (old == null || old < sequence) {
            map.put(stream, sequence);
        }
    }

    private void drop(long id, Pending value) {
        forgetWaiters(id, value);
        pending.remove(id);
        bytes -= value.size;
        droppedCount++;
    }

    private void forgetWaiters(long id, Pending value) {
        for (Long token : value.unassigned) {
            Set<Long> ids = waiters.get(token);
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    waiters.remove(token);
                }
            }
        }
    }

    private Pending require(long id) {
        Pending value = pending.get(id);
        if (value == null) {
            throw new IllegalArgumentException("unknown ack");
        }
        return value;
    }

    private static boolean expired(Pending value, long nowNanos) {
        return nowNanos - value.createdNanos >= DEADLINE_NANOS;
    }

    private static final class Pending {
        final int size;
        final long createdNanos;
        final TreeMap<Integer, Long> frontier = new TreeMap<Integer, Long>();
        final Set<Long> unassigned = new HashSet<Long>();
        boolean exact = true;

        Pending(int size, long createdNanos) {
            this.size = size;
            this.createdNanos = createdNanos;
        }

        void merge(int stream, long sequence) {
            mergeInto(frontier, stream, sequence);
        }
    }
}
