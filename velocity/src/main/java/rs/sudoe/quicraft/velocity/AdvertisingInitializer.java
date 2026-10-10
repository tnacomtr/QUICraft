// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;

/**
 * Wraps Velocity's server channel initializer (or whatever another plugin set before us). The
 * wrapped initializer runs first and builds the vanilla pipeline unchanged; then the
 * {@link StatusAdvertiser} goes in after {@code minecraft-encoder}. Applies to TCP and QUIC
 * channels alike, since server-list pings arrive over TCP.
 */
@ChannelHandler.Sharable
final class AdvertisingInitializer extends ChannelInitializer<Channel> {
    private final ChannelInitializer<Channel> delegate;
    private final StatusAdvertiser advertiser;
    private final Logger logger;

    AdvertisingInitializer(ChannelInitializer<Channel> delegate, StatusAdvertiser advertiser, Logger logger) {
        this.delegate = delegate;
        this.advertiser = advertiser;
        this.logger = logger;
    }

    ChannelInitializer<Channel> delegate() {
        return delegate;
    }

    @Override
    protected void initChannel(Channel ch) {
        // The channel is registered and we're on its event loop, so the delegate's initChannel
        // runs right here, inside addLast, exactly as Velocity would run it.
        ch.pipeline().addLast(delegate);
        try {
            ChannelPipeline pipeline = ch.pipeline();
            if (pipeline.get(StatusAdvertiser.AFTER) != null && pipeline.get(StatusAdvertiser.NAME) == null) {
                pipeline.addAfter(StatusAdvertiser.AFTER, StatusAdvertiser.NAME, advertiser);
            }
        } catch (Throwable t) {
            logger.debug("QUICraft: advertiser not installed on {}", ch, t);
        }
    }
}
