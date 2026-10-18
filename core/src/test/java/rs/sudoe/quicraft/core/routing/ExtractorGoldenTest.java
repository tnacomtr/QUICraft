// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;
import rs.sudoe.quicraft.core.routing.extract.FootprintRegistry;
import static org.junit.jupiter.api.Assertions.*;

class ExtractorGoldenTest {
    @Test void independentPythonGoldenVectorsAndEveryTruncationAndPrefix() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(
                "oracle-vectors.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\\|", -1);
                assertEquals(4, fields.length, line);
                String identifier = fields[0];
                byte[] packet = hex(fields[1]);
                int consumed = Integer.parseInt(fields[3]);
                assertTrue(consumed > 0 && consumed <= packet.length, line);
                // The sibling identifier names the same layout under the other family
                // (synthetic primitive.* vs reviewed mc775.*) when a captured packet carries it;
                // the same bytes must decode alike. varint_xz has no production sibling.
                ExtractorRegistry.Decoder decoder = ExtractorRegistry.decoder(identifier);
                boolean hasSibling = identifier.startsWith("primitive.")
                        && ExtractorRegistry.ids().contains(siblingId(identifier));
                ExtractorRegistry.Decoder sibling = hasSibling ? ExtractorRegistry.decoder(siblingId(identifier)) : decoder;
                assertResult(fields[2], decoder.decode(packet), line);
                assertResult(fields[2], sibling.decode(packet), line);
                assertThrows(UnsupportedOperationException.class, () -> decoder.decode(packet).positions.clear());
                // A packet that cannot supply the layout's fields is malformed (13.7).
                for (int i = 0; i < consumed; i++) {
                    byte[] shortPacket = Arrays.copyOf(packet, i);
                    assertThrows(IllegalArgumentException.class, () -> decoder.decode(shortPacket), line + " cut to " + i);
                    assertThrows(IllegalArgumentException.class, () -> sibling.decode(shortPacket), line + " cut to " + i);
                }
                // Bytes after those fields belong to the rest of the real packet: a prefix that
                // still carries every field decodes to the same result, and so do extra bytes (13.7).
                for (int i = consumed; i <= packet.length; i++) {
                    byte[] prefix = Arrays.copyOf(packet, i);
                    assertResult(fields[2], decoder.decode(prefix), line + " prefix " + i);
                }
                assertResult(fields[2], decoder.decode(Arrays.copyOf(packet, packet.length + 1)), line);
            }
        }
        assertEquals(-1, ExtractorRegistry.decoder("test.chunk_xz.v1").decode(hex("01ffffffffffffffef")).positions.get(0).chunkX);
        assertEquals(-1, ExtractorRegistry.decoder("mc775.chunk_xz.v1").decode(hex("01ffffffffffffffef")).positions.get(0).chunkX);
    }

    @Test void malformedIdsCoordinatesAndListBounds() {
        String[] families = {"primitive.", "mc775."};
        for (String family : families) {
            for (String bad : new String[] {"80", "808080808000", "ffffffff1f", "8100", "ffffffff0f"})
                assertThrows(IllegalArgumentException.class,
                        () -> ExtractorRegistry.decoder(family + "join.v1").decode(hex(bad)));
            for (String bad : new String[] {"01ffffffff1f00", "01800000", "0180808080800000"})
                assertThrows(IllegalArgumentException.class,
                        () -> ExtractorRegistry.decoder(family + "varint_xz.v1").decode(hex(bad)));
            // A chunk list entry is a packed ChunkPos, a VarInt payload length and that payload.
            for (String bad : new String[] {"018108", "018000", "010100", "01010000000000000000",
                    "0101000000000000000080", "010100000000000000000501"})
                assertThrows(IllegalArgumentException.class,
                        () -> ExtractorRegistry.decoder(family + "chunk_list.v1").decode(hex(bad)));
        }
        // A zero-length payload followed by bytes this layout does not read is a legal prefix.
        ExtractorRegistry.Result trailing = ExtractorRegistry.decoder("mc775.chunk_list.v1")
                .decode(hex("01010000000000000000002a"));
        assertEquals(1, trailing.positions.size());
        assertEquals(0, trailing.positions.get(0).chunkX);
        // The count is refused before anything is read for it, so an impossible count is rejected
        // for the count, not for a body that was never going to be large enough.
        for (int count : new int[] {ExtractorRegistry.MAX_LIST_ENTRIES + 1, Integer.MAX_VALUE}) {
            byte[] declared = concat(new byte[] {1}, varint(count));
            for (String family : families) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> ExtractorRegistry.decoder(family + "chunk_list.v1").decode(declared));
                assertTrue(e.getMessage().contains("chunk list count"), e.getMessage());
            }
        }
        // Lists larger than the retired 1024-entry primitive bound are legal when their entries fit.
        int count = 4096;
        byte[] list = new byte[1 + 2 + count * 9];
        list[0] = 1;
        list[1] = (byte) 0x80;
        list[2] = 0x20;
        assertEquals(count, ExtractorRegistry.decoder("mc775.chunk_list.v1").decode(list).positions.size());
        assertEquals(count, ExtractorRegistry.decoder("primitive.chunk_list.v1").decode(list).positions.size());
        assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("unknown.v1"));
        assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("mc775.unknown.v1"));
    }

    @Test void footprintBoundaryVectorsIncludeCopiesPrefixAndOverflow() {
        String id = "primitive.raw_copies.v1";
        assertEquals(160, FootprintRegistry.bound(id, 0, 0, false));
        assertEquals(5619, FootprintRegistry.bound(id, 2000, 1459, false));
        assertEquals(5620, FootprintRegistry.bound(id, 2000, 1460, false));
        assertEquals(160L + 3L * Integer.MAX_VALUE, FootprintRegistry.bound(id, Integer.MAX_VALUE, Integer.MAX_VALUE, false));
        assertThrows(IllegalArgumentException.class, () -> FootprintRegistry.bound(id, 10, 100, true));
        assertThrows(IllegalArgumentException.class, () -> FootprintRegistry.bound(id, -1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> FootprintRegistry.bound(id, 0, -1, false));
        assertThrows(IllegalArgumentException.class, () -> FootprintRegistry.require("unknown.v1"));
    }

    private static void assertResult(String expected, ExtractorRegistry.Result result, String context) {
        if (expected.equals("JOIN")) {
            assertEquals(ExtractorRegistry.Kind.JOIN, result.kind, context);
            return;
        }
        StringBuilder actual = new StringBuilder();
        for (Extractor.Position p : result.positions) {
            if (actual.length() != 0) actual.append(';');
            actual.append(p.chunkX).append(',').append(p.chunkZ);
        }
        assertEquals(expected, actual.toString(), context);
    }

    private static String siblingId(String identifier) {
        if (identifier.startsWith("primitive.")) return "mc775." + identifier.substring("primitive.".length());
        if (identifier.startsWith("mc775.")) return "primitive." + identifier.substring("mc775.".length());
        throw new IllegalArgumentException(identifier);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    private static byte[] varint(int value) {
        byte[] out = new byte[5];
        int length = 0;
        while ((value & ~127) != 0) {
            out[length++] = (byte) ((value & 127) | 128);
            value >>>= 7;
        }
        out[length++] = (byte) value;
        return Arrays.copyOf(out, length);
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
