// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge.status;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * A server-list status query over TCP, as the game sends it: handshake with intent "status",
 * status request, then the response's JSON. For a direct connect without a fresh advertisement
 * (docs/protocol.md §4). Uses only the game's Netty transport, no codec classes. Cancelling the
 * result closes the connection.
 */
public final class StatusQuery {
    /** Frame length VarInt + response: well above any valid status response. */
    static final int MAX_RESPONSE_BYTES = Wire.MAX_STATUS_BYTES + 16;

    private StatusQuery() {}

    /**
     * @param host the host name sent in the handshake, as the game would send it (virtual hosts
     *     route on it)
     * @param protocolVersion the game's protocol version
     */
    public static CompletableFuture<String> query(EventLoopGroup group, ChannelFactory<? extends Channel> sockets,
            InetSocketAddress server, String host, int protocolVersion) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ChannelFuture connect = new Bootstrap().group(group).channelFactory(sockets)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new Reader(result))
                .connect(server);
        result.whenComplete((json, error) -> connect.channel().close());
        connect.addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }
            Channel ch = f.channel();
            ByteBuf body = ch.alloc().buffer();
            Wire.writeVarInt(body, 0);
            Wire.writeVarInt(body, protocolVersion);
            Wire.writeString(body, host.length() > 255 ? host.substring(0, 255) : host);
            body.writeShort(server.getPort());
            Wire.writeVarInt(body, StatusAdvertising.INTENT_STATUS);
            ByteBuf out = ch.alloc().buffer();
            Wire.writeVarInt(out, body.readableBytes());
            out.writeBytes(body);
            body.release();
            Wire.writeVarInt(out, 1); // status request: length 1, id 0
            Wire.writeVarInt(out, 0);
            ch.writeAndFlush(out).addListener((ChannelFuture w) -> {
                if (!w.isSuccess()) {
                    result.completeExceptionally(w.cause());
                }
            });
        });
        return result;
    }

    /** Collects the first frame and decodes the status response from it. */
    private static final class Reader extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<String> result;
        private ByteBuf received;

        Reader(CompletableFuture<String> result) {
            this.result = result;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf in = (ByteBuf) msg;
            try {
                if (result.isDone()) {
                    return;
                }
                if (received == null) {
                    received = ctx.alloc().buffer();
                }
                received.writeBytes(in);
                if (received.readableBytes() > MAX_RESPONSE_BYTES) {
                    result.completeExceptionally(new IOException("status response too large"));
                    return;
                }
                tryDecode();
            } finally {
                in.release();
            }
        }

        private void tryDecode() {
            int start = received.readerIndex();
            Integer length = Wire.readVarInt(received);
            if (length == null || received.readableBytes() < length) {
                received.readerIndex(start);
                return; // incomplete
            }
            ByteBuf frame = received.readSlice(length);
            Integer id = Wire.readVarInt(frame);
            String json = id != null && id == 0 ? Wire.readString(frame, Wire.MAX_STATUS_BYTES) : null;
            if (json == null) {
                result.completeExceptionally(new IOException("not a status response"));
            } else {
                result.complete(json);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            release();
            result.completeExceptionally(new IOException("closed before the status response"));
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            release();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            result.completeExceptionally(cause);
            ctx.close();
        }

        private void release() {
            if (received != null) {
                received.release();
                received = null;
            }
        }
    }
}
