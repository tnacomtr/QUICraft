// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.quic.QuicCodecBuilder;
import io.netty.handler.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.QuicSupport;

/** Applies {@link TransportConfig} to Netty's codec builders (docs/protocol.md §9). */
final class Codecs {
    private Codecs() {}

    /**
     * Length of the connection ids the listener issues. Equal to Netty's default (quiche's maximum,
     * 20); set explicitly so {@link IngressGuard} parses headers with the same value as the codec.
     */
    static final int LOCAL_CONNECTION_ID_LENGTH = io.netty.handler.codec.quic.Quic.MAX_CONN_ID_LEN;

    static <B extends QuicCodecBuilder<B>> B apply(B builder, TransportConfig config, boolean server) {
        // Only QUICraft's patched native has the binding; elsewhere quiche's default (off) stays.
        if (QuicSupport.hasRelaxedLossThreshold()) {
            builder.relaxedLossThreshold(config.relaxedLossThreshold);
        }
        long maxData = config.initialMaxData;
        long localStreamData = config.initialMaxStreamData;
        long remoteStreamData = config.initialMaxStreamData;
        // v1: exactly one client-opened bidirectional stream; the server opens none.
        int maxStreams = server ? 1 : 0;
        if (config.multistream) {
            // /3 (13.13): small serverbound windows, a 16 MiB clientbound one, streams 0 and 4 from
            // the client and stream 1 from the server.
            maxData = server ? TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA
                    : TransportConfig.MULTISTREAM_CLIENTBOUND_INITIAL_MAX_DATA;
            // "local" and "remote" are relative to this endpoint (RFC 9000 18.2): "local" streams are
            // the ones this endpoint opened. A server grants only the one-byte reverse-CHUNK guard on
            // its own streams, and 8 KiB on the CONTROL and REALTIME streams the client opened (13.13).
            // The client grants 256 KiB on both kinds of stream.
            localStreamData = server ? serverOpenedStreamData() : TransportConfig.MULTISTREAM_MAX_STREAM_WINDOW;
            remoteStreamData = server ? TransportConfig.MULTISTREAM_INITIAL_MAX_STREAM_DATA
                    : TransportConfig.MULTISTREAM_MAX_STREAM_WINDOW;
            maxStreams = server ? TransportConfig.MULTISTREAM_SERVER_MAX_STREAMS_BIDI
                    : TransportConfig.MULTISTREAM_CLIENT_MAX_STREAMS_BIDI;
        }
        if (config.multistream) {
            if (!QuicSupport.hasMaxFlowControlWindows()) {
                throw new IllegalStateException("/3 needs the patched native that caps flow-control windows");
            }
            if (server) {
                // Equal to the initial serverbound windows: quiche clamps every window to its
                // maximum, so auto-growth is off and the exposure is the credit the node reserved.
                builder.maxConnectionWindow(TransportConfig.MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA);
                builder.maxStreamWindow(TransportConfig.MULTISTREAM_INITIAL_MAX_STREAM_DATA);
            } else {
                builder.maxConnectionWindow(TransportConfig.MULTISTREAM_MAX_CONNECTION_WINDOW);
                builder.maxStreamWindow(TransportConfig.MULTISTREAM_MAX_STREAM_WINDOW);
            }
        }
        B configured = builder
                .maxIdleTimeout(config.maxIdleTimeoutMillis, TimeUnit.MILLISECONDS)
                .initialMaxData(maxData)
                .initialMaxStreamDataBidirectionalLocal(localStreamData)
                .initialMaxStreamDataBidirectionalRemote(remoteStreamData)
                .initialMaxStreamDataUnidirectional(0)
                .initialMaxStreamsBidirectional(maxStreams)
                .initialMaxStreamsUnidirectional(0)
                .activeMigration(false)
                .localConnectionIdLength(LOCAL_CONNECTION_ID_LENGTH)
                .congestionControlAlgorithm(algorithm(config.congestionControl));
        if (config.initialCongestionWindowPackets() > 0) {
            configured.initialCongestionWindowPackets(config.initialCongestionWindowPackets());
        }
        return configured;
    }

    /**
     * The server's window on its own bidi streams (13.13 reverse CHUNK): one byte by spec, or the legacy
     * 8 KiB while {@link TransportConfig#LEGACY_LANE_SESSION} keeps the legacy LaneSession in place.
     */
    private static long serverOpenedStreamData() {
        return TransportConfig.LEGACY_LANE_SESSION ? TransportConfig.LEGACY_LANE_REVERSE_STREAM_DATA
                : TransportConfig.MULTISTREAM_REVERSE_CHUNK_STREAM_DATA;
    }

    private static QuicCongestionControlAlgorithm algorithm(TransportConfig.CongestionControl cc) {
        switch (cc) {
            case RENO:
                return QuicCongestionControlAlgorithm.RENO;
            case BBR:
                return QuicCongestionControlAlgorithm.BBR;
            case CUBIC:
            default:
                return QuicCongestionControlAlgorithm.CUBIC;
        }
    }

    /**
     * Closes a QUIC connection in a task on its event loop, never inside the caller's (possibly
     * Netty-internal) call stack, e.g. from a connect listener inside Netty's connect processing.
     */
    static void closeLater(io.netty.handler.codec.quic.QuicChannel connection, boolean application, int code) {
        connection.eventLoop().execute(
                () -> connection.close(application, code, io.netty.buffer.Unpooled.EMPTY_BUFFER));
    }

    /** NIO only: quiche is the single native QUICraft ships. Daemon threads. */
    static EventLoopGroup newGroup(String name, int threads) {
        return new MultiThreadIoEventLoopGroup(threads, new DefaultThreadFactory(name, true), NioIoHandler.newFactory());
    }
}
