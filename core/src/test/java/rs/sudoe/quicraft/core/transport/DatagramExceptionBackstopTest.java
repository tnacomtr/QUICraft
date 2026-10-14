// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The last handler on every socket of a multi-socket listener
 * ({@link QuicServer.DatagramExceptionBackstop}): a handler that throws for a datagram must not reach
 * Netty's tail, which would log a stack trace for that datagram, and the listener must log at most
 * one line a minute however many datagrams make a handler throw.
 */
class DatagramExceptionBackstopTest {
    private static final int CALLS = 3;

    @Test
    void aThrowingHandlerIsSwallowedAndLoggedOnceAMinute() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Logger quicraft = Logger.getLogger("QUICraft");
        Level previousLevel = quicraft.getLevel();
        Captured logged = new Captured();
        // FINE is off by default, so the test has to ask for it, exactly as a debugging listener does.
        quicraft.setLevel(Level.FINE);
        quicraft.addHandler(logged);
        Tail tail = new Tail();
        try {
            Channel socket = channel(loops, new Thrower(),
                    new QuicServer.DatagramExceptionBackstop(new AtomicLong(), 3), tail);

            // On the loop, exactly as a datagram arrives: Netty delivers an exceptionCaught raised by
            // a handler on the loop synchronously, so what the backstop did is visible right after.
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            socket.eventLoop().execute(() -> {
                try {
                    for (int call = 0; call < CALLS; call++) {
                        socket.pipeline().fireChannelRead(new Object());
                    }
                } finally {
                    done.countDown();
                }
            });
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "the loop ran the reads");

            assertEquals(0, tail.causes.size(),
                    "the backstop must swallow the exception before the tail sees it: " + tail.causes);
            assertEquals(1, logged.records.size(),
                    "the listener logs one line for the whole minute, not one per datagram: " + logged.records);
            assertEquals(Level.FINE, logged.records.get(0).getLevel(),
                    "a swallowed datagram exception is a FINE line: " + logged.records.get(0).getMessage());
            assertEquals("QUICraft", logged.records.get(0).getLoggerName(), "logged by the QUICraft logger");
            assertTrue(logged.records.get(0).getThrown() instanceof IllegalStateException,
                    "the cause is kept in the record, so a debugging listener can see it");
        } finally {
            quicraft.removeHandler(logged);
            quicraft.setLevel(previousLevel);
            shutdown(loops);
        }
    }

    /** Throws for every datagram, as Netty's dispatcher does for one it cannot parse. */
    private static final class Thrower extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            throw new IllegalStateException("cannot parse this datagram");
        }
    }

    /** Stands in for Netty's pipeline tail: records every exception that reached it. */
    private static final class Tail extends ChannelInboundHandlerAdapter {
        final List<Throwable> causes = new CopyOnWriteArrayList<>();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            causes.add(cause);
        }
    }

    /** Collects what one logger published. */
    private static final class Captured extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** A registered datagram channel on {@code loops}, with no socket bound to it. */
    private static Channel channel(EventLoopGroup loops, io.netty.channel.ChannelHandler... handlers)
            throws Exception {
        Channel channel = new NioDatagramChannel();
        channel.pipeline().addLast(handlers);
        loops.register(channel).sync();
        return channel;
    }

    private static void shutdown(EventLoopGroup loops) {
        loops.shutdownGracefully(0, 5, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
    }
}
