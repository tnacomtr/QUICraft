// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;

/**
 * The server-side {@code /3} switch of docs/protocol.md 12.1, 12.3 and 13.1: the flag is off by
 * default, and the listener may only advertise {@code quicraft/3} when every prerequisite holds.
 * No Minecraft and no listener is started here.
 */
class MultistreamSwitchTest {

    @Test
    void theFlagOffKeepsTodaysBehaviour() {
        assertEquals(MultistreamSwitch.Reason.FLAG_OFF,
                MultistreamSwitch.decide(false, true, true, true));
        assertEquals(MultistreamSwitch.Reason.FLAG_OFF, MultistreamSwitch.probe(false),
                "the default configuration never even probes the native");
        assertFalse(MultistreamSwitch.Reason.FLAG_OFF.isEnabled());
    }

    @Test
    void aMissingExporterIsAnOrdinaryCompatibilityCondition() {
        assertEquals(MultistreamSwitch.Reason.NO_EXPORTER,
                MultistreamSwitch.decide(true, false, true, true),
                "12.3: without the TLS exporter nothing that needs binding may be offered");
        assertFalse(MultistreamSwitch.Reason.NO_EXPORTER.isEnabled());
        assertTrue(MultistreamSwitch.Reason.NO_EXPORTER.message().contains("quicraft/2"));
    }

    @Test
    void aMissingRoutingResourceAlsoStaysOnV2() {
        assertEquals(MultistreamSwitch.Reason.NO_ROUTING,
                MultistreamSwitch.decide(true, true, false, true),
                "13.1: /3 selection needs a matching routing digest");
        assertFalse(MultistreamSwitch.Reason.NO_ROUTING.isEnabled());
    }

    @Test
    void missingFlowControlWindowCapsStayOnV2() {
        assertEquals(MultistreamSwitch.Reason.NO_WINDOW_CAPS,
                MultistreamSwitch.decide(true, true, true, false),
                "13.1/13.13: never advertise a listener that cannot speak /3");
        assertFalse(MultistreamSwitch.Reason.NO_WINDOW_CAPS.isEnabled());
    }

    @Test
    void everythingPresentEnablesTheV3Listener() {
        assertEquals(MultistreamSwitch.Reason.ENABLED,
                MultistreamSwitch.decide(true, true, true, true));
    }

    @Test
    void theShippedRoutingResourceIsWhatTheAdvertisementOffers() {
        Map<Integer, String> routing = MultistreamSwitch.advertisedRouting();

        assertEquals(1, routing.size(), "one shipped placeholder today: " + routing);
        for (int protocol : MultistreamSwitch.SHIPPED_PROTOCOLS) {
            String digest = routing.get(Integer.valueOf(protocol));
            assertTrue(digest != null, "protocol " + protocol + " must be advertised");
            // 13.1: lowercase "sha256:" followed by 64 hex digits, the digest of the exact bytes.
            assertTrue(digest.matches("sha256:[0-9a-f]{64}"), digest);
            assertEquals("sha256:" + RoutingRegistry.forProtocol(protocol).get().digestHex(), digest,
                    "the advertisement must carry the resource digest, and only what is loaded");
        }
        assertEquals(Integer.valueOf(775), routing.keySet().iterator().next(),
                "the 26.x placeholder protocol number (docs/version-matrix.md)");
    }

    @Test
    void theListenerServesTheShippedTableAndFailsClosedWithoutOne() {
        assertSame(RoutingRegistry.forProtocol(775).get(), MultistreamSwitch.tableFor(775),
                "the listener serves the shipped table for the number it advertises");
        assertNull(MultistreamSwitch.tableFor(1234),
                "12.3/13.1: a protocol number without a table is null, which fails closed");
    }
}
