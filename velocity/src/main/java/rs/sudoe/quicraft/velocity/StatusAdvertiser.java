// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.velocitypowered.proxy.protocol.packet.StatusResponsePacket;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import rs.sudoe.quicraft.core.discovery.Advertisement;

/**
 * Adds the QUIC advertisement to Velocity's status response (docs/protocol.md §1). Sits right
 * after {@code minecraft-encoder}, so it sees the packet object before it is encoded. The
 * advertisement is left out whenever it would push the response over the status length cap,
 * and on any error the original packet goes out unchanged.
 */
@ChannelHandler.Sharable
final class StatusAdvertiser extends ChannelOutboundHandlerAdapter {
    static final String NAME = "quicraft-advertiser";
    static final String AFTER = "minecraft-encoder";

    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();
    /** Null once the QUIC listener is gone: nothing is advertised. */
    private volatile Advertisement advertisement;

    StatusAdvertiser(Advertisement advertisement, Logger logger) {
        this.advertisement = advertisement;
        this.logger = logger;
    }

    void withdraw() {
        advertisement = null;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        Advertisement ad = advertisement;
        if (ad != null && msg instanceof StatusResponsePacket status) {
            try {
                String json = status.getStatus();
                String advertised = ad.insertInto(json);
                if (!advertised.equals(json)) {
                    msg = new StatusResponsePacket(advertised);
                }
            } catch (Throwable t) {
                if (warned.compareAndSet(false, true)) {
                    logger.warn("QUICraft: could not add the QUIC advertisement to a status response; "
                            + "sending it unchanged", t);
                }
            }
        }
        ctx.write(msg, promise);
    }
}
