// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import rs.sudoe.quicraft.core.multistream.JoinDiag;

/**
 * Diagnostics only: marks the first datagram each way on one {@code /3} attempt's UDP socket, then
 * passes every message on unchanged. It is in the pipeline only when join marks are on
 * ({@code -Dquicraft.v3.joindiag=true}); with them off the codec is the whole pipeline, as before.
 */
final class JoinDatagramProbe extends ChannelDuplexHandler {
    private final JoinDiag diag;
    private boolean sent;
    private boolean received;

    JoinDatagramProbe(JoinDiag diag) {
        this.diag = diag;
    }

    /**
     * The codec behind a probe when {@code diag} is non-null, or the codec itself. Used as a bootstrap
     * handler, so the probe is added when the channel is initialised, ahead of the codec.
     */
    static ChannelHandler wrap(final ChannelHandler codec, final JoinDiag diag) {
        if (diag == null) {
            return codec;
        }
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new JoinDatagramProbe(diag), codec);
            }
        };
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!sent) {
            sent = true;
            JoinDiag.mark(diag, "first outbound datagram");
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!received) {
            received = true;
            JoinDiag.mark(diag, "first inbound datagram");
        }
        ctx.fireChannelRead(msg);
    }
}
