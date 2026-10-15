// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * Wire tests for {@link AuxPreamble} (docs/protocol.md 13.3).
 *
 * <p>Golden vectors, hand-derived from {@code u8(version=2) || u8(type)}:
 * REALTIME {@code 0x02 0x01}, WORLD {@code 0x02 0x02}.
 */
class AuxPreambleTest {
    private static final byte[] REALTIME = {0x02, 0x01};
    private static final byte[] WORLD = {0x02, 0x02};

    @Test
    void encodesGolden() {
        assertArrayEquals(REALTIME, AuxPreamble.encode(AuxPreamble.TYPE_REALTIME));
        assertArrayEquals(WORLD, AuxPreamble.encode(AuxPreamble.TYPE_WORLD));
    }

    @Test
    void encodeRejectsOtherTypes() {
        assertThrows(IllegalArgumentException.class, () -> AuxPreamble.encode(0));
        assertThrows(IllegalArgumentException.class, () -> AuxPreamble.encode(3));
    }

    @Test
    void parsesGolden() throws Exception {
        AuxPreamble.Parser realtime = new AuxPreamble.Parser();
        assertTrue(realtime.feed(ByteBuffer.wrap(REALTIME)));
        assertEquals(AuxPreamble.TYPE_REALTIME, realtime.type());
        assertEquals(2, realtime.consumed());

        AuxPreamble.Parser world = new AuxPreamble.Parser();
        assertTrue(world.feed(ByteBuffer.wrap(WORLD)));
        assertEquals(AuxPreamble.TYPE_WORLD, world.type());
    }

    @Test
    void parsesSplitAtEveryBoundary() throws Exception {
        for (byte[] golden : new byte[][] {REALTIME, WORLD}) {
            for (int split = 0; split <= golden.length; split++) {
                AuxPreamble.Parser parser = new AuxPreamble.Parser();
                assertEquals(split == 2, parser.feed(ByteBuffer.wrap(golden, 0, split)),
                        "split " + split);
                assertTrue(parser.feed(ByteBuffer.wrap(golden, split, golden.length - split)));
                assertEquals(2, parser.consumed());
            }
        }
    }

    @Test
    void rejectsBadVersion() {
        assertClose(new byte[] {0x01, 0x01});
        assertClose(new byte[] {0x03, 0x01});
    }

    @Test
    void rejectsBadType() {
        assertClose(new byte[] {0x02, 0x00});
        assertClose(new byte[] {0x02, 0x03});
        assertClose(new byte[] {0x02, (byte) 0xff});
    }

    private static void assertClose(byte[] bytes) {
        AuxPreamble.Parser parser = new AuxPreamble.Parser();
        MultistreamProtocolException e =
                assertThrows(MultistreamProtocolException.class, () -> parser.feed(ByteBuffer.wrap(bytes)));
        assertEquals(AuxPreamble.CLOSE_CODE, e.closeCode());
        assertEquals(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, e.closeCode());
    }
}
