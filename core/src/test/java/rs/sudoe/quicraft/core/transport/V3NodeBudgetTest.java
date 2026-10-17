// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The node-wide pending-bytes budget of docs/protocol.md 13.12: every admitted {@code /3}
 * connection reserves its initial receive-credit exposure against it, a connection that does not
 * fit is refused, and a closed one gives its reservation back. A {@code /2} listener has no budget.
 */
class V3NodeBudgetTest {
    @Test
    void activatedIdleConnectionsKeepTheProtectedShareAndRefuseTheNext() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport());
        rs.sudoe.quicraft.core.routing.RoutingTable table =
                V3ConnectionTest.TABLE;
        int n = 3;
        // N+2 protected reads stay inside the existing exposure share, not legacy lane buffers.
        long idle = TransportConfig.V3_INITIAL_EXPOSURE_BYTES;
        ReceiveBudget budget = ReceiveBudget.bounded(n * idle);
        List<V3Connection> peers = Collections.synchronizedList(new ArrayList<V3Connection>());
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity,
                TransportConfig.builder().nodeBudget(budget).build(), stream -> peers.add((V3Connection) stream),
                protocol -> table)) {
            for (int i = 0; i < n; i++) {
                V3Connection client = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        table, null).get(10, TimeUnit.SECONDS);
                final int count = i + 1;
                assertTrue(await(() -> peers.size() == count, 5000));
                assertTrue(await(peers.get(i)::handshakeComplete, 5000));
                V3ConnectionTest.activate(peers.get(i), new V3ConnectionTest.Game(), 4242);
                V3ConnectionTest.activate(client, new V3ConnectionTest.Game(), -1);
                long expected = count * idle;
                assertTrue(await(() -> budget.reserved() == expected, 5000),
                        "idle reservation expected " + expected + " but was " + budget.reserved());
            }
            try {
                V3Connection extra = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        table, null).get(10, TimeUnit.SECONDS);
                assertTrue(await(() -> !extra.isOpen(), 5000));
            } catch (java.util.concurrent.ExecutionException refused) {
                // Refused before the handshake completed.
            }
            assertEquals(n, peers.size());
        }
        assertTrue(await(() -> budget.reserved() == 0, 5000));
    }

    private static ServerIdentity identity;

    /** The server's log line for a connection refused by the node budget (QuicServer, 13.12). */
    private static final String BUDGET_REFUSAL = "QUIC /3 connection over the node pending-bytes budget; refusing it";

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

    @Test
    void anInjectedBudgetIsSharedByTheListenerAndGivesEveryByteBackWhenConnectionsClose() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        AtomicInteger accepted = new AtomicInteger();
        // The platform's own send-side reservations would share this very instance.
        ReceiveBudget budget = ReceiveBudget.bounded(TransportConfig.V3_INITIAL_EXPOSURE_BYTES + 1024);
        TransportConfig config = TransportConfig.builder().nodeBudget(budget).build();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config,
                stream -> accepted.incrementAndGet(), protocol -> V3ConnectionTest.TABLE)) {
            assertTrue(server.nodeBudget() == budget, "the listener enforces the injected instance");
            V3Connection first = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.get() == 1, 5_000), "the first connection fits");
            assertEquals(TransportConfig.V3_INITIAL_EXPOSURE_BYTES, budget.reserved(), "its credit is reserved");

            try {
                V3Connection second = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
                assertTrue(await(() -> !second.isOpen(), 5_000), "the second, over the shared budget, ends");
            } catch (java.util.concurrent.ExecutionException refusedAtHandshake) {
                // Also a refusal.
            }
            assertEquals(1, accepted.get(), "the second connection was refused");

            first.close();
            assertTrue(await(() -> budget.reserved() == 0, 5_000), "teardown returned every byte: " + budget.reserved());
        }
    }

    /**
     * 13.12 with the 13.13 exposure: a budget of exactly N times the per-connection exposure admits N
     * /3 connections and refuses the N+1th, so the reservation is what the server can really hold.
     */
    @Test
    void aBudgetOfExactlyNExposuresAdmitsNConnectionsAndRefusesTheNPlusFirst() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        final int n = 3;
        AtomicInteger accepted = new AtomicInteger();
        ReceiveBudget budget = ReceiveBudget.bounded(n * TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        TransportConfig config = TransportConfig.builder().nodeBudget(budget).build();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config,
                stream -> accepted.incrementAndGet(), protocol -> V3ConnectionTest.TABLE)) {
            for (int i = 1; i <= n; i++) {
                V3Connection conn = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
                final int expected = i;
                assertTrue(await(() -> accepted.get() == expected, 5_000), "connection " + i + " of " + n + " fits");
            }
            assertEquals(n * TransportConfig.V3_INITIAL_EXPOSURE_BYTES, budget.reserved(), "N exposures reserved");

            // The reason, not just the outcome: the server logs the budget refusal on its own loop, so
            // the log line is awaited with a bound.
            List<String> lines = Collections.synchronizedList(new ArrayList<String>());
            Logger logger = Logger.getLogger("QUICraft");
            Level previous = logger.getLevel();
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    lines.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            capture.setLevel(Level.ALL);
            logger.setLevel(Level.FINE);
            logger.addHandler(capture);
            try {
                try {
                    V3Connection extra = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                            V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
                    assertTrue(await(() -> !extra.isOpen(), 5_000), "the N+1th connection ends");
                } catch (java.util.concurrent.ExecutionException refusedAtHandshake) {
                    // Also a refusal.
                }
                assertEquals(n, accepted.get(), "the N+1th /3 connection was refused");
                assertTrue(await(() -> containsLine(lines, BUDGET_REFUSAL), 5_000),
                        "13.12: the refusal reason is the node pending-bytes budget, logged: " + lines);
            } finally {
                logger.removeHandler(capture);
                logger.setLevel(previous);
            }
        }
    }

    private static boolean containsLine(List<String> lines, String wanted) {
        synchronized (lines) {
            return lines.contains(wanted);
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
