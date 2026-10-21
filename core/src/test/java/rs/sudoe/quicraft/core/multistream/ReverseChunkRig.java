// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only access to a real N+3 server, with guard reads held outside the transport. */
public final class ReverseChunkRig implements AutoCloseable {
    private final NRig rig;
    private final List<ChannelHandlerContext> held = new ArrayList<>();
    public final AtomicInteger reads = new AtomicInteger();
    public final AtomicInteger bytes = new AtomicInteger();
    public final AtomicInteger workBeforeClose = new AtomicInteger();
    public final AtomicInteger synchronousCloses = new AtomicInteger();
    private final int shards;
    private boolean received;
    private boolean transportClosed;

    public ReverseChunkRig(int n) throws Exception {
        shards = n;
        rig = NRig.start(n);
        NRig.onLoop(rig.server, () -> {
            rig.serverConnection.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                    transportClosed = true;
                    ctx.close(promise);
                }
            });
            rig.server.reverseGuardSetupForTests = stream -> {
                stream.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void read(ChannelHandlerContext ctx) {
                        reads.incrementAndGet();
                        if (received && !transportClosed) workBeforeClose.incrementAndGet();
                        held.add(ctx);
                    }

                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        if (received && !transportClosed) workBeforeClose.incrementAndGet();
                        ctx.write(msg, promise);
                    }
                });
                stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        bytes.addAndGet(((ByteBuf) msg).readableBytes());
                        received = true;
                        ctx.fireChannelRead(msg);
                        // fail() must not merely enqueue the native close behind credit/send work.
                        if (transportClosed) synchronousCloses.incrementAndGet();
                    }
                });
            };
            return null;
        });
        rig.activateBoth();
    }

    public QuicStreamChannel clientChunk(int shard) throws Exception {
        return NRig.channel(rig.client, shard + 2);
    }

    /** The client's side of the server-opened ACK stream, logical index N+2 (13.21). */
    public QuicStreamChannel clientAck() throws Exception {
        return NRig.channel(rig.client, shards + 2);
    }

    /** Native send capacity of the reverse direction of the ACK stream. */
    public long ackCapacity() throws Exception {
        return capacityOf(clientAck());
    }

    public io.netty.channel.ChannelFuture writeAck(int value) throws Exception {
        return clientAck().writeAndFlush(Unpooled.wrappedBuffer(new byte[] {(byte) value}));
    }

    /** Native send capacity, not a Java write-queue or connection-close proxy for credit. */
    public long capacity(int shard) throws Exception {
        return capacityOf(clientChunk(shard));
    }

    private long capacityOf(QuicStreamChannel stream) throws Exception {
        return stream.eventLoop().submit(() -> ((Long) NStreamSession.findStreamCapacity(stream.parent().getClass())
                .invoke(stream.parent(), stream.streamId())).longValue()).get(5, TimeUnit.SECONDS);
    }

    public io.netty.channel.ChannelFuture write(int shard, int value) throws Exception {
        return clientChunk(shard).writeAndFlush(Unpooled.wrappedBuffer(new byte[] {(byte) value}));
    }

    public void releaseGuards() throws Exception {
        NRig.onLoop(rig.server, () -> {
            for (ChannelHandlerContext ctx : held) ctx.read();
            held.clear();
            return null;
        });
    }

    public int peerClose() throws Exception {
        return rig.net.clientSawClose.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() { rig.close(); }
}
