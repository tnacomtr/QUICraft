// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;

/** The /3 transport parameters of docs/protocol.md 13.13, and that /2 is untouched. */
class MultistreamTransportConfigTest {
    @Test
    void theMultistreamVariantCarriesTheSpecifiedLimitsAndLeavesTheDefaultAlone() {
        // Without the patched native /3 is refused (see the refusal test), so there is no variant.
        org.junit.jupiter.api.Assumptions.assumeTrue(QuicSupport.hasMaxFlowControlWindows());
        assertFalse(TransportConfig.DEFAULT.isMultistream());
        assertEquals(8L << 20, TransportConfig.DEFAULT.initialMaxStreamData);
        assertEquals(16L << 20, TransportConfig.DEFAULT.initialMaxData);
        TransportConfig config = TransportConfig.multistream();
        assertTrue(config.isMultistream());
        assertEquals(16L << 20, TransportConfig.MULTISTREAM_CLIENTBOUND_INITIAL_MAX_DATA);
        assertEquals(3 * (8L << 10), TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA);
        assertEquals(24L << 10, TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA);
        assertEquals(8L << 10, TransportConfig.MULTISTREAM_INITIAL_MAX_STREAM_DATA);
        assertEquals(2, TransportConfig.MULTISTREAM_SERVER_MAX_STREAMS_BIDI);
        assertEquals(1, TransportConfig.MULTISTREAM_CLIENT_MAX_STREAMS_BIDI);
        assertEquals(4L << 20, TransportConfig.MULTISTREAM_MAX_STREAM_WINDOW);
        assertEquals(12L << 20, TransportConfig.MULTISTREAM_MAX_CONNECTION_WINDOW);
    }

    @Test
    void theServerAllowsStreamsZeroAndFourAndTheClientAllowsTheServersStreamOneOnly() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        org.junit.jupiter.api.Assumptions.assumeTrue(QuicSupport.hasMaxFlowControlWindows());
        LaneLoopback.Options options = new LaneLoopback.Options();
        try (LaneLoopback net = LaneLoopback.open(options)) {
            QuicStreamChannel zero = net.clientStream();
            QuicStreamChannel four = net.clientStream();
            assertEquals(0, zero.streamId());
            assertEquals(4, four.streamId());
            assertFalse(net.clientConnection.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter()).awaitUninterruptibly().isSuccess(),
                    "a third client stream exceeds initial_max_streams_bidi=2");
            QuicChannel server = net.serverConnection.get(10, TimeUnit.SECONDS);
            assertEquals(1, server.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .get(5, TimeUnit.SECONDS).streamId());
            assertFalse(server.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .awaitUninterruptibly().isSuccess(),
                    "a second server stream exceeds initial_max_streams_bidi=1");
        }
    }

    /**
     * The node-budget reservation (13.12) must never fall below what the server's three pinned stream
     * windows can hold unread (13.13), and must equal the server's pinned connection window.
     */
    @Test
    void theExposureReservationEqualsTheThreePinnedServerStreamWindows() {
        assertTrue(TransportConfig.V3_INITIAL_EXPOSURE_BYTES
                >= TransportConfig.MULTISTREAM_STREAMS * TransportConfig.MULTISTREAM_INITIAL_MAX_STREAM_DATA);
        assertEquals(TransportConfig.MULTISTREAM_STREAMS * TransportConfig.MULTISTREAM_INITIAL_MAX_STREAM_DATA,
                TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        assertEquals(TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA,
                TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void theWindowMaximaReachTheCodecBuildersOrMultistreamIsRefused() throws Exception {
        boolean expected = Boolean.parseBoolean(System.getProperty("quicraft.test.expectKeyingMaterialExport"));
        assertEquals(expected, QuicSupport.hasMaxFlowControlWindows());
        if (!expected) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, TransportConfig::multistream);
            return;
        }
        TransportConfig config = TransportConfig.multistream();
        Object server = Codecs.apply(new io.netty.handler.codec.quic.QuicServerCodecBuilder(), config, true);
        // The server's maxima equal its initial serverbound windows: quiche's auto-growth is off.
        assertEquals(TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA, field(server, "maxConnectionWindow"));
        assertEquals(TransportConfig.V3_INITIAL_EXPOSURE_BYTES, field(server, "maxConnectionWindow"));
        assertEquals(8L << 10, field(server, "maxStreamWindow"));
        Object client = Codecs.apply(new io.netty.handler.codec.quic.QuicClientCodecBuilder(), config, false);
        assertEquals(12L << 20, field(client, "maxConnectionWindow"));
        assertEquals(4L << 20, field(client, "maxStreamWindow"));
    }
}
