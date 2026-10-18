// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import rs.sudoe.quicraft.core.multistream.MultistreamSession.CausalScope;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.UpdateToken;

/**
 * Causal-scope STATE and API CONTRACT tests for {@link AckJoinTracker} (U1, docs/protocol.md
 * 13.19). Deterministic: the tracker is exercised in isolation with its opaque handles, mirroring
 * the driver-side delegation path.
 */
class CausalScopeTest {

    private static Map<Integer, Long> pairs(Record.Dependency[] deps) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (Record.Dependency d : deps) {
            result.put(d.streamIndex(), d.sequence());
        }
        return result;
    }

    private static Map<Integer, Long> expect(long... flat) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (int i = 0; i < flat.length; i += 2) {
            result.put((int) flat[i], flat[i + 1]);
        }
        return result;
    }

    // 1. Empty-but-open scope blocks resolution even with empty pending.
    @Test void emptyButOpenScopeBlocksTake() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        CausalScope scope = acks.openScope();
        acks.coverScope(1, scope);
        assertNull(acks.take(1, 1, 0, null), "sealed membership required");
        acks.sealAckCoverage(1);
        assertNull(acks.take(1, 2, 0, null), "scope must complete before resolution");
        acks.completeScope(scope);
        AckJoinTracker.Ready ready = acks.take(1, 3, 0, null);
        assertNotNull(ready, "sealed + complete scope resolves the ack");
        assertEquals(0, acks.pendingCount());
    }

    // 2. Late registration after completion must fail.
    @Test void lateRegisterAfterCompleteFails() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.suppressScopeToken(token);
        acks.completeScope(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "late registration after completion must throw");
    }

    // 3. Multiple scopes covered by one ack, both complete and sealed.
    @Test void multipleScopesMergedIntoAck() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 8, 5));
        CausalScope s1 = acks.openScope();
        CausalScope s2 = acks.openScope();
        UpdateToken t1 = acks.registerUpdate(s1);
        UpdateToken t2 = acks.registerUpdate(s2);
        acks.assignScopeToken(t1, 2, 10);
        acks.assignScopeToken(t2, 3, 20);
        acks.completeScope(s1);
        acks.completeScope(s2);
        acks.coverScope(1, s1);
        acks.coverScope(1, s2);
        acks.sealAckCoverage(1);
        AckJoinTracker.Ready ready = acks.take(1, 1, 5, null);
        assertNotNull(ready);
        assertEquals(expect(0, 5, 2, 10, 3, 20), pairs(ready.dependencies()),
                "ack frontier merges control snapshot and both scope frontiers");
        assertEquals(0, acks.scopeCount(), "both scopes retired after ack resolution");
    }

    // 4. Duplicate / foreign / stale handles.
    @Test void duplicateForeignStaleTokensRejected() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        CausalScope scope = acks.openScope();
        acks.coverScope(1, scope);
        // duplicate coverScope is a no-op, not an error
        acks.coverScope(1, scope);
        // foreign scope and token handles: another tracker's local ids start at the same values
        AckJoinTracker other = new AckJoinTracker();
        CausalScope foreignScope = other.openScope();
        UpdateToken foreignToken = other.registerUpdate(foreignScope);
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> acks.coverScope(1, foreignScope));
        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> acks.assignScopeToken(foreignToken, 2, 1));
        UpdateToken token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 5);
        // stale: token already resolved
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 6));
        // stale: token already suppressed
        UpdateToken t2 = acks.registerUpdate(scope);
        acks.suppressScopeToken(t2);
        assertThrows(IllegalArgumentException.class, () -> acks.suppressScopeToken(t2));
    }

    // 5. Suppression semantics: suppressed tokens never add a frontier.
    @Test void suppressionRemovesFromCoverage() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 5));
        CausalScope scope = acks.openScope();
        UpdateToken t1 = acks.registerUpdate(scope);
        UpdateToken t2 = acks.registerUpdate(scope);
        acks.assignScopeToken(t1, 2, 10);
        acks.suppressScopeToken(t2);
        acks.completeScope(scope);
        acks.coverScope(1, scope);
        acks.sealAckCoverage(1);
        AckJoinTracker.Ready ready = acks.take(1, 1, 5, null);
        assertNotNull(ready);
        assertEquals(expect(0, 5, 2, 10), pairs(ready.dependencies()),
                "suppressed token contributes no frontier");
    }

    // 6b. Bounded retention: scope retired after the last covering ack is taken.
    @Test void scopeRetiresAfterLastAck() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        assertTrue(acks.create(2, 4, 0));
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 7);
        acks.completeScope(scope);
        acks.coverScope(1, scope);
        acks.coverScope(2, scope);
        acks.sealAckCoverage(1);
        acks.sealAckCoverage(2);
        // Take ack 1: scope should NOT be retired (ack 2 still covers it)
        AckJoinTracker.Ready r1 = acks.take(1, 1, 0, null);
        assertNotNull(r1);
        assertEquals(1, acks.scopeCount(), "scope retained while ack 2 still covers it");
        // Take ack 2: scope should be retired
        AckJoinTracker.Ready r2 = acks.take(2, 1, 0, null);
        assertNotNull(r2);
        assertEquals(0, acks.scopeCount(), "scope retired after last ack taken");
    }

    // 7. Sealed-vs-unsealed ACK coverage.
    @Test void sealedVsUnsealedCoverage() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 5));
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 3);
        acks.completeScope(scope);
        acks.coverScope(1, scope);
        assertNull(acks.take(1, 1, 5, null), "unsealed coverage blocks resolution");
        assertNull(acks.take(1, 2, 5, null), "still unsealed");
        acks.sealAckCoverage(1);
        AckJoinTracker.Ready ready = acks.take(1, 3, 5, null);
        assertNotNull(ready, "sealed + complete resolves");
        assertEquals(expect(0, 5, 2, 3), pairs(ready.dependencies()));
    }

    // 8. Regression: OLD empty-pending-means-complete behaviour was wrong.
    @Test void regressionEmptyPendingDoesNotResolveScopedAck() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        // Under the OLD code (no scope gating), take() would have returned this ack
        // immediately because pending.unassigned was empty. The scope-gating check
        // makes take() return null when coverage is not sealed and complete.
        CausalScope scope = acks.openScope();
        acks.coverScope(1, scope);
        assertNull(acks.take(1, 0, 0, null),
                "old code would have resolved on empty pending; new code must not");
        // Sealed but scope not complete: still blocked
        acks.sealAckCoverage(1);
        assertNull(acks.take(1, 0, 0, null), "sealed but incomplete scope still blocks");
        // Complete: now resolved
        acks.completeScope(scope);
        assertNotNull(acks.take(1, 0, 0, null), "sealed + complete resolves");
    }

    // 9. Overflow rejection: MAX_SCOPES exceeded with a typed capacity failure (Defect 3).
    @Test void scopeCapRejects() {
        AckJoinTracker acks = new AckJoinTracker();
        for (int i = 0; i < AckJoinTracker.MAX_SCOPES; i++) {
            assertNotNull(acks.openScope());
        }
        assertThrows(AckJoinTracker.CapacityException.class, acks::openScope,
                "MAX_SCOPES exceeded must throw the typed capacity failure, not silently evict");
        assertEquals(AckJoinTracker.MAX_SCOPES, acks.scopeCount());
    }

    // 10. Foreign-session rejection: two trackers whose local ids both start at 1 (Defect 2).
    @Test void foreignTrackerHandlesRejected() {
        AckJoinTracker a = new AckJoinTracker();
        AckJoinTracker b = new AckJoinTracker();
        assertTrue(a.create(1, 4, 0));
        assertTrue(b.create(1, 4, 0));
        CausalScope scopeA = a.openScope();
        CausalScope scopeB = b.openScope();
        UpdateToken tokenA = a.registerUpdate(scopeA);
        UpdateToken tokenB = b.registerUpdate(scopeB);
        // The local id spaces collide by construction.
        assertEquals(1, scopeA.id());
        assertEquals(1, scopeB.id());
        assertEquals(1, tokenA.id());
        assertEquals(1, tokenB.id());
        // Owner identity keeps the handles distinct and rejects cross-tracker use.
        assertNotEquals(scopeA, scopeB, "handles from different trackers are never equal");
        assertNotEquals(tokenA, tokenB, "tokens from different trackers are never equal");
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> b.coverScope(1, scopeA));
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> b.assignScopeToken(tokenA, 2, 1));
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> b.suppressScopeToken(tokenA));
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> b.completeScope(scopeA));
        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> a.coverScope(1, scopeB));
        // The owning tracker still resolves its own handles normally.
        a.assignScopeToken(tokenA, 2, 5);
        a.completeScope(scopeA);
        a.coverScope(1, scopeA);
        a.sealAckCoverage(1);
        assertNotNull(a.take(1, 1, 0, null));
        assertEquals(0, a.scopeCount());
    }

    // 11. completeScope with an unresolved registered token must throw.
    @Test void completeWithUnresolvedThrows() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken t1 = acks.registerUpdate(scope); // unresolved
        assertThrows(IllegalArgumentException.class, () -> acks.completeScope(scope),
                "completion with unresolved tokens must throw");
        // Resolve the token, then completion succeeds
        acks.suppressScopeToken(t1);
        acks.completeScope(scope);
        // Double-complete throws
        assertThrows(IllegalArgumentException.class, () -> acks.completeScope(scope),
                "double complete must throw");
        // Registration after completion throws
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "registration after completion throws");
    }

    // 12. Scoped-but-unsealed ACK stays pending across repeated take calls.
    @Test void unsealedAckStaysPendingAcrossRepeatedTake() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 5);
        acks.completeScope(scope);
        acks.coverScope(1, scope);
        for (int i = 0; i < 5; i++) {
            assertNull(acks.take(1, i + 1, 0, null),
                    "unsealed ack must stay pending: iteration " + i);
        }
        assertEquals(1, acks.pendingCount(), "ack still pending");
        assertEquals(1, acks.scopeCount(), "scope still live");
        acks.sealAckCoverage(1);
        assertNotNull(acks.take(1, 6, 0, null), "sealed + complete resolves");
    }

    // 13. Expired scoped ACK is dropped, not resolved, when coverage is not ready.
    @Test void expiredScopedAckIsDropped() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 100));
        CausalScope scope = acks.openScope();
        acks.coverScope(1, scope);
        assertNull(acks.take(1, 99, 0, null));
        assertEquals(1, acks.pendingCount());
        long deadline = 100 + AckJoinTracker.DEADLINE_NANOS;
        assertEquals(0, acks.expire(deadline, 0, null).size(),
                "unsealed ack is dropped at expiry, not resolved");
        assertEquals(1, acks.droppedCount());
        assertEquals(0, acks.pendingCount());
    }

    // 14. Transition settlement drops incomplete-scope acks and clears scope state.
    @Test void transitionDropsUnsealedAcksAndClearsScopes() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        CausalScope scope = acks.openScope();
        acks.coverScope(1, scope);
        acks.sealAckCoverage(1);
        java.util.List<AckJoinTracker.Ready> settled = acks.settleForTransition(0, null);
        assertEquals(0, settled.size(), "incomplete-scope ack dropped at transition");
        assertEquals(1, acks.droppedCount());
        assertEquals(0, acks.scopeCount(), "scope state cleared at transition");
    }

    // 15. Session lifecycle (closeScopes) invalidates all handles; new allocation works.
    @Test void closeScopesInvalidatesHandles() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.closeScopes();
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "retired scope rejects registration");
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 1),
                "retired token rejects assignment");
        assertThrows(IllegalArgumentException.class, () -> acks.completeScope(scope),
                "retired scope rejects completion");
        // New scope after closeScopes: allocation works, IDs are monotonic (not reused)
        CausalScope fresh = acks.openScope();
        assertEquals(scope.id() + 1, fresh.id(), "IDs are monotonic, never reused");
    }

    // 16. Assigned token can never later be suppressed; completion after assignment works.
    @Test void assignedTokenCannotBeSuppressed() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 4);
        assertThrows(IllegalArgumentException.class, () -> acks.suppressScopeToken(token),
                "assigned token cannot be suppressed");
        acks.completeScope(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "registration after completion throws");
    }

    // 17. Sealing is final: no scope may join afterwards, while resealing is idempotent (Defect 1).
    @Test void sealIsFinalAndDoubleSealIdempotent() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        CausalScope first = acks.openScope();
        CausalScope late = acks.openScope();
        acks.coverScope(1, first);
        acks.sealAckCoverage(1);
        acks.sealAckCoverage(1);
        assertThrows(AckJoinTracker.SealedCoverageException.class, () -> acks.coverScope(1, late),
                "membership is final once sealed");
        // The sealed membership still resolves normally.
        acks.completeScope(first);
        assertNotNull(acks.take(1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> acks.sealAckCoverage(99),
                "sealing an unknown ack is still rejected");
    }

    // 18. Resolved and suppressed tokens release their retention immediately (Defect 3).
    @Test void resolvedTokensAreDroppedImmediately() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken assigned = acks.registerUpdate(scope);
        UpdateToken suppressed = acks.registerUpdate(scope);
        assertEquals(2, acks.retainedTokenCount());
        acks.assignScopeToken(assigned, 2, 5);
        assertEquals(1, acks.retainedTokenCount(), "an assigned token is not retained");
        acks.suppressScopeToken(suppressed);
        assertEquals(0, acks.retainedTokenCount(), "a suppressed token is not retained");
        // Neither token resolves a second time: its identity left the live token space.
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(assigned, 2, 6));
        assertThrows(IllegalArgumentException.class, () -> acks.suppressScopeToken(suppressed));
        acks.completeScope(scope);
    }

    // 19. Per-scope token cap with a typed failure; resolving frees capacity (Defect 3).
    @Test void tokenCapRejects() {
        AckJoinTracker acks = new AckJoinTracker();
        CausalScope scope = acks.openScope();
        UpdateToken first = null;
        for (int i = 0; i < AckJoinTracker.MAX_TOKENS_PER_SCOPE; i++) {
            UpdateToken token = acks.registerUpdate(scope);
            if (first == null) {
                first = token;
            }
        }
        assertEquals(AckJoinTracker.MAX_TOKENS_PER_SCOPE, acks.retainedTokenCount());
        assertThrows(AckJoinTracker.CapacityException.class, () -> acks.registerUpdate(scope),
                "the per-scope token cap is a typed capacity failure");
        // Releasing a token frees capacity; nothing is silently evicted.
        acks.assignScopeToken(first, 2, 1);
        assertNotNull(acks.registerUpdate(scope));
        assertEquals(AckJoinTracker.MAX_TOKENS_PER_SCOPE, acks.retainedTokenCount());
    }

    // 20. Frontier keys are bounded by the session's logical stream count (Defect 3).
    @Test void frontierKeysAreBounded() {
        AckJoinTracker acks = new AckJoinTracker(2);
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 1),
                "stream 2 is outside a two-stream session");
        acks.assignScopeToken(token, 1, 1);
        UpdateToken second = acks.registerUpdate(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(second, 8, 1),
                "an arbitrary stream index never grows a frontier map");
        assertTrue(acks.create(1, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> acks.coverAssigned(1, 2, 1),
                "ack frontiers are bounded by the same stream count");
    }

    // 21. Expiry of a scope-unready ack drops, counts and retires before returning (Defect 4).
    @Test void expiryOfScopeUnreadyAckDropsTrackerState() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 100));
        CausalScope scope = acks.openScope();
        UpdateToken token = acks.registerUpdate(scope);
        acks.coverScope(1, scope);
        acks.sealAckCoverage(1);
        long deadline = 100 + AckJoinTracker.DEADLINE_NANOS;
        assertNull(acks.take(1, deadline, 0, null),
                "a past-deadline ack with an incomplete scope never resolves");
        assertEquals(0, acks.pendingCount(), "the tracker dropped the orphan");
        assertEquals(1, acks.droppedCount(), "the drop is counted");
        assertEquals(0, acks.scopeCount(), "the scope is retired");
        assertEquals(0, acks.retainedTokenCount(), "the unresolved token is released");
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 1),
                "a token of a retired scope is no longer usable");
    }
}
