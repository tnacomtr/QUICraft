// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The busy timer's accounting boundaries on the real inbound path (decode per record, then deliver, then fire):
 * time is a fake clock that only the codec and the chunk handler advance, so every number is exact.
 */
class GameLanesBusyTimerTest {
    private static final long DECODE = 100;
    private static final long FIRE = 50;

    /** A decode costs {@link #DECODE} on the fake clock; the inner codec is the shared test double. */
    private static final class ClockedCodec implements GameLanes.Codec {
        private final AtomicLong time;
        private final GameLanesTest.FakeCodec inner = new GameLanesTest.FakeCodec();

        ClockedCodec(AtomicLong time) {
            this.time = time;
        }

        @Override
        public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state, LaneGame.Reservation reservation)
                throws Exception {
            time.addAndGet(DECODE);
            return inner.decode(frame, state, reservation);
        }

        @Override
        public void encode(Object packet, ByteBuf out) throws Exception {
            inner.encode(packet, out);
        }

        @Override
        public GameLanes.Route routeOf(Object packet) {
            return inner.routeOf(packet);
        }
    }

    /** Records the busy mark each chunk sees, and charges {@link #FIRE} to the fake clock per chunk. */
    private static final class ChunkHandler extends ChannelInboundHandlerAdapter {
        final AtomicLong time;
        final List<Long> marks = new ArrayList<>();
        GameLanes lanes;

        ChunkHandler(AtomicLong time) {
            this.time = time;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof String && ((String) msg).startsWith("chunk")) {
                marks.add(lanes.busyMarkForCurrentDelivery());
                time.addAndGet(FIRE);
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** One game pipeline with the busy timer on or off, and the clock the lanes read. */
    private static final class Rig {
        final AtomicLong time = new AtomicLong();
        final ChunkHandler handler = new ChunkHandler(time);
        final EmbeddedChannel channel = new EmbeddedChannel(handler);
        final GameLanes lanes;

        Rig(boolean busy) {
            // With the timer off the clock must never be read: an off path that read it would fail here.
            LongSupplier clock = busy ? time::get : () -> {
                throw new AssertionError("the clock must not be read with the busy timer off");
            };
            lanes = GameLanes.forClockTest(channel, new ClockedCodec(time), new GameLanesTest.FakeSession(),
                    ProtocolState.PLAY, null, busy, clock);
            handler.lanes = lanes;
        }

        /** Decodes one record (the receive driver's step before a delivery). */
        void decode(long sequence, boolean insideBundle) throws Exception {
            lanes.decode(Lane.WORLD, sequence, new byte[] {1}, insideBundle, (extra, total) -> { });
        }

        void deliver(long sequence, List<Object> packets, boolean bundle) {
            lanes.deliver(Lane.WORLD, sequence, packets, bundle);
        }
    }

    @Test
    void busyTimerIsOffUnlessThePropertyIsTrue() {
        // The test JVM does not set quicraft.v3.batchTimerBusy, so the shipped default applies.
        assertTrue(System.getProperty("quicraft.v3.batchTimerBusy") != null
                || !GameLanes.BUSY_TIMER_PROPERTY, "the busy batch timer must default to off (plain wall time)");
    }

    @Test
    void aChunkDeliveryMarksTheStartOfItsOwnDecode() throws Exception {
        Rig rig = new Rig(true);

        rig.decode(1, false);
        rig.deliver(1, Collections.singletonList("chunk:1"), false);

        assertEquals(Collections.singletonList(0L), rig.handler.marks,
                "the mark is the busy counter when this chunk's decode started, so the decode is inside the batch");
        assertEquals(DECODE + FIRE, rig.lanes.busyNanos(), "one decode and one fire are counted");
        assertEquals(-1L, rig.lanes.busyMarkForCurrentDelivery(), "no mark outside a fire");
    }

    @Test
    void eachDeliveryCountsItsOwnDecodeAndFire() throws Exception {
        Rig rig = new Rig(true);

        rig.decode(1, false);
        rig.deliver(1, Collections.singletonList("chunk:1"), false);
        assertEquals(DECODE + FIRE, rig.lanes.busyNanos());

        rig.decode(2, false);
        rig.deliver(2, Collections.singletonList("chunk:2"), false);
        assertEquals(2 * (DECODE + FIRE), rig.lanes.busyNanos(), "the second decode is not charged to the first");
        assertEquals(Arrays.asList(0L, DECODE + FIRE), rig.handler.marks,
                "the second chunk's mark is the start of its own decode, after the first delivery's decode and fire");
    }

    @Test
    void aBundleDeliveryMarksTheStartOfTheDecodeOfAllItsFrames() throws Exception {
        Rig rig = new Rig(true);

        rig.decode(1, true);
        rig.decode(2, true);
        rig.deliver(2, Arrays.asList("chunk:1", "chunk:2"), true);

        assertEquals(Arrays.asList(0L, 0L), rig.handler.marks,
                "both chunks of the bundle share the mark taken before the decode of its first frame");
        assertEquals(2 * DECODE + 2 * FIRE, rig.lanes.busyNanos(), "both decodes and both fires are counted");
    }

    @Test
    void theDisabledPathLeavesTheCounterAtZeroAndNoMark() throws Exception {
        Rig rig = new Rig(false);

        rig.decode(1, false);
        rig.deliver(1, Collections.singletonList("chunk:1"), false);

        assertEquals(0L, rig.lanes.busyNanos());
        assertEquals(Collections.singletonList(-1L), rig.handler.marks);
        assertEquals(-1L, rig.lanes.busyMarkForCurrentDelivery());
    }
}
