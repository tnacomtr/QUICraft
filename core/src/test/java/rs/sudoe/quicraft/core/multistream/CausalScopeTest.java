// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Causal-scope STATE and API CONTRACT tests for {@link AckJoinTracker} (U1, docs/protocol.md
 * 13.19). Deterministic: the tracker is exercised in isolation with opaque long ids,
 * mirroring the driver-side delegation path.
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
        long scope = acks.openScope();
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
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
        acks.suppressScopeToken(token);
        acks.completeScope(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "late registration after completion must throw");
    }

    // 3. Multiple scopes covered by one ack, both complete and sealed.
    @Test void multipleScopesMergedIntoAck() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 8, 5));
        long s1 = acks.openScope();
        long s2 = acks.openScope();
        long t1 = acks.registerUpdate(s1);
        long t2 = acks.registerUpdate(s2);
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

    // 4. Duplicate / foreign / stale tokens.
    @Test void duplicateForeignStaleTokensRejected() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 0));
        long scope = acks.openScope();
        acks.coverScope(1, scope);
        // duplicate coverScope is a no-op, not an error
        acks.coverScope(1, scope);
        // foreign scope id
        assertThrows(IllegalArgumentException.class, () -> acks.coverScope(1, 99_999L));
        // foreign token id for assignment
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(99_999L, 2, 1));
        long token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 5);
        // stale: token already resolved
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 6));
        // stale: token already suppressed
        long t2 = acks.registerUpdate(scope);
        acks.suppressScopeToken(t2);
        assertThrows(IllegalArgumentException.class, () -> acks.suppressScopeToken(t2));
    }

    // 5. Suppression semantics: suppressed tokens never add a frontier.
    @Test void suppressionRemovesFromCoverage() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, 5));
        long scope = acks.openScope();
        long t1 = acks.registerUpdate(scope);
        long t2 = acks.registerUpdate(scope);
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
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
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
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
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
        long scope = acks.openScope();
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

    // 9. Overflow rejection: MAX_SCOPES exceeded.
    @Test void scopeCapRejects() {
        AckJoinTracker acks = new AckJoinTracker();
        for (int i = 0; i < AckJoinTracker.MAX_SCOPES; i++) {
            acks.openScope();
        }
        assertThrows(IllegalStateException.class, () -> acks.openScope(),
                "MAX_SCOPES exceeded must throw, not silently evict");
        assertEquals(AckJoinTracker.MAX_SCOPES, acks.scopeCount());
    }

    // 10. Foreign-session rejection: tokens/scopes from another session are rejected.
    @Test void foreignTokenRejected() {
        AckJoinTracker acks = new AckJoinTracker();
        assertThrows(IllegalArgumentException.class,
                () -> acks.assignScopeToken(1L, 2, 1),
                "token 1L was never registered in this session");
        assertThrows(IllegalArgumentException.class,
                () -> acks.suppressScopeToken(1L));
        assertThrows(IllegalArgumentException.class,
                () -> acks.coverScope(1L, 1L),
                "foreign ack and foreign scope both rejected");
    }

    // 11. completeScope with an unresolved registered token must throw.
    @Test void completeWithUnresolvedThrows() {
        AckJoinTracker acks = new AckJoinTracker();
        long scope = acks.openScope();
        long t1 = acks.registerUpdate(scope); // unresolved
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
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
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
        long scope = acks.openScope();
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
        long scope = acks.openScope();
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
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
        acks.closeScopes();
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "retired scope rejects registration");
        assertThrows(IllegalArgumentException.class, () -> acks.assignScopeToken(token, 2, 1),
                "retired token rejects assignment");
        assertThrows(IllegalArgumentException.class, () -> acks.completeScope(scope),
                "retired scope rejects completion");
        // New scope after closeScopes: allocation works, IDs are monotonic (not reused)
        long fresh = acks.openScope();
        assertEquals(scope + 1, fresh, "IDs are monotonic, never reused");
    }

    // 16. Assigned token can never later be suppressed; completion after assignment works.
    @Test void assignedTokenCannotBeSuppressed() {
        AckJoinTracker acks = new AckJoinTracker();
        long scope = acks.openScope();
        long token = acks.registerUpdate(scope);
        acks.assignScopeToken(token, 2, 4);
        assertThrows(IllegalArgumentException.class, () -> acks.suppressScopeToken(token),
                "assigned token cannot be suppressed");
        acks.completeScope(scope);
        assertThrows(IllegalArgumentException.class, () -> acks.registerUpdate(scope),
                "registration after completion throws");
    }
}
