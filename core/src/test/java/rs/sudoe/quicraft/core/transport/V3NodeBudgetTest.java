// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The node-wide pending-bytes budget of docs/protocol.md 13.12: every admitted {@code /3}
 * connection reserves its initial receive-credit exposure against it, a connection that does not
 * fit is refused, and a closed one gives its reservation back. A {@code /2} listener has no budget.
 */
class V3NodeBudgetTest {
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
    void aConnectionThatDoesNotFitTheNodeBudgetIsRefusedAndAClosedOnesRoomIsReused() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        AtomicInteger accepted = new AtomicInteger();
        // Room for exactly one connection's initial exposure.
        TransportConfig config = TransportConfig.builder()
                .nodePendingBytes(TransportConfig.V3_INITIAL_EXPOSURE_BYTES + 1024).build();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config,
                stream -> accepted.incrementAndGet(), protocol -> V3ConnectionTest.TABLE)) {
            V3Connection first = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.get() == 1, 5_000), "the first connection fits");

            // The second does not fit: the listener never hands it to the game. The dial itself may
            // finish its handshake before the refusal arrives, so what counts is the acceptor.
            try {
                V3Connection second = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
                assertTrue(await(() -> !second.isOpen(), 5_000), "a refused connection ends");
            } catch (java.util.concurrent.ExecutionException refusedAtHandshake) {
                // Also a refusal.
            }
            assertEquals(1, accepted.get(), "13.12: the budget refused the second /3 connection");

            first.close();
            assertTrue(await(() -> !first.isOpen(), 5_000), "the first connection closed");
            Thread.sleep(300);
            V3Connection third = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.get() == 2, 5_000), "a closed connection returned its reservation");
            third.close();
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
