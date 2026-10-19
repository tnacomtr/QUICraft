// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.Packet;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/** Two inert outbound taps. Both forward the original message and promise unchanged. */
public final class CausalSendTap {
    public static final String PACKET = "quicraft_causal_send_packet";
    public static final String BYTES = "quicraft_causal_send_bytes";
    private CausalSendTap() {}

    public static void install(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PACKET) != null || pipeline.get(BYTES) != null) throw new AssertionError("causal taps already installed");
        ChannelHandlerContext encoder = pipeline.context("encoder");
        if (encoder == null) throw new AssertionError("no actual encoder in pipeline: " + pipeline.names());
        pipeline.addBefore(encoder.name(), BYTES, new ByteTap());
        pipeline.addLast(PACKET, new PacketTap());
    }

    public static void remove(Channel channel) {
        Runnable remove = () -> {
            if (channel.pipeline().get(PACKET) != null) channel.pipeline().remove(PACKET);
            if (channel.pipeline().get(BYTES) != null) channel.pipeline().remove(BYTES);
        };
        if (channel.eventLoop().inEventLoop()) {
            remove.run();
            return;
        }
        try {
            channel.eventLoop().submit(remove).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted removing causal send taps", error);
        } catch (Exception error) {
            throw new AssertionError("bounded causal send tap removal failed", error);
        }
    }

    private static final class PacketTap extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            CausalSendProbe.Row row;
            try { row = CausalSendProbe.witness(ctx.channel(), msg); }
            catch (Throwable ignored) { ctx.write(msg, promise); return; }
            if (row == null) { ctx.write(msg, promise); return; }
            CausalSendProbe.Row previous = null;
            boolean tokenPushed = false;
            try {
                try {
                    row.channel = ctx.channel();
                    previous = CausalSendProbe.beginObservation(row);
                    tokenPushed = true;
                    CausalSendProbe.terminal(row, ctx.channel(), CausalSendContext.currentEnvelope(ctx.channel()), Thread.currentThread());
                } catch (Throwable error) {
                    record(row, "terminal observation: " + error);
                }
            } catch (Throwable error) {
                record(row, "packet observer: " + error);
            }
            if (row.id.equals("nested_outer")) {
                try { CausalSendGameTest.nestedInner(row); }
                catch (Throwable error) { record(row, "nested send observer: " + error); }
            }
            try {
                if (CausalSendProbe.observing() != row) record(row, "nested observer token was not restored");
                if (CausalSendContext.currentEnvelope(ctx.channel()) != row.envelope)
                    record(row, "nested send did not restore outer production envelope");
            } catch (Throwable error) {
                record(row, "outer restoration observation: " + error);
            }
            try {
                ctx.write(msg, promise);
            } finally {
                if (tokenPushed) {
                    try { CausalSendProbe.restoreObservation(previous); }
                    catch (Throwable error) { record(row, "observer token restore: " + error); }
                }
            }
        }
    }

    private static final class ByteTap extends ChannelOutboundHandlerAdapter {
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            CausalSendProbe.Row row = CausalSendProbe.observing();
            if (row != null && msg instanceof ByteBuf bytes) {
                try {
                    if (CausalSendContext.currentEnvelope(ctx.channel()) != row.envelope)
                        record(row, "encoded-byte boundary saw the wrong production envelope");
                    CausalSendProbe.encoded(row, bytes.readableBytes());
                } catch (Throwable error) {
                    record(row, "encoded-byte observation: " + error);
                }
            }
            ctx.write(msg, promise);
        }
    }

    private static void record(CausalSendProbe.Row row, String error) {
        try { row.error(error); } catch (Throwable ignored) { /* Observation must not block vanilla forwarding. */ }
    }
}
