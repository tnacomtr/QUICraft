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

    static <B extends QuicCodecBuilder<B>> B apply(B builder, TransportConfig config, boolean server) {
        // Only QUICraft's patched native has the binding; elsewhere quiche's default (off) stays.
        if (QuicSupport.hasRelaxedLossThreshold()) {
            builder.relaxedLossThreshold(config.relaxedLossThreshold);
        }
        return builder
                .maxIdleTimeout(config.maxIdleTimeoutMillis, TimeUnit.MILLISECONDS)
                .initialMaxData(config.initialMaxData)
                .initialMaxStreamDataBidirectionalLocal(config.initialMaxStreamData)
                .initialMaxStreamDataBidirectionalRemote(config.initialMaxStreamData)
                .initialMaxStreamDataUnidirectional(0)
                // v1: exactly one client-opened bidirectional stream; the server opens none.
                .initialMaxStreamsBidirectional(server ? 1 : 0)
                .initialMaxStreamsUnidirectional(0)
                .activeMigration(false)
                .congestionControlAlgorithm(algorithm(config.congestionControl));
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
