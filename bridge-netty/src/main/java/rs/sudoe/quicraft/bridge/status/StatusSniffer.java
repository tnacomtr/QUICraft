// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge.status;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.Optional;
import java.util.function.Consumer;
import rs.sudoe.quicraft.core.discovery.Advertisement;

/**
 * Client side of a server-list ping: reads the QUIC advertisement from the status response as
 * it passes, without changing it, so the game's own status parsing (which drops unknown members)
 * never has to know. Sits after the frame decoder of a status connection, reports once, then
 * leaves. A first packet that isn't a status response reports "no advertisement".
 */
public final class StatusSniffer extends ChannelInboundHandlerAdapter {
    public static final String NAME = "quicraft-sniffer";

    private final Consumer<Optional<Advertisement>> result;

    private StatusSniffer(Consumer<Optional<Advertisement>> result) {
        this.result = result;
    }

    /** Installs after {@code frameDecoder}; does nothing if that is missing. */
    public static void install(ChannelPipeline pipeline, String frameDecoder, Consumer<Optional<Advertisement>> result) {
        if (pipeline.get(frameDecoder) != null && pipeline.get(NAME) == null) {
            pipeline.addAfter(frameDecoder, NAME, new StatusSniffer(result));
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Optional<Advertisement> ad = Optional.empty();
        if (msg instanceof ByteBuf) {
            ByteBuf frame = (ByteBuf) msg;
            int start = frame.readerIndex();
            try {
                Integer id = Wire.readVarInt(frame);
                String json = id != null && id == 0 ? Wire.readString(frame, Wire.MAX_STATUS_BYTES) : null;
                if (json != null) {
                    ad = Advertisement.extract(json);
                }
            } catch (RuntimeException e) {
                ad = Optional.empty();
            } finally {
                frame.readerIndex(start);
            }
        }
        ctx.pipeline().remove(this);
        try {
            result.accept(ad);
        } finally {
            ctx.fireChannelRead(msg);
        }
    }
}
