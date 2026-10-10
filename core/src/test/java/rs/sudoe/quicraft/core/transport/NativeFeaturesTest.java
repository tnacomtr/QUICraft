// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The relaxed loss threshold from QUICraft's patched native (natives/patches/). Gradle sets
 * {@code quicraft.test.expectRelaxedLossThreshold}: true on a host whose native is the patched
 * build, false in {@code testUpstreamNative} (patched classes, upstream Netty native). Both runs
 * must connect; only the patched one may report the option.
 */
class NativeFeaturesTest {
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    private static boolean expected() {
        String value = System.getProperty("quicraft.test.expectRelaxedLossThreshold");
        assertNotNull(value, "run through Gradle, which says whether this host has a patched native");
        return Boolean.parseBoolean(value);
    }

    @Test
    void relaxedLossThresholdIsAvailableExactlyWithThePatchedNative() {
        assertEquals(expected(), QuicSupport.hasRelaxedLossThreshold());
    }

    @Test
    void builderTakesTheOptionOnlyWhenTheNativeHasIt() {
        QuicServerCodecBuilder builder = new QuicServerCodecBuilder();
        if (expected()) {
            builder.relaxedLossThreshold(true);
        } else {
            assertThrows(UnsupportedOperationException.class, () -> builder.relaxedLossThreshold(true));
        }
    }

    @Test
    void connectsWithTheOptionRequestedEitherWay() throws Exception {
        TransportConfig config = TransportConfig.builder().relaxedLossThreshold(true).build();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(), config)
                    .get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            byte[] hello = "relaxed".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(hello));
            stream.flush();
            assertArrayEquals(hello, collector.await(hello.length, 5, TimeUnit.SECONDS));
            stream.close();
        }
    }

    /**
     * Proves the option reaches quiche: through a relay that reorders like the testkit's reorder
     * profile (both directions, 75% of packets delayed by 10 ms, the rest overtaking them, nothing
     * dropped), BBR with the relaxed threshold declares far fewer packets lost than without it.
     * Every loss here is spurious.
     */
    @Test
    void relaxedThresholdCutsSpuriousLossesUnderReordering() throws Exception {
        assumeTrue(expected(), "needs the patched native");
        long off = spuriousLosses(false);
        long on = spuriousLosses(true);
        System.out.printf("2 MiB through a reordering relay, BBR: lost %d without, %d with the relaxed threshold%n",
                off, on);
        assertTrue(off >= 20, "reordering should cause spurious losses without the option, got " + off);
        // Measured 2-4x fewer (1884 -> 908, 1969 -> 897, 1778 -> 466); assert a margin below that.
        assertTrue(on * 4 <= off * 3, "relaxed threshold should cut spurious losses: " + off + " -> " + on);
    }

    private static long spuriousLosses(boolean relaxed) throws Exception {
        TransportConfig config = TransportConfig.builder()
                .congestionControl(TransportConfig.CongestionControl.BBR)
                .relaxedLossThreshold(relaxed)
                .build();
        int size = 2 << 20;
        byte[] payload = new byte[size];
        new Random(7).nextBytes(payload);
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, config, stream -> {
                    accepted.complete(stream);
                    stream.setListener(new QuicByteStream.Listener() {
                        @Override
                        public void onData(ByteBuffer data) {
                            for (int off = 0; off < size; off += 16384) {
                                stream.write(ByteBuffer.wrap(payload, off, Math.min(16384, size - off)));
                            }
                            stream.flush();
                        }

                        @Override
                        public void onWritabilityChanged(boolean writable) {}

                        @Override
                        public void onClosed(Throwable cause) {}
                    });
                    stream.setAutoRead(true);
                });
                UdpRelay relay = new UdpRelay(server.localAddress(), 10, 0.25, 1)) {
            QuicByteStream client = QuicClient.connect(relay.address(), identity.fingerprint(), config)
                    .get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            client.setListener(collector);
            client.setAutoRead(true);
            client.write(ByteBuffer.wrap(new byte[] {1}));
            client.flush();
            assertEquals(size, collector.await(size, 30, TimeUnit.SECONDS).length, "transfer incomplete");
            Map<String, Long> stats = accepted.get(5, TimeUnit.SECONDS).connectionStats().get(5, TimeUnit.SECONDS);
            client.close();
            return stats.get("lost");
        }
    }
}
