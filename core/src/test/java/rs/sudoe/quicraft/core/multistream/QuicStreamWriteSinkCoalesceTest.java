// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;

/** Tests opt-in per-stream write coalescing in QuicStreamWriteSink (quicraft.coalesceWrites). */
final class QuicStreamWriteSinkCoalesceTest {

    private static final class Stream extends ChannelOutboundHandlerAdapter {
        final EmbeddedChannel channel = new EmbeddedChannel(this);
        final List<String> events = new ArrayList<>();
        final int index;
        int writes;
        int flushCalls;
        boolean writable = true;
        boolean failNextWrite = false;

        Stream(int index) { this.index = index; }

        QuicStreamChannel proxy() {
            QuicChannel parent = (QuicChannel) Proxy.newProxyInstance(
                    QuicChannel.class.getClassLoader(),
                    new Class<?>[] { QuicChannel.class },
                    (proxy, method, args) -> {
                        if ("isWritable".equals(method.getName())) return true;
                        throw new AssertionError(method.getName());
                    });
            return (QuicStreamChannel) Proxy.newProxyInstance(
                    QuicStreamChannel.class.getClassLoader(),
                    new Class<?>[] { QuicStreamChannel.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "pipeline": return channel.pipeline();
                            case "parent": return parent;
                            case "isActive": return channel.isActive();
                            case "isWritable": return writable;
                            case "write": return channel.write(args[0]);
                            case "writeAndFlush": return channel.writeAndFlush(args[0]);
                            case "flush":
                                flushCalls++;
                                events.add("flush" + index);
                                channel.flush();
                                return proxy;
                            default:
                                throw new AssertionError(method.getName());
                        }
                    });
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            writes++;
            ByteBuf buf = (ByteBuf) msg;
            int len = buf.readableBytes();
            events.add("write" + index + ":" + len);
            if (failNextWrite) {
                failNextWrite = false;
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IllegalStateException("injected failure"));
            } else {
                ctx.write(msg, promise);
            }
        }
    }

    private static class Rig {
        final Object owner = new Object();
        final OutboundPool pool;
        final SessionSendDriver driver;
        final QuicStreamWriteSink sink;
        final Stream[] streams;
        int successes;
        int failures;
        final List<String> events = new ArrayList<>();

        Rig(boolean coalesce) throws Exception {
            PressureRanking ranking = new PressureRanking();
            pool = new OutboundPool(
                    new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                    1L << 40, 1L << 40, 1L << 40,
                    ranking.view(() -> 1L));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(
                    SessionContext.of(1), profile, pool, ranking, owner,
                    InetAddress.getLoopbackAddress(), null, () -> 1L);
            sink = new QuicStreamWriteSink(driver, 3, i -> true, success -> {
                if (success) successes++;
                else failures++;
            }, coalesce);
            streams = new Stream[3];
            for (int i = 0; i < streams.length; i++) {
                streams[i] = new Stream(i);
                sink.attach(i, streams[i].proxy());
            }
        }

        void submit(byte[] bytes, StreamSequencer.Fence fence, int stream) {
            assertTrue(driver.submit(
                    new OutboundPacket(bytes), fence, stream == 0 ? 0 : stream, 0).isAssigned());
        }

        void close() {
            driver.close();
        }
    }

    private static byte[] frame(int content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            VarInt.write(out, content);
            byte[] body = new byte[content];
            Arrays.fill(body, (byte) 7);
            out.write(body, 0, body.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    @Test
    void coalesceOffWritesOncePerSlice() throws Exception {
        Rig rig = new Rig(false);
        try {
            rig.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.submit(frame(512), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.sink.drain();
            assertEquals(2, rig.streams[0].writes, "two separate writes with coalesce off");
            assertEquals(2, rig.successes);
        } finally {
            rig.close();
        }
    }

    @Test
    void coalesceOnCombinesSameStream() throws Exception {
        Rig rig = new Rig(true);
        try {
            rig.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.submit(frame(512), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.sink.drain();
            assertEquals(1, rig.streams[0].writes, "one combined write for same stream");
            List<String> events = rig.streams[0].events;
            assertTrue(events.size() >= 1, "at least one event");
            // The combined write should contain both bytes (1024 + 512 + header overhead)
            assertEquals(2, rig.successes, "both slices settled");
        } finally {
            rig.close();
        }
    }

    @Test
    void coalesceOnWritesPerStream() throws Exception {
        Rig rig = new Rig(true);
        try {
            rig.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.submit(frame(512), StreamSequencer.Fence.CHUNK, 2);
            rig.sink.drain();
            assertEquals(1, rig.streams[0].writes, "one write for stream 0");
            assertEquals(1, rig.streams[2].writes, "one write for stream 2");
        } finally {
            rig.close();
        }
    }

    @Test
    void coalesceCapSplitsWrites() throws Exception {
        Rig rig = new Rig(true);
        try {
            // Submit many small records so total exceeds 64 KiB cap
            for (int i = 0; i < 10; i++) {
                rig.submit(frame(10000), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            }
            rig.sink.drain();
            // Should have split into at least 2 writes due to 64 KiB cap
            assertTrue(rig.streams[0].writes >= 2,
                    "cap should split into multiple writes, got " + rig.streams[0].writes);
        } finally {
            rig.close();
        }
    }

    @Test
    void coalesceFailedWriteCallsFailedForAllTokens() throws Exception {
        Rig rig = new Rig(true);
        try {
            rig.streams[0].failNextWrite = true;
            rig.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.submit(frame(512), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.sink.drain();
            // Even with coalesce, the combined write should call failed for both tokens
            assertEquals(0, rig.successes, "no successes when combined write fails");
            assertEquals(2, rig.failures, "both tokens failed");
        } finally {
            rig.close();
        }
    }

    @Test
    void coalesceOnWithCapStopsFurtherPoll() throws Exception {
        Rig rig = new Rig(true);
        try {
            // A large single slice that pushes past the cap
            rig.submit(frame(70000), StreamSequencer.Fence.ORDINARY_CONTROL, 0);
            rig.sink.drain();
            assertTrue(rig.streams[0].writes >= 1, "at least one write");
            // After reaching cap, no more writes
            assertTrue(rig.streams[0].writes <= 2, "cap limits writes");
        } finally {
            rig.close();
        }
    }
}
