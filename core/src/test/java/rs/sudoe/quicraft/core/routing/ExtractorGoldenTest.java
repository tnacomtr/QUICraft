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
    @Test void independentPythonGoldenVectorsAndEveryTruncationAndTrailingByte() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(
                "extractor-vectors.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\|", -1);
                ExtractorRegistry.Decoder decoder = ExtractorRegistry.decoder(fields[0]);
                byte[] packet = hex(fields[1]);
                ExtractorRegistry.Result result = decoder.decode(packet);
                if (fields[2].equals("JOIN")) assertEquals(ExtractorRegistry.Kind.JOIN, result.kind);
                else {
                    StringBuilder actual = new StringBuilder();
                    for (Extractor.Position p : result.positions) {
                        if (actual.length() != 0) actual.append(';');
                        actual.append(p.chunkX).append(',').append(p.chunkZ);
                    }
                    assertEquals(fields[2], actual.toString(), line);
                }
                assertThrows(UnsupportedOperationException.class, () -> result.positions.clear());
                for (int i = 0; i < packet.length; i++) {
                    byte[] shortPacket = Arrays.copyOf(packet, i);
                    assertThrows(IllegalArgumentException.class, () -> decoder.decode(shortPacket));
                }
                assertThrows(IllegalArgumentException.class, () -> decoder.decode(Arrays.copyOf(packet, packet.length + 1)));
            }
        }
        assertEquals(-1, ExtractorRegistry.decoder("test.chunk_xz.v1").decode(hex("01ffffffffffffffef")).positions.get(0).chunkX);
    }

    @Test void malformedIdsCoordinatesAndBoundedList() {
        for (String bad : new String[] {"80", "808080808000", "ffffffff1f", "8100", "ffffffff0f"})
            assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("primitive.join.v1").decode(hex(bad)));
        for (String bad : new String[] {"01ffffffff1f00", "01800000", "0180808080800000"})
            assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("primitive.varint_xz.v1").decode(hex(bad)));
        for (String bad : new String[] {"018108", "01ffffffff0f", "010100", "018000"})
            assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("primitive.chunk_list.v1").decode(hex(bad)));
        byte[] max = new byte[3 + 1024 * 8];
        max[0] = 1; max[1] = (byte) 0x80; max[2] = 8;
        assertEquals(1024, ExtractorRegistry.decoder("primitive.chunk_list.v1").decode(max).positions.size());
        assertThrows(IllegalArgumentException.class, () -> ExtractorRegistry.decoder("unknown.v1"));
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

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
