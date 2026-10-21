// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.fabric.ChunkPacing;

/**
 * The busy batch timer end to end with real parts: {@link GameLanes} marks and busy counter feed a real
 * {@link ChunkPacing}, the way {@code ChunkBatchTimerMixin} wires them. Decode runs on the test thread (the
 * receive driver) and the fire runs on a second thread (a real event loop), and the transport wait is the clock
 * advancing between deliveries with no decode and no fire. The clock is fake and only moves where the test says,
 * so the batch interval is exact.
 */
class GameLanesBusyTimerChunkPacingTest {
    private static final long DECODE = 100;
    private static final long FIRE = 50;
    private static final long WAIT = 1_000;

    /** Decodes a frame into a chunk packet; a decode costs {@link #DECODE} on the fake clock. */
    private static final class ClockedCodec implements GameLanes.Codec {
        private final AtomicLong time;

        ClockedCodec(AtomicLong time) {
            this.time = time;
        }

        @Override
        public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state, LaneGame.Reservation reservation) {
            time.addAndGet(DECODE);
            String packet = frame.toString(StandardCharsets.US_ASCII);
            return new LaneGame.Decoded(packet, ProtocolState.PLAY, 9, Envelope.Bundle.NONE, false);
        }

        @Override
        public void encode(Object packet, ByteBuf out) {
            out.writeBytes(String.valueOf(packet).getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public GameLanes.Route routeOf(Object packet) {
            return null;
        }
    }

    /** Never used on the inbound path: the test only delivers. */
    private static final class IdleSession implements GameLanes.Session {
        @Override
        public Lane outboundLane(ProtocolState state, int packetId) {
            return Lane.CONTROL;
        }

        @Override
        public Lane send(ProtocolState state, int packetId, byte[] frame) {
            return Lane.CONTROL;
        }

        @Override
        public void applied(Lane lane, long sequence) {
        }

        @Override
        public void abort(int code) {
        }
    }

    /** The game handler: the first chunk of the batch calls the pacing exactly as the mixin's HEAD injection does, and fires the fake fire cost. */
    private static final class GameChunkHandler extends ChannelInboundHandlerAdapter {
        final AtomicLong time;
        final List<Thread> firedOn = Collections.synchronizedList(new ArrayList<Thread>());
        GameLanes lanes;
        ChunkPacing pacing;

        GameChunkHandler(AtomicLong time) {
            this.time = time;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof String && ((String) msg).startsWith("chunk")) {
                firedOn.add(Thread.currentThread());
                pacing.chunk(true, () -> true, () -> { }, lanes::busyMarkForCurrentDelivery);
                time.addAndGet(FIRE);
            }
            // The sink: nothing past the game handler, so acknowledgements are consumed here too.
        }
    }

    @Test
    void theBatchIntervalIsTheDecodeAndFireOfItsDeliveriesAndExcludesTheTransportWait() throws Exception {
        AtomicLong time = new AtomicLong();
        DefaultEventLoop loop = new DefaultEventLoop();
        LocalChannel game = new LocalChannel();
        GameChunkHandler handler = new GameChunkHandler(time);
        game.pipeline().addLast(handler);
        try {
            loop.register(game).sync();
            GameLanes lanes = GameLanes.forClockTest(game, new ClockedCodec(time), new IdleSession(),
                    ProtocolState.PLAY, null, true, time::get);
            ChunkPacing pacing = new ChunkPacing(false, true);
            handler.lanes = lanes;
            handler.pacing = pacing;

            // chunk_batch_start is handled on the game side before the batch's chunks arrive.
            pacing.batchStarted();
            long batchStart = time.get();
            Thread receiver = Thread.currentThread();

            // Three chunk deliveries, each with a transport wait before it (clock only, no decode, no fire).
            for (int i = 1; i <= 3; i++) {
                time.addAndGet(WAIT);
                lanes.decode(Lane.WORLD, i, ("chunk:" + i).getBytes(StandardCharsets.US_ASCII), false,
                        (extra, total) -> { });
                lanes.deliver(Lane.WORLD, i, Collections.<Object>singletonList("chunk:" + i), false);
                onLoop(loop, () -> null);
            }

            long busy = onLoop(loop, () -> pacing.batchFinished(lanes::busyNanos));
            long wall = time.get() - batchStart;

            assertEquals(3 * (DECODE + FIRE), busy,
                    "the batch interval is the decode and fire of all three deliveries, the first chunk's decode included");
            assertEquals(3 * WAIT, wall - busy, "the transport wait between deliveries is the part the timer excludes");
            assertEquals(3, handler.firedOn.size());
            for (Thread fired : handler.firedOn) {
                assertNotSame(receiver, fired, "deliveries are decoded on the receiver and fired on the game loop");
            }
            assertEquals(-1L, lanes.busyMarkForCurrentDelivery(), "no mark outside a fire");
        } finally {
            game.close().sync();
            loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    private static <T> T onLoop(DefaultEventLoop loop, Callable<T> task) throws Exception {
        return loop.submit(task).get(10, TimeUnit.SECONDS);
    }
}
