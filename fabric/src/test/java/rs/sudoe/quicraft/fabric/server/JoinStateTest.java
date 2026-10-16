// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;

/** The join's life (docs/protocol.md 12.14): ACTIVE only after the cutover, FAILED is terminal. */
class JoinStateTest {
    @Test
    void aFailureBeforeActivationIsABindingFailureEvenWhenTheCutoverWasUnderway() {
        JoinState state = new JoinState();
        assertTrue(state.startBinding());
        // The cutover is under way but not complete: the join is not ACTIVE yet.

        assertEquals(Protocol.CLOSE_BINDING_FAILURE, state.fail(), "0x10, so the client retries over TCP");
    }

    @Test
    void aFailureAfterActivationIsAnInternalError() {
        JoinState state = new JoinState();
        state.startBinding();
        assertTrue(state.activate());

        assertEquals(Protocol.CLOSE_INTERNAL_ERROR, state.fail());
    }

    @Test
    void aFailedJoinNeverMovesAgain() {
        JoinState state = new JoinState();
        state.startBinding();
        state.fail();

        assertFalse(state.activate(), "a late activation step cannot resurrect it");
        assertFalse(state.startBinding());
        assertEquals(-1, state.fail(), "only the first failure closes");
        assertTrue(state.failed());
        assertEquals(JoinState.Phase.FAILED, state.phase());
    }

    @Test
    void activationNeedsBindingFirst() {
        JoinState state = new JoinState();

        assertFalse(state.activate());
        assertTrue(state.startBinding());
        assertFalse(state.startBinding());
    }
}
