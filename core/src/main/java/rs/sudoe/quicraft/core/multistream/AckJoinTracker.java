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
    /** Max live (open or completed, not retired) causal scopes per connection (U1, 13.19). */
    public static final int MAX_SCOPES = 128;
    /** Max scopes covering one ack, bounding per-ack membership (U1, 13.19). */
    public static final int MAX_COVER_PER_ACK = 128;
    /** Max live (unresolved) update tokens per scope; resolved tokens are dropped at once (U1, 13.19). */
    public static final int MAX_TOKENS_PER_SCOPE = 128;
    /** Frontier keys of a tracker that does not know its session shape (U1, 13.19). */
    private static final int DEFAULT_MAX_FRONTIER_KEYS = LogicalStream.MAX_CHUNKS + 2;

    /**
     * A bounded causal-scope resource is exhausted (U1, 13.19). Typed so the driver can map it to a
     * local resource close (0x12) instead of letting a bare {@link IllegalStateException} reach a
     * production send path. It never names a peer.
     */
    public static final class CapacityException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Creates a capacity failure with a diagnostic reason, never a payload dump. */
        public CapacityException(String message) {
            super(message);
        }
    }

    /**
     * A covering scope was offered after the ack's membership was sealed (U1, 13.19). Typed so a
     * caller can reject the producer's contract violation without parsing messages.
     */
    public static final class SealedCoverageException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Creates a sealed-membership failure with a diagnostic reason. */
        public SealedCoverageException(String message) {
            super(message);
        }
    }

    /**
     * A scope or token handle belongs to another tracker/session (U1, 13.19). Per-tracker ids plus
     * the owner identity make handles unique across sessions; a foreign handle is never resolved
     * against the local id space.
     */
    public static final class ForeignHandleException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /** Creates a foreign-handle failure with a diagnostic reason. */
        public ForeignHandleException(String message) {
            super(message);
        }
    }

    private final int maxFrontierKeys;
    /** Owner identity carried by every handle this tracker issues; never shared statically. */
    private final Object identity = new Object();

    private final Map<Long, Pending> pending = new LinkedHashMap<Long, Pending>();
    private final Map<Long, Set<Long>> waiters = new HashMap<Long, Set<Long>>();
    private int bytes;
    private long droppedCount;

    /** Live causal scopes by monotonic id (U1, 13.19). */
    private final Map<Long, Scope> scopes = new HashMap<Long, Scope>();
    /** Token id to owning scope id (U1, 13.19). */
    private final Map<Long, Long> tokenScope = new HashMap<Long, Long>();
    /** Ack id to set of covering scope ids (U1, 13.19). */
    private final Map<Long, Set<Long>> ackScopes = new HashMap<Long, Set<Long>>();
    /** Scope id to set of ack ids that cover it, for retirement (U1, 13.19). */
    private final Map<Long, Set<Long>> scopeAcks = new HashMap<Long, Set<Long>>();
    /** Ack ids whose coverage membership is sealed (U1, 13.19). */
    private final Set<Long> sealedAcks = new HashSet<Long>();
    /** Monotonic scope id allocator, never reused (U1, 13.19). */
    private long nextScopeId = 1;
    /** Monotonic token id allocator, never reused (U1, 13.19). */
    private long nextTokenId = 1;

    /** A tracker for a session whose logical stream count is unknown: the /3 maximum, N+2 = 8. */
    public AckJoinTracker() {
        this(DEFAULT_MAX_FRONTIER_KEYS);
    }

    /**
     * A tracker whose frontier maps never hold keys outside the session's logical streams (U1,
     * 13.19). The driver passes {@link SessionContext#logicalStreamCount()}; invalid stream indices
     * are rejected rather than growing a frontier map without bound.
     *
     * @throws IllegalArgumentException when {@code maxFrontierKeys} is not positive
     */
    public AckJoinTracker(int maxFrontierKeys) {
        if (maxFrontierKeys <= 0) {
            throw new IllegalArgumentException("max frontier keys");
        }
        this.maxFrontierKeys = maxFrontierKeys;
    }

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
     * <p>The clock is any monotonic origin: {@link System#nanoTime()} may be negative, and only
     * differences are compared.
     *
     * @throws IllegalArgumentException for a duplicate id or a negative size
     */
    public synchronized boolean create(long id, int chargedBytes, long nowNanos) {
        if (chargedBytes < 0) {
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
        requireStream(stream);
        if (sequence <= 0) {
            throw new IllegalArgumentException("coverage");
        }
        require(id).merge(stream, sequence);
    }

    /**
     * Adds a covered update that has not been assigned yet. The ack waits until
     * {@link #assignedPending} or {@link #suppressPending} resolves it (13.19).
     *
     * <p>For one update, call this before {@link #assignedPending} or {@link #suppressPending}. A
     * cover recorded after its update was already resolved would wait until the deadline.
     *
     * @return true when the cover was newly recorded as unassigned; false when this ack already
     *         waited on the same update
     */
    public synchronized boolean coverPending(long id, long updateToken) {
        Pending value = require(id);
        if (!value.unassigned.add(updateToken)) {
            return false;
        }
        Set<Long> ids = waiters.get(updateToken);
        if (ids == null) {
            ids = new HashSet<Long>();
            waiters.put(updateToken, ids);
        }
        ids.add(id);
        return true;
    }

    /**
     * Records the assignment of a covered update in every pending ack that waits on it (13.19).
     * Tokens that no pending ack waits on are ignored. Call {@link #coverPending} for the same
     * update first.
     */
    public synchronized void assignedPending(long updateToken, int stream, long sequence) {
        requireStream(stream);
        if (sequence <= 0) {
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
     *
     * <p>Scope coverage gates resolution (U1, 13.19): if any scopes cover this ack, coverage must be
     * sealed and every covering scope complete. Empty scope membership alone never establishes readiness.
     */
    public synchronized Ready take(long id, long nowNanos, long controlFrontier,
            Record.Dependency[] safeSuperset) {
        Pending value = pending.get(id);
        if (value == null) {
            return null;
        }
        if (!isScopeReady(id)) {
            if (expired(value, nowNanos)) {
                // A past-deadline ack whose scope membership never became ready is dropped here,
                // counted and retired before returning, so the caller never holds an orphan.
                drop(id, value);
            }
            return null;
        }
        if (!value.unassigned.isEmpty()) {
            if (expired(value, nowNanos)) {
                drop(id, value);
            }
            return null;
        }
        mergeScopeFrontiers(id, value);
        Record.Dependency[] result = resolve(value, controlFrontier, safeSuperset);
        forgetWaiters(id, value);
        pending.remove(id);
        bytes -= value.size;
        retireScopes(id);
        return new Ready(id, result);
    }

    /**
     * Settles every pending ack at a protocol-state, epoch or disconnect transition (13.19). Acks
     * whose coverage is complete are returned as ready so the caller can sequence them in the old
     * state and epoch. The others are dropped and counted. Every entry is removed; nothing carries
     * across the transition.
     *
     * <p>Scope coverage is checked at the same gate (U1, 13.19): only acks whose covering scopes are
     * sealed and complete resolve; others are dropped. All scope state is cleared here.
     */
    public synchronized List<Ready> settleForTransition(long controlFrontier, Record.Dependency[] safeSuperset) {
        List<Ready> ready = new ArrayList<Ready>();
        for (Map.Entry<Long, Pending> entry : new ArrayList<Map.Entry<Long, Pending>>(pending.entrySet())) {
            long id = entry.getKey();
            Pending value = entry.getValue();
            if (value.unassigned.isEmpty() && isScopeReady(id)) {
                mergeScopeFrontiers(id, value);
                ready.add(new Ready(id, resolve(value, controlFrontier, safeSuperset)));
            } else {
                droppedCount++;
            }
        }
        pending.clear();
        waiters.clear();
        bytes = 0;
        closeScopes();
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
            long id = entry.getKey();
            Pending value = entry.getValue();
            if (!expired(value, nowNanos)) {
                continue;
            }
            if (value.unassigned.isEmpty() && isScopeReady(id)) {
                mergeScopeFrontiers(id, value);
                ready.add(new Ready(id, resolve(value, controlFrontier, safeSuperset)));
            } else {
                droppedCount++;
            }
            forgetWaiters(id, value);
            bytes -= value.size;
            it.remove();
            retireScopes(id);
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

    // --- Causal scope tracking (U1, 13.19) ---

    /** Number of live (open or completed, not retired) scopes. */
    public synchronized int scopeCount() {
        return scopes.size();
    }

    /**
     * Number of retained update tokens: registered and neither assigned nor suppressed yet
     * (U1, 13.19). Resolved tokens are dropped immediately, so this is the live retention count.
     */
    public synchronized int retainedTokenCount() {
        return tokenScope.size();
    }

    /**
     * Opens a new causal scope and returns its opaque handle (U1, 13.19). The id is monotonic per
     * tracker and never reused; the handle carries this tracker's owner identity. Throws
     * {@link CapacityException} when the cap is exceeded.
     */
    public synchronized MultistreamSession.CausalScope openScope() {
        if (scopes.size() >= MAX_SCOPES) {
            throw new CapacityException("scope cap");
        }
        long id = nextScopeId++;
        scopes.put(id, new Scope(id));
        return new MultistreamSession.CausalScope(identity, id);
    }

    /**
     * Registers one update in the live scope, returning its opaque token handle (U1, 13.19). The
     * token is resolved by {@link #assignScopeToken} or {@link #suppressScopeToken}. Throws for an
     * unknown, foreign or completed scope, and {@link CapacityException} when the per-scope token
     * cap is reached.
     */
    public synchronized MultistreamSession.UpdateToken registerUpdate(MultistreamSession.CausalScope scope) {
        long scopeId = scopeIdOf(scope);
        Scope value = scopes.get(scopeId);
        if (value == null) {
            throw new IllegalArgumentException("unknown scope");
        }
        if (value.complete) {
            throw new IllegalArgumentException("scope complete");
        }
        if (value.unresolved.size() >= MAX_TOKENS_PER_SCOPE) {
            throw new CapacityException("scope token cap");
        }
        long token = nextTokenId++;
        value.unresolved.add(token);
        tokenScope.put(token, scopeId);
        return new MultistreamSession.UpdateToken(identity, token);
    }

    /**
     * Resolves a scope token by assignment to {@code stream/sequence} (U1, 13.19). The token's
     * frontier is merged into its scope. Throws for an unknown, retired or already-resolved token,
     * for a completed scope, or for a stream outside the session's logical streams: assigned
     * updates can never later be suppressed.
     */
    public synchronized void assignScopeToken(MultistreamSession.UpdateToken tokenId, int stream, long sequence) {
        requireStream(stream);
        if (sequence <= 0) {
            throw new IllegalArgumentException("coverage");
        }
        long token = tokenIdOf(tokenId);
        Long scopeId = tokenScope.get(token);
        if (scopeId == null) {
            throw new IllegalArgumentException("unknown token");
        }
        Scope scope = scopes.get(scopeId);
        if (scope == null) {
            throw new IllegalArgumentException("scope retired");
        }
        if (scope.complete) {
            throw new IllegalArgumentException("scope complete");
        }
        if (!scope.unresolved.remove(token)) {
            throw new IllegalArgumentException("token already resolved");
        }
        // The token's identity is consumed: it is not retained by the tracker any more.
        tokenScope.remove(token);
        scope.merge(stream, sequence);
    }

    /**
     * Removes a suppression-only token from its scope: it has no wire frontier and is never
     * awaited (U1, 13.19). Throws for an unknown, retired or already-resolved token, or for a
     * completed scope. Suppression is valid only for genuinely unassigned tokens.
     */
    public synchronized void suppressScopeToken(MultistreamSession.UpdateToken tokenId) {
        long token = tokenIdOf(tokenId);
        Long scopeId = tokenScope.get(token);
        if (scopeId == null) {
            throw new IllegalArgumentException("unknown token");
        }
        Scope scope = scopes.get(scopeId);
        if (scope == null) {
            throw new IllegalArgumentException("scope retired");
        }
        if (scope.complete) {
            throw new IllegalArgumentException("scope complete");
        }
        if (!scope.unresolved.remove(token)) {
            throw new IllegalArgumentException("token already resolved");
        }
        // The token's identity is consumed: it is not retained by the tracker any more.
        tokenScope.remove(token);
    }

    /**
     * Marks a live scope as complete, the explicit producer-completeness event (U1, 13.19).
     * Throws if any registered token is still unresolved; afterwards neither registration nor
     * assignment is legal for that scope.
     */
    public synchronized void completeScope(MultistreamSession.CausalScope scopeId) {
        Scope scope = scopes.get(scopeIdOf(scopeId));
        if (scope == null) {
            throw new IllegalArgumentException("unknown scope");
        }
        if (scope.complete) {
            throw new IllegalArgumentException("scope complete");
        }
        if (!scope.unresolved.isEmpty()) {
            throw new IllegalArgumentException("unresolved tokens");
        }
        scope.complete = true;
    }

    /**
     * Registers a live or completed scope as covering a pending ack (U1, 13.19). The scope retains
     * its assigned frontier. A duplicate cover is a no-op. Throws for an unknown ack or scope, for
     * a foreign scope handle, and once the ack's membership is sealed.
     */
    public synchronized void coverScope(long ackId, MultistreamSession.CausalScope scopeId) {
        Pending ack = pending.get(ackId);
        if (ack == null) {
            throw new IllegalArgumentException("unknown ack");
        }
        if (sealedAcks.contains(ackId)) {
            throw new SealedCoverageException("ack coverage sealed");
        }
        long id = scopeIdOf(scopeId);
        Scope scope = scopes.get(id);
        if (scope == null) {
            throw new IllegalArgumentException("unknown scope");
        }
        Set<Long> covered = ackScopes.get(ackId);
        if (covered == null) {
            covered = new HashSet<Long>();
            ackScopes.put(ackId, covered);
        }
        if (covered.contains(id)) {
            // 13.19: a duplicate cover is a no-op, and it must stay one even at the cap, or re-covering
            // an existing member would be mapped to a resource close by the driver.
            return;
        }
        if (covered.size() >= MAX_COVER_PER_ACK) {
            throw new CapacityException("ack scope cap");
        }
        covered.add(id);
        scopeAcks.computeIfAbsent(id, k -> new HashSet<Long>()).add(ackId);
    }

    /**
     * Seals an ack's coverage membership: the producer declares no additional covering scopes will
     * arise (U1, 13.19). Idempotent. Throws for an unknown ack.
     */
    public synchronized void sealAckCoverage(long ackId) {
        Pending ack = pending.get(ackId);
        if (ack == null) {
            throw new IllegalArgumentException("unknown ack");
        }
        sealedAcks.add(ackId);
    }

    /**
     * Drops all causal-scope state (U1, 13.19). Called by the driver on session close. IDs are
     * not reset: monotonicity is per-session, not per-state.
     */
    public synchronized void closeScopes() {
        scopes.clear();
        tokenScope.clear();
        ackScopes.clear();
        scopeAcks.clear();
        sealedAcks.clear();
    }

    /**
     * True when an ack's scope coverage, if any, is ready: sealed and every covering scope
     * complete (U1, 13.19). No-scope acks are always ready at this check.
     */
    private boolean isScopeReady(long ackId) {
        Set<Long> scopeIds = ackScopes.get(ackId);
        if (scopeIds == null || scopeIds.isEmpty()) {
            return true;
        }
        if (!sealedAcks.contains(ackId)) {
            return false;
        }
        for (Long scopeId : scopeIds) {
            Scope scope = scopes.get(scopeId);
            if (scope == null || !scope.complete) {
                return false;
            }
        }
        return true;
    }

    /**
     * Merges every covering scope's assigned frontier into the ack's pending frontier (U1, 13.19).
     * Idempotent: scopes are immutable once complete, so repeated merges take the max.
     */
    private void mergeScopeFrontiers(long ackId, Pending value) {
        Set<Long> scopeIds = ackScopes.get(ackId);
        if (scopeIds == null) {
            return;
        }
        for (Long scopeId : scopeIds) {
            Scope scope = scopes.get(scopeId);
            if (scope != null) {
                for (Map.Entry<Integer, Long> entry : scope.frontier.entrySet()) {
                    value.merge(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * Retires an ack's covering scopes after the ack is resolved or dropped (U1, 13.19). A scope
     * whose last covering ack has cleared is removed entirely, and its tokens become foreign (rejected).
     */
    private void retireScopes(long ackId) {
        Set<Long> scopeIds = ackScopes.remove(ackId);
        sealedAcks.remove(ackId);
        if (scopeIds == null) {
            return;
        }
        for (Long scopeId : scopeIds) {
            Set<Long> ackSet = scopeAcks.get(scopeId);
            if (ackSet != null) {
                ackSet.remove(ackId);
                if (ackSet.isEmpty()) {
                    scopeAcks.remove(scopeId);
                    Scope scope = scopes.remove(scopeId);
                    if (scope != null) {
                        for (Long token : scope.unresolved) {
                            tokenScope.remove(token);
                        }
                        scope.unresolved.clear();
                    }
                }
            }
        }
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
                requireStream(dependency.streamIndex());
                if (dependency.sequence() <= 0) {
                    throw new IllegalArgumentException("coverage");
                }
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
        retireScopes(id);
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

    /** Rejects a stream index outside this session's logical streams, bounding every frontier map. */
    private void requireStream(int stream) {
        if (stream < 0 || stream >= maxFrontierKeys) {
            throw new IllegalArgumentException("stream index");
        }
    }

    /** Resolves a scope handle only when this tracker issued it; foreign handles never alias a local id. */
    private long scopeIdOf(MultistreamSession.CausalScope scope) {
        if (scope == null || scope.owner() != identity) {
            throw new ForeignHandleException("foreign scope handle");
        }
        return scope.id();
    }

    /** Resolves a token handle only when this tracker issued it; foreign handles never alias a local id. */
    private long tokenIdOf(MultistreamSession.UpdateToken token) {
        if (token == null || token.owner() != identity) {
            throw new ForeignHandleException("foreign token handle");
        }
        return token.id();
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

    /**
     * One causal scope: a monotonic id, its registered tokens, those still unresolved, and the
     * frontier merged from assigned tokens (U1, 13.19). Suppressed tokens contribute no frontier.
     */
    private static final class Scope {
        final long id;
        boolean complete;
        final Set<Long> unresolved = new HashSet<Long>();
        final TreeMap<Integer, Long> frontier = new TreeMap<Integer, Long>();

        Scope(long id) {
            this.id = id;
        }

        void merge(int stream, long sequence) {
            mergeInto(frontier, stream, sequence);
        }
    }
}
