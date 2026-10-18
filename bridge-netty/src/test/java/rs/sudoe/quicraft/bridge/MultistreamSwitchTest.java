// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

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
        // 13.7: a schema-1 resource loads, but it cannot drive /3 and must not be advertised.
        assertEquals(1, RoutingRegistry.forProtocol(775).get().schema(),
                "this tree ships schema 1 until U9a-2b lands the reviewed schema-2 table");
        assertEquals(MultistreamSwitch.Reason.NO_ROUTING_SCHEMA, MultistreamSwitch.probe(true, true, true),
                "13.7: the advertisement names only a reviewed schema-2 resource");
        // The public probe short-circuits on a native without the exporter, so guard that assertion.
        assertEquals(QuicSupport.hasKeyingMaterialExport() ? MultistreamSwitch.Reason.NO_ROUTING_SCHEMA
                        : MultistreamSwitch.Reason.NO_EXPORTER,
                MultistreamSwitch.probe(true));
        assertTrue(MultistreamSwitch.Reason.NO_ROUTING_SCHEMA.message().contains("13.7"));
        assertTrue(MultistreamSwitch.advertisedRouting().isEmpty(),
                "a schema-1 digest must never reach the advertisement: "
                        + MultistreamSwitch.advertisedRouting());
        assertFalse(MultistreamSwitch.servesProtocol(775));
    }

    @Test
    void aSchemaTwoTableIsServedAdvertisedAndTheDialPathIsUnaffected() {
        RoutingTable schemaTwo = RoutingTableLoader.parse(tableV2Json());
        assertTrue(MultistreamSwitch.serves(schemaTwo), "schema 2 is the reviewed resource");
        assertFalse(MultistreamSwitch.serves(RoutingRegistry.forProtocol(775).get()));

        Map<Integer, String> routing = MultistreamSwitch.advertisedRouting(
                protocol -> protocol == 775 ? schemaTwo : null);
        assertEquals(1, routing.size(), routing.toString());
        String digest = String.valueOf(routing.get(775));
        assertTrue(digest.matches("sha256:[0-9a-f]{64}"), digest);
        assertEquals("sha256:" + schemaTwo.digestHex(), digest,
                "13.1: the digest of the exact bytes, whitespace included");
        assertTrue(MultistreamSwitch.advertisedRouting(protocol -> null).isEmpty());
    }

    @Test
    void theSkipGuardOnlyAcceptsSchemaTwo() {
        assertFalse(MultistreamSwitch.servesProtocol(775), "a shipped schema-1 table cannot drive /3");
        assertFalse(MultistreamSwitch.servesProtocol(1234), "a protocol without a resource cannot drive /3");
        assertTrue(MultistreamSwitch.serves(RoutingTableLoader.parse(tableV2Json())));
    }

    @Test
    void theListenerServesTheShippedTableAndFailsClosedWithoutOne() {
        assertSame(RoutingRegistry.forProtocol(775).get(), MultistreamSwitch.tableFor(775),
                "the listener serves the shipped table for the number it advertises");
        assertNull(MultistreamSwitch.tableFor(1234),
                "12.3/13.1: a protocol number without a table is null, which fails closed");
    }

    /** A valid schema-2 table (13.7): no routes, every state's limits declared. */
    private static String tableV2Json() {
        String limit = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
                + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
                + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";
        StringBuilder limits = new StringBuilder("{");
        String[] states = {"handshaking", "status", "login", "configuration", "play"};
        for (int i = 0; i < states.length; i++) {
            limits.append(i > 0 ? "," : "").append('"').append(states[i]).append("\":").append(limit);
        }
        limits.append('}');
        return "{\"schema\":2,\"protocol\":775,\"default\":\"global\",\"extractors\":{},"
                + "\"serverboundLimits\":" + limits + ",\"routes\":[]}";
    }
}
