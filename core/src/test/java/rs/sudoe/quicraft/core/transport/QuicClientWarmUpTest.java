// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** The warm-up only moves initialisation off the join: it never throws and changes nothing a dial does. */
class QuicClientWarmUpTest {

    @Test
    void warmUpNeverThrowsAndIsRepeatable() {
        assertDoesNotThrow(QuicClient::warmUp);
        assertDoesNotThrow(QuicClient::warmUp);
    }

    @Test
    void aDialAfterTheWarmUpStillConnects() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs the patched native (12.2, 12.3, 13.13)");
        QuicClient.warmUp();
        ServerIdentity identity = ServerIdentity.generate();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                stream -> { }, protocol -> V3ConnectionTest.TABLE)) {
            V3Connection connection = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertNotNull(connection);
            connection.close();
        } finally {
            QuicClient.forgetSessions();
        }
    }
}
