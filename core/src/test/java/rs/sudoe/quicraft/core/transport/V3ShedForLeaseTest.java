// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.resource.ResourcePools;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * Listener path of docs/protocol.md 12.13 at full global capacity: a validated {@code /3} Initial is not
 * dropped while the open-connection limit is full. The listener sheds a staged pre-login connection,
 * whose close returns its open-connection lease at once, and admits the newcomer.
 */
class V3ShedForLeaseTest {
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @AfterEach
    void reset() {
        QuicClient.forgetSessions();
    }

    @Test
    void aValidatedNewcomerShedsAStagedConnectionWhenTheGlobalOpenLimitIsFull() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        List<V3Connection> accepted = Collections.synchronizedList(new ArrayList<V3Connection>());
        // One open connection in total, and a pre-login pool with room for exactly one staged connection.
        ResourcePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, PreLoginTracker.stagedShare(5));
        TransportConfig config = TransportConfig.builder().maxOpen(1).resourcePools(pools).build();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config,
                stream -> accepted.add((V3Connection) stream), protocol -> V3ConnectionTest.TABLE)) {
            V3Connection first = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.size() == 1, 5_000), "the first connection is accepted");

            // The global open limit is full. The second connection's validated Initial must shed the first
            // (idle and staged, so sheddable) instead of being dropped, so the second one is accepted.
            V3Connection second = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.size() == 2, 5_000),
                    "12.13: a validated newcomer over the open limit is admitted by shedding a staged victim");
            assertTrue(await(() -> !first.isOpen(), 5_000), "the shed victim is closed");
            second.close();
        }
    }

    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
