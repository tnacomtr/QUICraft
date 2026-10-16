// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;

class ReceiveCloseTest {
    @Test void peerViolationsAreZeroX11WithoutPhrase() {
        ReceiveClose close = ReceiveClose.peerViolation("cycle");
        assertEquals(0x11, close.errorCode());
        assertFalse(close.unseenPrerequisite());
        assertNull(close.reason());
    }

    @Test void blockedExpiryWithoutStallOrLocalWithholdingCarriesThePhrase() {
        ReceiveClose close = ReceiveClose.blockedExpiry(false, false);
        assertEquals(Protocol.CLOSE_RESOURCE, close.errorCode());
        assertEquals("unseen-prerequisite", new String(close.reason(), StandardCharsets.US_ASCII));
    }

    @Test void blockedExpiryAfterStallOrLocalWithholdingDropsThePhrase() {
        assertNull(ReceiveClose.blockedExpiry(true, false).reason());
        assertEquals(0x12, ReceiveClose.blockedExpiry(true, false).errorCode());
        assertNull(ReceiveClose.blockedExpiry(false, true).reason());
        assertEquals(0x12, ReceiveClose.blockedExpiry(false, true).errorCode());
    }

    @Test void localResourceWaitIsZeroX12WithoutPhrase() {
        ReceiveClose close = ReceiveClose.resourceWait("excess grant");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason());
    }
}
