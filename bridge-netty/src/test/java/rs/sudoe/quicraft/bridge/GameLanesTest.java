// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The game side of a {@code /3} connection (docs/protocol.md 13.6, 13.10, 13.12, 13.14) with a
 * fake codec and a fake session: no Minecraft classes, no QUIC connection.
 */
class GameLanesTest {
    private static EventLoopGroup loop;

    @BeforeAll
    static void setUp() {
        loop = new NioEventLoopGroup(1);
    }

    @AfterAll
    static void tearDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void deliverFiresThePacketsThenTheAckInOrder() {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.CONFIGURATION, null);

        lanes.deliver(Lane.CONTROL, 5, Arrays.asList("spawn", "move"), false);

        assertSame("spawn", channel.readInbound());
        assertSame("move", channel.readInbound());
        GameLanes.Ack ack = channel.readInbound();
        assertEquals(Lane.CONTROL, ack.lane());
        assertEquals(5, ack.sequence(), "13.10: the final sequence is what the game acknowledges");
        assertFalse(ack.bundle());
    }

    @Test
    void deliverFromAnotherThreadKeepsTheDeliveryOrder() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        OrderCollector collector = new OrderCollector();
        channel.pipeline().addLast(collector);
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);

        // The test thread is not the game channel's event loop.
        lanes.deliver(Lane.CONTROL, 1, Collections.singletonList("first"), false);
        lanes.deliver(Lane.WORLD, 2, Collections.singletonList("second"), false);
        collector.await(4);

        assertEquals(Arrays.asList("first", "ack:CONTROL:1", "second", "ack:WORLD:2"), collector.seen,
                "every delivery is fired whole, in arrival order, on the game's own loop");
        assertFalse(collector.offLoop.get(), "never off the game channel's event loop");
        channel.close().sync();
    }

    @Test
    void writabilityChangesReachTheGamePipelineAndIsWritable() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        OrderCollector collector = new OrderCollector();
        channel.pipeline().addLast(collector);
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);

        lanes.onWritabilityChanged(false);
        collector.await(1);

        assertFalse(lanes.isWritable(), "13.12: the game stops submitting while this is false");
        assertEquals(1, collector.writabilityChanges.get());
        lanes.onWritabilityChanged(true);
        assertTrue(lanes.isWritable());
        channel.close().sync();
    }

    @Test
    void anOrderingFailureClosesTheGameChannelWithTheCodeInTheCause() {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);

        lanes.onClosed(LaneSession.CLOSE_ORDERING, false, null);

        assertTrue(lanes.isClosed());
        assertFalse(channel.isOpen(), "13.14: the game's channel goes away with the connection");
        GameLanes.LaneClosedException cause = (GameLanes.LaneClosedException) seen.get();
        assertNotNull(cause);
        assertEquals(LaneSession.CLOSE_ORDERING, cause.code());
        assertTrue(cause.getMessage().contains("0x11"),
                "13.14: the cause names the application error code: " + cause.getMessage());
    }

    @Test
    void aNormalEndClosesTheChannelWithoutAnError() {
        FakeCodec codec = new FakeCodec();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = new GameLanes(channel, codec, new FakeSession(), ProtocolState.PLAY, null);

        lanes.onClosed(LaneSession.CLOSE_NORMAL, true, null);

        assertFalse(channel.isOpen());
        channel.checkException();
    }

    @Test
    void decodeHandsTheFrameAndTheActivationStateToThePlatform() throws Exception {
        FakeCodec codec = new FakeCodec();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = new GameLanes(channel, codec, new FakeSession(), ProtocolState.CONFIGURATION, null);

        LaneGame.Decoded decoded = lanes.decode(Lane.CONTROL, 3, new byte[] {4, 5, 6}, false, null);

        assertEquals(ProtocolState.CONFIGURATION, codec.decodeStates.get(0));
        assertArrayEquals(new byte[] {4, 5, 6}, codec.decodedFrames.get(0));
        assertEquals(1, codec.frameRefCountDuringDecode, "the frame is alive while it is decoded");
        assertEquals(0, codec.seenFrame.refCnt(), "and released once the codec is done with it");
        assertEquals(9, decoded.packetId());
        assertEquals(ProtocolState.PLAY, decoded.state());
    }

    @Test
    void appliedReachesTheSession() {
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = new GameLanes(channel, new FakeCodec(), session, ProtocolState.PLAY, null);

        lanes.applied(Lane.WORLD, 11);

        assertEquals(Collections.singletonList(Lane.WORLD + ":11"), session.applied);
    }

    @Test
    void outboundPacketsGoThroughTheSessionAndNeverToStreamZero() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannelHandOverTest.FakeStream stream = new QuicBridgeChannelHandOverTest.FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        session.assign = Lane.REALTIME;
        codec.routes.put("move", new GameLanes.Route(ProtocolState.PLAY, 42, Lane.REALTIME));

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("move"))).sync();

        assertTrue(write.get().isSuccess());
        assertEquals(1, session.sent.size());
        assertEquals(ProtocolState.PLAY + ":42", session.sent.get(0));
        assertArrayEquals("frame:move".getBytes(StandardCharsets.US_ASCII), session.frames.get(0),
                "the platform's own encoder produced the frame");
        assertEquals(0, stream.writtenBytes().length,
                "nothing may reach stream 0 once the session owns it (13.4)");
        channel.close().sync();
    }

    @Test
    void aLaneTheRoutingTableDisagreesWithAbortsTheConnection() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannelHandOverTest.FakeStream stream = new QuicBridgeChannelHandOverTest.FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        // The platform says CONTROL, the session's routing table assigns WORLD.
        session.assign = Lane.WORLD;
        codec.routes.put("spawn", new GameLanes.Route(ProtocolState.PLAY, 7, Lane.CONTROL));
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("spawn"))).sync();

        assertFalse(write.get().isSuccess(), "13.6: a wrong-lane packet is never put on the wire");
        assertTrue(seen.get() instanceof GameLanes.LaneMismatchException, String.valueOf(seen.get()));
        assertEquals(Collections.singletonList(LaneSession.CLOSE_ORDERING), session.aborted,
                "13.6/13.14: a routing disagreement is an ordering failure");
        assertEquals(0, stream.writtenBytes().length);
        channel.close().sync();
    }

    @Test
    void anUnroutedPacketFailsClosed() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        GameLanes lanes = new GameLanes(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("unknown"))).sync();

        assertFalse(write.get().isSuccess());
        assertNotNull(seen.get());
        assertEquals(Collections.singletonList(LaneSession.CLOSE_INTERNAL_ERROR), session.aborted);
        channel.close().sync();
    }

    /** A codec the test drives: what a frame decodes to and how a packet routes are the test's. */
    static final class FakeCodec implements GameLanes.Codec {
        final Map<Object, GameLanes.Route> routes = new HashMap<>();
        final List<byte[]> decodedFrames = new ArrayList<>();
        final List<ProtocolState> decodeStates = new ArrayList<>();
        int frameRefCountDuringDecode = -1;
        ByteBuf seenFrame;
        LaneGame.Decoded next;

        @Override
        public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state) {
            byte[] bytes = new byte[frame.readableBytes()];
            frame.getBytes(frame.readerIndex(), bytes);
            decodedFrames.add(bytes);
            decodeStates.add(state);
            frameRefCountDuringDecode = frame.refCnt();
            seenFrame = frame;
            if (next != null) {
                return next;
            }
            return new LaneGame.Decoded("decoded:" + new String(bytes, StandardCharsets.US_ASCII),
                    ProtocolState.PLAY, 9, Envelope.Bundle.NONE, false);
        }

        @Override
        public void encode(Object packet, ByteBuf out) {
            out.writeBytes(("frame:" + packet).getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public GameLanes.Route routeOf(Object packet) {
            return routes.get(packet);
        }
    }

    /** The session as the test sees it: what it assigns, what it was sent, what it was told. */
    static final class FakeSession implements GameLanes.Session {
        final List<String> sent = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        final List<Integer> aborted = new ArrayList<>();
        volatile Lane assign;

        @Override
        public Lane send(ProtocolState state, int packetId, byte[] frame) {
            sent.add(state + ":" + packetId);
            frames.add(frame.clone());
            return assign;
        }

        @Override
        public void applied(Lane lane, long sequence) {
            applied.add(lane + ":" + sequence);
        }

        @Override
        public void abort(int code) {
            aborted.add(Integer.valueOf(code));
        }
    }

    /** Collects what the pipeline sees, so a test can wait for events from another thread. */
    static final class OrderCollector extends ChannelInboundHandlerAdapter {
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        final java.util.concurrent.atomic.AtomicInteger writabilityChanges =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean offLoop =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            offLoop.set(offLoop.get() || !ctx.channel().eventLoop().inEventLoop());
            synchronized (seen) {
                if (msg instanceof GameLanes.Ack) {
                    GameLanes.Ack ack = (GameLanes.Ack) msg;
                    seen.add("ack:" + ack.lane() + ":" + ack.sequence());
                } else {
                    seen.add(String.valueOf(msg));
                }
                seen.notifyAll();
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            writabilityChanges.incrementAndGet();
            synchronized (seen) {
                seen.notifyAll();
            }
        }

        void await(int count) throws InterruptedException {
            await(count, 5);
        }

        void await(int count, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            synchronized (seen) {
                while (seen.size() < count && writabilityChanges.get() == 0
                        && System.nanoTime() < deadline) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(seen, left);
                }
            }
        }
    }
}
