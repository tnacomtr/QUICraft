// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.fabric.net.FrameCodec;
import rs.sudoe.quicraft.fabric.net.PacketLaneCodec;

/**
 * docs/protocol.md 13.8: the server's send path hands the session the packet's uncompressed id and
 * body, so a CHUNK route can extract its position. Without them the session fails the route closed.
 * A recording session stands in for the activated N+2 session; the framing is the real codec's.
 */
class SubmitUncompressedTest {
    private static final int PACKET_ID = 0x27;

    @Test
    void sessionReceivesTheUncompressedPacketNextToItsFrame() throws Exception {
        byte[] packet = packet(PACKET_ID, 3, 4);
        List<Object[]> calls = new ArrayList<>();
        MultistreamSession session = recording(calls);
        PacketLaneCodec codec = new PacketLaneCodec(new EmbeddedChannel().pipeline());

        V3Join.submitUncompressed(session, codec, ProtocolState.PLAY, packet);

        assertEquals(1, calls.size(), "one submission");
        Object[] call = calls.get(0);
        assertEquals("send", call[0]);
        assertEquals(ProtocolState.PLAY, call[1]);
        assertEquals(PACKET_ID, call[2]);
        assertArrayEquals(FrameCodec.encode(packet, -1), (byte[]) call[3], "the framed record");
        assertNotNull(call[4], "the uncompressed bytes must be supplied");
        assertArrayEquals(packet, (byte[]) call[4], "the uncompressed packet id and body, unchanged");
    }

    @Test
    void uncompressedBytesCarryNoLengthPrefix() throws Exception {
        byte[] packet = packet(PACKET_ID, 9);
        List<Object[]> calls = new ArrayList<>();
        V3Join.submitUncompressed(recording(calls), new PacketLaneCodec(new EmbeddedChannel().pipeline()),
                ProtocolState.PLAY, packet);
        byte[] uncompressed = (byte[]) calls.get(0)[4];
        assertEquals(packet.length, uncompressed.length, "no VarInt length prefix in the uncompressed bytes");
    }

    /** VarInt-free packet: the id as one byte (PACKET_ID fits) followed by the body. */
    private static byte[] packet(int id, int... body) {
        byte[] out = new byte[1 + body.length];
        out[0] = (byte) id;
        for (int i = 0; i < body.length; i++) {
            out[i + 1] = (byte) body[i];
        }
        return out;
    }

    private static MultistreamSession recording(List<Object[]> calls) {
        return (MultistreamSession) Proxy.newProxyInstance(MultistreamSession.class.getClassLoader(),
                new Class<?>[] {MultistreamSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("send")) {
                        // Row: "send", then the call's args (state, id, frame, uncompressed, after[]).
                        Object[] row = new Object[args.length + 1];
                        row[0] = "send";
                        System.arraycopy(args, 0, row, 1, args.length);
                        calls.add(row);
                        return null;
                    }
                    return defaultValue(method);
                });
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        return null;
    }
}
