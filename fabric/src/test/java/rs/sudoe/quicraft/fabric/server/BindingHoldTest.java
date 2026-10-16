// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The configuration stage's hold on Finish Configuration (docs/protocol.md 12.4): closed until
 * binding activates, and closed for good once it fails. Nothing here starts a game.
 */
class BindingHoldTest {
    @Test
    void theHoldIsClosedUntilActivation() {
        BindingHold hold = new BindingHold();

        assertFalse(hold.open(), "Finish Configuration waits for the exchange");
        hold.release();
        assertTrue(hold.open(), "activation lets the stage go on");
    }

    @Test
    void aFailedExchangeNeverOpensTheHold() {
        BindingHold hold = new BindingHold();

        hold.fail();
        assertFalse(hold.open());
        hold.release();
        assertFalse(hold.open(), "a connection being closed runs no more of the configuration");
        assertTrue(hold.failed());
    }
}
