// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge.status;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import rs.sudoe.quicraft.core.discovery.Advertisement;

/**
 * Adds the QUIC advertisement to a game server's status response (docs/protocol.md §1) at the
 * byte level, so it doesn't depend on the game's status classes. Two handlers per connection:
 *
 * <ul>
 *   <li>after the frame decoder: reads the handshake's intent from the first frame, then
 *       leaves. Not a status connection: the rewriter leaves too.</li>
 *   <li>after the frame encoder (so it sees each packet before framing): rewrites the first
 *       outbound packet with id 0, the status response, then leaves.</li>
 * </ul>
 *
 * Bytes pass through unchanged on anything unexpected, when the advertisement is withdrawn
 * (null), or when it would push the response over the status length cap.
 */
public final class StatusAdvertising {
    public static final String INTENT = "quicraft-intent";
    public static final String REWRITER = "quicraft-advertiser";
    /** Handshake intent for a status (server list) connection. */
    static final int INTENT_STATUS = 1;

    private StatusAdvertising() {}

    /**
     * Installs the handlers next to the game's frame decoder and encoder. Does nothing if either
     * is missing or the handlers are already there.
     */
    public static void install(ChannelPipeline pipeline, String frameDecoder, String frameEncoder,
            Supplier<Advertisement> advertisement) {
        if (pipeline.get(frameDecoder) == null || pipeline.get(frameEncoder) == null
                || pipeline.get(INTENT) != null) {
            return;
        }
        Rewriter rewriter = new Rewriter(advertisement);
        pipeline.addAfter(frameEncoder, REWRITER, rewriter);
        pipeline.addAfter(frameDecoder, INTENT, new IntentReader(rewriter));
    }

    private static final class IntentReader extends ChannelInboundHandlerAdapter {
        private final Rewriter rewriter;

        IntentReader(Rewriter rewriter) {
            this.rewriter = rewriter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            boolean status = false;
            if (msg instanceof ByteBuf) {
                ByteBuf frame = (ByteBuf) msg;
                int start = frame.readerIndex();
                try {
                    Integer intent = Wire.handshakeIntent(frame);
                    status = intent != null && intent == INTENT_STATUS;
                } catch (RuntimeException e) {
                    status = false;
                } finally {
                    frame.readerIndex(start);
                }
            }
            ctx.pipeline().remove(this);
            if (!status && ctx.pipeline().context(rewriter) != null) {
                ctx.pipeline().remove(rewriter);
            }
            ctx.fireChannelRead(msg);
        }
    }

    private static final class Rewriter extends ChannelOutboundHandlerAdapter {
        private final Supplier<Advertisement> advertisement;

        Rewriter(Supplier<Advertisement> advertisement) {
            this.advertisement = advertisement;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof ByteBuf)) {
                ctx.write(msg, promise);
                return;
            }
            ctx.pipeline().remove(this);
            ctx.write(rewrite(ctx, (ByteBuf) msg), promise);
        }

        private ByteBuf rewrite(ChannelHandlerContext ctx, ByteBuf packet) {
            int start = packet.readerIndex();
            ByteBuf out = null;
            try {
                Advertisement ad = advertisement.get();
                Integer id = Wire.readVarInt(packet);
                String json = id != null && id == 0 ? Wire.readString(packet, Wire.MAX_STATUS_BYTES) : null;
                if (ad == null || json == null || packet.isReadable()) {
                    return packet;
                }
                String advertised = ad.insertInto(json);
                if (advertised.equals(json)) {
                    return packet;
                }
                byte[] bytes = advertised.getBytes(StandardCharsets.UTF_8);
                out = ctx.alloc().buffer(1 + Wire.varIntSize(bytes.length) + bytes.length);
                Wire.writeVarInt(out, 0);
                Wire.writeVarInt(out, bytes.length);
                out.writeBytes(bytes);
                packet.release();
                ByteBuf result = out;
                out = null;
                return result;
            } catch (RuntimeException e) {
                if (out != null) {
                    out.release();
                }
                return packet;
            } finally {
                if (packet.refCnt() > 0) {
                    packet.readerIndex(start);
                }
            }
        }
    }
}
