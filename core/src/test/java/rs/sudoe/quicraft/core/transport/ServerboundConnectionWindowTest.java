// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;

/**
 * 13.13 and 13.3: the server advertises a serverbound connection window of three pinned 8 KiB stream
 * windows (24 KiB), the most quiche can hold unread on the server. Unread data holds connection credit
 * until the server reads it, so a window of 8 KiB would let one withheld stream block the other two.
 * This test runs over the real quiche loopback with no QUICraft stream logic in between.
 */
class ServerboundConnectionWindowTest {
    private static final int STREAMS = 3;
    private static final int CHUNK = 8 * 1024;
    private static final long WAIT_SECONDS = 5;

    @Test
    void withReadsWithheldOnOneStreamTheOtherTwoPinnedStreamsStillAcceptAndDeliverEightKiBEach() throws Exception {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows(), "/3 needs QUICraft's patched native (13.13)");
        BlockingQueue<QuicStreamChannel> serverStreams = new LinkedBlockingQueue<>();
        LaneLoopback.Options options = new LaneLoopback.Options();
        // The three pinned streams are opened by the client; raise the server's stream limit so the
        // window, not the stream count, is what the test exercises.
        options.serverMaxStreamsBidi = STREAMS;
        options.serverSink = serverStreams::add;
        try (LaneLoopback net = LaneLoopback.open(options)) {
            QuicStreamChannel[] client = new QuicStreamChannel[STREAMS];
            for (int i = 0; i < STREAMS; i++) {
                client[i] = net.clientStream();
            }
            // The server never reads (every serverbound stream keeps autoRead off). A write completes
            // only once quiche has accepted all of its bytes, which needs connection credit from the
            // client's view of the server's initial_max_data. With 24 KiB all three complete; with
            // 8 KiB, the first takes the whole window and the second never completes.
            byte[] chunk = new byte[CHUNK];
            ChannelFuture[] writes = new ChannelFuture[STREAMS];
            for (int i = 0; i < STREAMS; i++) {
                writes[i] = client[i].writeAndFlush(Unpooled.wrappedBuffer(chunk));
            }
            for (int i = 0; i < STREAMS; i++) {
                assertTrue(writes[i].await(WAIT_SECONDS, TimeUnit.SECONDS) && writes[i].isSuccess(),
                        "13.13: the 8 KiB write on pinned stream " + client[i].streamId() + " (index " + i
                                + ") was not accepted while the server withheld reads on stream "
                                + client[0].streamId() + ": " + writes[i].cause());
            }

            // The server now reads the two streams it did not withhold; the withheld one is never read.
            Map<Long, QuicStreamChannel> byId = new HashMap<>();
            for (int i = 0; i < STREAMS; i++) {
                QuicStreamChannel s = serverStreams.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(s, "the server saw serverbound stream " + i + " of " + STREAMS);
                byId.put(s.streamId(), s);
            }
            CompletableFuture<Integer> first = collect(byId.get(client[1].streamId()));
            CompletableFuture<Integer> second = collect(byId.get(client[2].streamId()));
            assertEquals(CHUNK, await(first, client[1].streamId()), "13.13: stream " + client[1].streamId()
                    + " delivered its 8 KiB through the 24 KiB connection window");
            assertEquals(CHUNK, await(second, client[2].streamId()), "13.13: stream " + client[2].streamId()
                    + " delivered its 8 KiB through the 24 KiB connection window");
        }
    }

    private static CompletableFuture<Integer> collect(QuicStreamChannel stream) {
        assertNotNull(stream, "the server has the stream to read");
        CompletableFuture<Integer> done = new CompletableFuture<>();
        stream.pipeline().addLast(new Collector(CHUNK, done));
        stream.config().setAutoRead(true);
        return done;
    }

    private static int await(CompletableFuture<Integer> bytes, long streamId) throws Exception {
        try {
            return bytes.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("13.13: stream " + streamId + " did not deliver its 8 KiB within " + WAIT_SECONDS
                    + " s while another pinned stream was withheld", e);
            return -1;
        }
    }

    /** Counts bytes on the event loop and completes once {@code wanted} have arrived. */
    private static final class Collector extends ChannelInboundHandlerAdapter {
        private final int wanted;
        private final CompletableFuture<Integer> done;
        private int received;

        Collector(int wanted, CompletableFuture<Integer> done) {
            this.wanted = wanted;
            this.done = done;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                received += buf.readableBytes();
            } finally {
                buf.release();
            }
            if (received >= wanted) {
                done.complete(received);
            }
        }
    }
}
