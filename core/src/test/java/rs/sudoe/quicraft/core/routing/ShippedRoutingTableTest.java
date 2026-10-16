// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * The routing table this build ships for Minecraft 26.1.2 (protocol 775, docs/protocol.md 13.7):
 * it parses through the production loader, its digest is SHA-256 over the exact resource bytes,
 * and every entry is a clientbound PLAY route that the ordering tests in
 * {@code ShippedRoutingOrderingTest} cover.
 */
class ShippedRoutingTableTest {
    private static final int PROTOCOL = 775;
    private static final String RESOURCE = RoutingTableLoader.resourcePath(PROTOCOL);

    @Test
    void theShippedTableParsesAndIsTheRegistryAnswer() throws Exception {
        Faults.set();
        Optional<RoutingTable> table = RoutingRegistry.forProtocol(PROTOCOL);
        assertTrue(table.isPresent(), "26.1.2 ships a table: " + RESOURCE);
        assertEquals(PROTOCOL, table.get().protocolNumber());
        assertEquals(RoutingTableLoader.loadResource(PROTOCOL).digestHex(), table.get().digestHex());
    }

    @Test
    void theDigestIsSha256OverTheExactResourceBytes() throws Exception {
        Faults.set();
        byte[] bytes = readResource(RESOURCE);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] expected = sha256.digest(bytes);
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();
        assertArrayEquals(expected, table.digest(), "R is SHA-256 over the exact bytes (13.7)");
    }

    @Test
    void everyShippedRouteIsAClientboundPlayRouteWithALaneDependency() throws Exception {
        Faults.set();
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();
        assertFalse(table.routes().isEmpty(), "the 26.1.2 table routes packets off CONTROL");
        for (Map.Entry<RouteKey, Route> entry : table.routes().entrySet()) {
            RouteKey key = entry.getKey();
            Route route = entry.getValue();
            assertEquals(ProtocolState.PLAY, key.state(), key.toString());
            assertEquals(Direction.CLIENTBOUND, key.direction(), key.toString());
            assertFalse(route.lane().isControl(), key + " is listed only when it leaves CONTROL");
            assertEquals(Dependency.LANE, route.dependency(), key.toString());
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = ShippedRoutingTableTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
