// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import rs.sudoe.quicraft.bridge.BoundaryCipherDecoder;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.fabric.net.BindingPayload;

/**
 * The pipeline side of a {@code /3} join, in front of the game's packet handler: it sees every
 * decoded packet before the game does and every written packet after it.
 *
 * <p><b>Inbound.</b> A {@code quicraft:binding} payload is taken off the wire here, on the
 * connection's event loop and synchronously inside the read that carried it, which the cipher
 * cutover of 12.13 depends on; it never reaches the game's packet listener, so no mod sees it
 * (12.8). A {@link GameLanes.Ack} is turned into an acknowledgement after the game's main thread
 * applied what it follows (13.10).</p>
 *
 * <p><b>Outbound.</b> Ordinary packets are held while the exchange pauses them (12.10), and until the
 * lanes are active after it. Anything else, such as the game's own protocol-switch tasks, passes.</p>
 */
final class V3Handler extends ChannelDuplexHandler implements DecodeContext.Gate {
    static final String NAME = "quicraft_v3";
    static final String TAIL_NAME = "quicraft_v3_tail";
    static final String SINK_NAME = "quicraft_v3_sink";

    private final V3Client client;
    private ChannelHandlerContext context;
    /** When the connection last delivered anything to the game (nanoTime). */
    private volatile long lastRead = System.nanoTime();
    /** Deliveries the game has been handed but not yet applied: while any, it is busy, not silent. */
    private final AtomicInteger unapplied = new AtomicInteger();
    private ScheduledFuture<?> silenceWatch;

    V3Handler(V3Client client) {
        this.client = client;
        client.attach(this);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    /** Whether a {@code quicraft:binding} payload arriving now is part of this join's exchange. */
    @Override
    public boolean expectsBinding() {
        V3Client.State state = client.state();
        return state == V3Client.State.LOGIN || state == V3Client.State.BINDING;
    }

    void deliveryQueued() {
        unapplied.incrementAndGet();
    }

    void deliveryApplied() {
        unapplied.decrementAndGet();
        lastRead = System.nanoTime();
    }

    /**
     * Vanilla's read timeout (30 s without a read, the pipeline's {@code timeout} handler) sits in
     * front of the byte path, which a {@code /3} connection leaves at activation, so nothing would
     * reset it. This takes over: the same {@code TimeoutException} reaches the game's own handling
     * when nothing was delivered for {@code timeoutNanos} and the game is not simply busy applying
     * what it was given.
     */
    void startSilenceWatch(long timeoutNanos) {
        lastRead = System.nanoTime();
        long period = Math.max(timeoutNanos / 6, TimeUnit.MILLISECONDS.toNanos(50));
        ChannelHandlerContext ctx = context;
        silenceWatch = ctx.executor().scheduleWithFixedDelay(() -> {
            if (!ctx.channel().isOpen()) {
                silenceWatch.cancel(false);
                return;
            }
            if (unapplied.get() > 0) {
                lastRead = System.nanoTime();
            } else if (System.nanoTime() - lastRead >= timeoutNanos) {
                silenceWatch.cancel(false);
                client.silent();
                ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
            }
        }, period, period, TimeUnit.NANOSECONDS);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        lastRead = System.nanoTime();
        try {
            if (msg instanceof GameLanes.Ack) {
                client.acknowledge((GameLanes.Ack) msg);
                return;
            }
            if (msg instanceof ClientboundCustomPayloadPacket) {
                Object payload = ((ClientboundCustomPayloadPacket) msg).payload();
                if (payload instanceof BindingPayload) {
                    client.onBindingPayload(((BindingPayload) payload).data());
                    return;
                }
            } else if (msg instanceof ClientboundLoginDisconnectPacket || msg instanceof ClientboundDisconnectPacket) {
                client.serverDisconnects();
            }
        } catch (Throwable t) {
            client.fail(t);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean held;
        try {
            held = msg instanceof Packet && client.hold(msg, promise);
        } catch (Throwable t) {
            client.fail(t);
            held = false;
        }
        if (!held) {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (silenceWatch != null) {
            silenceWatch.cancel(false);
        }
        client.channelClosed();
        ctx.fireChannelInactive();
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        client.closing();
        ctx.close(promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof GameLanes.LaneClosedException) {
            client.laneClosed((GameLanes.LaneClosedException) cause);
        } else if (client.state() == V3Client.State.ACTIVE) {
            // After activation: recorded (the next join uses TCP), then vanilla's own disconnect.
            client.failActive("protocol error");
        } else if (client.state() != V3Client.State.FAILED && !client.serverHasDisconnected()) {
            // Before activation any error is a /3 failure, handled BEFORE vanilla closes the
            // connection: vanilla's close would otherwise look like the player leaving and skip the
            // failure record and the TCP retry.
            client.fail("protocol error");
            return;
        }
        ctx.fireExceptionCaught(cause);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        try {
            client.writabilityChanged();
        } catch (Throwable t) {
            client.fail(t);
        }
        ctx.fireChannelWritabilityChanged();
    }

    /**
     * The last handler of the pipeline once the lanes are active. Packets pass it into the game's own
     * handlers (anything a mod or vanilla put in front of the encoder sees them as it always did),
     * unless the lanes cannot take more: then they wait here, in order, until they can.
     */
    static final class Tail extends ChannelOutboundHandlerAdapter {
        private final V3Client client;

        Tail(V3Client client) {
            this.client = client;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet && client.holdForLanes(ctx, msg, promise)) {
                return;
            }
            if (msg instanceof Packet) {
                client.stampSendState();
            }
            ctx.write(msg, promise);
        }
    }

    /**
     * Sits right behind the game's packet encoder: whatever the encoder wrote (and every handler in
     * front of it, vanilla's bundle unpacker and any mod's, saw first) is the packet's id and body,
     * which goes to the lane session instead of stream 0 (13.6). The encoder, its terminal-packet
     * swap and the handlers that remove themselves with it all run exactly as on TCP.
     */
    static final class Sink extends ChannelOutboundHandlerAdapter {
        private final V3Client client;

        Sink(V3Client client) {
            this.client = client;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                client.sendEncoded((io.netty.buffer.ByteBuf) msg, promise);
            } else {
                ctx.write(msg, promise);
            }
        }
    }
}
