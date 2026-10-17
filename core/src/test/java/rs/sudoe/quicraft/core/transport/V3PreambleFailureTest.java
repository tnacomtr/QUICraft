// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamChannelConfig;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.MultistreamProtocolException;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * A malformed or invalid /3 preamble ends negotiation for good (13.2): the bytes that arrived with
 * it are never delivered to the game, and the game still sees the close. Runs without the native:
 * the connection and stream are proxies over Netty's embedded channels, and the server's reply is
 * fed straight into {@link V3ConnectionImpl#onData}.
 */
class V3PreambleFailureTest {
    private static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":4242,\"default\":\"control\",\"routes\":[]}");

    /** A reply naming 2..3 to a client offer of 1..6 is not one shard count (13.2): 0x1. */
    @Test
    void aClientReplyNamingTwoShardCountsDeliversNoCoalescedGameBytes() throws Exception {
        Fakes fakes = new Fakes();
        V3ConnectionImpl impl = V3ConnectionImpl.client(fakes.connection, fakes.control,
                new NettyQuicByteStream(fakes.control), null, null, null, null, TABLE, true);
        impl.start();
        Game game = new Game();
        attachGame(impl, game);

        // The reply (length 4, version 2, no token, range 2..3) with game bytes in the same read.
        byte[] reply = {0x04, 0x02, 0x00, 0x02, 0x03};
        ByteBuffer coalesced = ByteBuffer.allocate(reply.length + 3);
        coalesced.put(reply).put(new byte[] {5, 6, 7}).flip();
        impl.onData(coalesced);
        // Later reads from the stream after the failure are ignored too.
        impl.onData(ByteBuffer.wrap(new byte[] {8, 9}));
        fakes.connectionChannel.runPendingTasks();
        // The connection then ends (the peer or the close just issued).
        impl.onClosed(null);

        assertEquals(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, fakes.closeCode(),
                "the invalid reply closes the connection with 0x1");
        assertEquals(0, game.delivered.size(), "no game bytes after a failed negotiation");
        assertEquals(1, game.closes.size(), "the game still sees the close, exactly once");
    }

    /** With a 0-RTT flight in the air the gate stays shut, but a failed negotiation still ends it. */
    @Test
    void anInvalidReplyAfterAZeroRttFlightStillDeliversTheCloseOnce() throws Exception {
        Fakes fakes = new Fakes();
        V3ConnectionImpl impl = V3ConnectionImpl.client(fakes.connection, fakes.control,
                new NettyQuicByteStream(fakes.control), null, null, null, new byte[] {1, 2, 3}, TABLE, true);
        impl.start();
        Game game = new Game();
        attachGame(impl, game);

        byte[] reply = {0x04, 0x02, 0x00, 0x02, 0x03};
        ByteBuffer coalesced = ByteBuffer.allocate(reply.length + 3);
        coalesced.put(reply).put(new byte[] {5, 6, 7}).flip();
        impl.onData(coalesced);
        fakes.connectionChannel.runPendingTasks();
        impl.onClosed(null);
        impl.onClosed(null);

        assertEquals(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, fakes.closeCode(),
                "the invalid reply closes the connection with 0x1");
        assertEquals(0, game.delivered.size(), "no game bytes after a failed negotiation");
        assertEquals(1, game.closes.size(), "the game sees the close exactly once, 0-RTT or not");
    }

    // --- Proxies and recorders.

    private static void attachGame(V3ConnectionImpl impl, Game game) throws Exception {
        Field field = V3ConnectionImpl.class.getDeclaredField("game");
        field.setAccessible(true);
        field.set(impl, game);
    }

    /** The QUIC connection and the stream 0, as proxies over embedded channels. */
    static final class Fakes {
        final EmbeddedChannel connectionChannel = new EmbeddedChannel();
        final EmbeddedChannel streamChannel = new EmbeddedChannel();
        final List<Object[]> closes = new ArrayList<Object[]>();
        /** Accepts setAutoRead and friends; their results are not used by the connection. */
        final QuicStreamChannelConfig streamConfig = proxy(QuicStreamChannelConfig.class, (method, args) -> null);
        final QuicChannel connection = proxy(QuicChannel.class, (method, args) -> {
            if (method.getName().equals("pipeline")) {
                return connectionChannel.pipeline();
            }
            if (method.getName().equals("eventLoop")) {
                return connectionChannel.eventLoop();
            }
            if (method.getName().equals("close")) {
                closes.add(args);
                return null;
            }
            return null;
        });
        final QuicStreamChannel control = proxy(QuicStreamChannel.class, (method, args) -> {
            if (method.getName().equals("pipeline")) {
                return streamChannel.pipeline();
            }
            if (method.getName().equals("config")) {
                return streamConfig;
            }
            if (method.getName().equals("eventLoop")) {
                return streamChannel.eventLoop();
            }
            if (method.getName().equals("streamId")) {
                return 0L;
            }
            return null;
        });

        /** The error code the connection was closed with (the application close, arg 1). */
        int closeCode() {
            assertEquals(1, closes.size(), "exactly one close was issued");
            return (Integer) closes.get(0)[1];
        }

        private static <T> T proxy(Class<T> type, Dispatch dispatch) {
            return type.cast(Proxy.newProxyInstance(V3PreambleFailureTest.class.getClassLoader(),
                    new Class<?>[] {type}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                    : method.getName().equals("equals") ? proxy == args[0]
                                    : "proxy";
                        }
                        Object value = dispatch.call(method, args);
                        return value != null || !method.getReturnType().isPrimitive() ? value
                                : defaultOf(method.getReturnType());
                    }));
        }

        private static Object defaultOf(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == int.class) {
                return 0;
            }
            return (byte) 0;
        }
    }

    /** Routes a proxied call; unhandled calls return null (defaulted for primitives). */
    interface Dispatch {
        Object call(Method method, Object[] args);
    }

    /** The game's side of stream 0: the bytes it was given and the closes it was told of. */
    static final class Game implements QuicByteStream.Listener {
        final ByteArrayOutputStream delivered = new ByteArrayOutputStream();
        final List<Throwable> closes = new ArrayList<Throwable>();

        @Override
        public void onData(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            delivered.write(bytes, 0, bytes.length);
        }

        @Override
        public void onWritabilityChanged(boolean writable) { }

        @Override
        public void onClosed(Throwable cause) {
            closes.add(cause);
        }
    }
}
