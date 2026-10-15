// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Structural encode/decode validation of the 103-byte payload, docs/protocol.md §12.8. */
class BindingMessageTest {
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);

    @Test
    void roundTripsEveryKind() throws Exception {
        for (BindingKind kind : BindingKind.values()) {
            BindingMessage message = BindingMessage.of(kind, BindingTestData.MODE_ONLINE,
                    BindingTestData.PROTOCOL_NUMBER, F, R, new byte[32]);
            BindingMessage decoded = BindingMessage.decode(message.encode());
            assertEquals(kind, decoded.kind());
            assertEquals(BindingTestData.MODE_ONLINE, decoded.mode());
            assertEquals(BindingTestData.PROTOCOL_NUMBER, decoded.protocolNumber());
            assertArrayEquals(F, decoded.fingerprint());
            assertArrayEquals(R, decoded.routing());
        }
    }

    @Test
    void layoutIsVersionKindModeProtocolThenDigests() throws Exception {
        byte[] payload = BindingMessage.offer(1, 775, F, R).encode();
        assertEquals(103, payload.length);
        assertEquals(2, payload[0] & 0xFF, "version");
        assertEquals(0, payload[1] & 0xFF, "kind OFFER");
        assertEquals(1, payload[2] & 0xFF, "mode online");
        assertArrayEquals(new byte[]{0x00, 0x00, 0x03, 0x07}, Arrays.copyOfRange(payload, 3, 7), "u32 protocol");
        assertArrayEquals(F, Arrays.copyOfRange(payload, 7, 39), "F");
        assertArrayEquals(R, Arrays.copyOfRange(payload, 39, 71), "R");
        assertArrayEquals(new byte[32], Arrays.copyOfRange(payload, 71, 103), "OFFER tag is zeros");
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] payload = Arrays.copyOf(BindingMessage.offer(1, 775, F, R).encode(), 102);
        assertEquals(BindingFailure.BAD_LENGTH, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(payload)).reason());
    }

    @Test
    void rejectsTrailingByte() {
        byte[] payload = Arrays.copyOf(BindingMessage.offer(1, 775, F, R).encode(), 104);
        assertEquals(BindingFailure.BAD_LENGTH, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(payload)).reason());
    }

    @Test
    void rejectsNullAndEmpty() {
        assertEquals(BindingFailure.BAD_LENGTH, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(null)).reason());
        assertEquals(BindingFailure.BAD_LENGTH, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(new byte[0])).reason());
    }

    @Test
    void rejectsUnknownVersion() {
        byte[] payload = BindingMessage.offer(1, 775, F, R).encode();
        payload[0] = 3;
        assertEquals(BindingFailure.BAD_VERSION, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(payload)).reason());
    }

    @Test
    void rejectsUnknownKind() {
        byte[] payload = BindingMessage.offer(1, 775, F, R).encode();
        payload[1] = 5;
        assertEquals(BindingFailure.UNKNOWN_KIND, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(payload)).reason());
    }

    @Test
    void rejectsOutOfRangeModeByte() {
        byte[] payload = BindingMessage.offer(1, 775, F, R).encode();
        payload[2] = 2;
        assertEquals(BindingFailure.WRONG_MODE, assertThrows(BindingFormatException.class,
                () -> BindingMessage.decode(payload)).reason());
    }

    @Test
    void constructorRejectsWrongSizesAndModes() {
        assertThrows(IllegalArgumentException.class,
                () -> BindingMessage.of(BindingKind.OFFER, 2, 775, F, R, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> BindingMessage.of(BindingKind.OFFER, 1, 775, new byte[31], R, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> BindingMessage.of(BindingKind.OFFER, 1, 775, F, new byte[31], new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> BindingMessage.of(BindingKind.OFFER, 1, 775, F, R, new byte[31]));
    }

    /**
     * Regression for §12.8: {@code protocol_number} is an unsigned u32, so a negative value is out
     * of range and MUST be rejected before encoding rather than wrapped into four bytes that mean
     * another protocol. Every message-building entry point goes through {@link BindingMessage#of}.
     */
    @Test
    void rejectsANegativeProtocolNumberInsteadOfWrappingIt() {
        for (int protocolNumber : new int[] {-1, Integer.MIN_VALUE, -775}) {
            assertThrows(IllegalArgumentException.class, () -> BindingMessage.of(BindingKind.OFFER,
                    BindingTestData.MODE_ONLINE, protocolNumber, F, R, new byte[32]),
                    "protocol number " + protocolNumber + " must be rejected");
            assertThrows(IllegalArgumentException.class, () -> BindingMessage.offer(
                    BindingTestData.MODE_ONLINE, protocolNumber, F, R),
                    "protocol number " + protocolNumber + " must be rejected");
        }
        // The largest representable protocol number is still legal: it is the u32 range's upper
        // half, and only values outside 0..4294967295 are out of range.
        assertEquals(Integer.MAX_VALUE, BindingMessage.offer(1, Integer.MAX_VALUE, F, R)
                .protocolNumber());
        assertEquals(0, BindingMessage.offer(1, 0, F, R).protocolNumber());
    }

    @Test
    void toStringRevealsNoTagOrDigestBytes() {
        byte[] tag = BindingTestData.hex(BindingTestData.E_HEX);
        BindingMessage message = BindingMessage.of(BindingKind.CLIENT_PROOF, 1, 775, F, R, tag);
        String text = message.toString();
        assertEquals(-1, text.indexOf(BindingTestData.hex(F)));
        assertEquals(-1, text.indexOf(BindingTestData.hex(tag)));
    }
}