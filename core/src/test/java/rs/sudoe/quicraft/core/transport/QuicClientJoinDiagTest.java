// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.JoinDiag;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** The client's join marks reach the adapter (V3ConnectionImpl) through the QUIC channel. */
class QuicClientJoinDiagTest {

    @BeforeAll
    static void setUp() throws Exception {
        assertNotNull(V3ConnectionTest.TABLE, "routing table available");
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs the patched native (12.2, 12.3, 13.13)");
    }

    @AfterEach
    void reset() {
        QuicClient.setJoinDiagSourceForTests(null);
        QuicClient.forgetSessions();
    }

    @Test
    void theAdapterSeesTheAttemptsDiag() throws Exception {
        AtomicReference<JoinDiag> attemptDiag = new AtomicReference<>();
        // Enable diagnostics temporarily for this test.
        String previousProperty = System.getProperty(JoinDiag.PROPERTY);
        System.setProperty(JoinDiag.PROPERTY, "true");
        try {
            QuicClient.setJoinDiagSourceForTests(() -> {
                // Force a diag instance regardless of the ENABLED switch.
                JoinDiag diag = createDiagForTest();
                attemptDiag.set(diag);
                return diag;
            });
            try {
                ServerIdentity identity = ServerIdentity.generate();
                try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity,
                        TransportConfig.DEFAULT, stream -> { },
                        protocol -> V3ConnectionTest.TABLE)) {
                    V3Connection connection = QuicClient.connectV3(
                            server.localAddress(), identity.fingerprint(), V3ConnectionTest.TABLE, null)
                            .get(10, TimeUnit.SECONDS);
                    assertNotNull(attemptDiag.get(), "the attempt created a diag instance");
                    assertSame(attemptDiag.get(), connection.joinDiag(),
                            "the adapter's diag must be the same instance the attempt created");
                    connection.close();
                }
            } finally {
                QuicClient.setJoinDiagSourceForTests(null);
            }
        } finally {
            if (previousProperty == null) {
                System.clearProperty(JoinDiag.PROPERTY);
            } else {
                System.setProperty(JoinDiag.PROPERTY, previousProperty);
            }
        }
    }

    private static JoinDiag createDiagForTest() {
        try {
            java.lang.reflect.Method create = JoinDiag.class.getDeclaredMethod("create",
                    JoinDiag.Side.class, boolean.class, java.util.function.LongSupplier.class,
                    java.util.function.Consumer.class);
            create.setAccessible(true);
            return (JoinDiag) create.invoke(null, new Object[] {
                    JoinDiag.Side.CLIENT, true, (java.util.function.LongSupplier) System::nanoTime,
                    (java.util.function.Consumer<String>) s -> System.out.println(s)
            });
        } catch (Exception e) {
            throw new AssertionError("cannot create test diag", e);
        }
    }
}
