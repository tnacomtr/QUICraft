// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.resource.ResourcePools;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * Production path of a pending {@code /3} newcomer (12.13) through {@link QuicServer}: the newcomer waits for a shed
 * victim's close, its TLS handshake completes meanwhile, and once it is granted its game connection, the handshake
 * it already had, and its streams all proceed. The wait is held deterministically with {@link QuicServer#heldWakes}.
 */
class V3PendingAdmissionTest {
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @AfterEach
    void reset() {
        QuicServer.heldWakes = null;
        QuicClient.forgetSessions();
    }

    @Test
    void aPendingNewcomerKeepsItsHandshakeAndItsStreamsAcrossTheGrant() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        List<V3Connection> accepted = Collections.synchronizedList(new ArrayList<V3Connection>());
        // One open connection, and room in the pre-login pool for exactly one staged connection: the second
        // connection must shed the first, and waits until the first's close is confirmed.
        ResourcePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, PreLoginTracker.stagedShare(5));
        TransportConfig config = TransportConfig.builder().maxOpen(1).resourcePools(pools).build();
        Queue<Runnable> held = new ConcurrentLinkedQueue<>();
        QuicServer.heldWakes = held;
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config, stream -> {
            V3Connection connection = (V3Connection) stream;
            accepted.add(connection);
            connection.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                    copy.put(data.duplicate());
                    copy.flip();
                    connection.write(copy);
                    connection.flush();
                }

                @Override
                public void onWritabilityChanged(boolean writable) {
                }

                @Override
                public void onClosed(Throwable cause) {
                }
            });
            connection.setAutoRead(true);
        }, protocol -> V3ConnectionTest.TABLE)) {
            V3Connection first = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.size() == 1, 5_000), "the first connection is accepted");

            CompletableFuture<V3Connection> secondAttempt = QuicClient.connectV3(server.localAddress(),
                    identity.fingerprint(), V3ConnectionTest.TABLE, null);
            assertTrue(await(() -> !held.isEmpty(), 5_000), "12.13: the newcomer waits for the victim's close");
            // The newcomer's TLS handshake completes while it waits. Its game connection does not exist yet.
            Thread.sleep(500);
            assertEquals(1, accepted.size(), "nothing is accepted while the newcomer is pending");

            // Grant: the held wake runs on the newcomer's own loop.
            QuicServer.heldWakes = null;
            for (Runnable dispatch = held.poll(); dispatch != null; dispatch = held.poll()) {
                dispatch.run();
            }
            V3Connection second = secondAttempt.get(10, TimeUnit.SECONDS);
            assertTrue(await(() -> accepted.size() == 2, 5_000),
                    "the granted newcomer reaches the acceptor, so its handshake was not lost while it waited");
            assertTrue(accepted.get(1).handshakeComplete(), "the handshake completed while the newcomer waited");

            // Its streams, held while it waited, resume: data flows both ways after the grant.
            Loopback.Collector echo = new Loopback.Collector();
            second.setListener(echo);
            second.setAutoRead(true);
            byte[] message = {1, 2, 3, 4, 5, 6, 7, 8};
            second.write(ByteBuffer.wrap(message));
            second.flush();
            assertArrayEquals(message, echo.await(message.length, 5, TimeUnit.SECONDS));
            second.close();
            first.close();
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
