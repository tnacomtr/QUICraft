// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Black-box tests for the strict lowercase hex helpers and SHA-256 key identifiers. */
class HexTest {
    /** Marker that must never appear in a rejection message. */
    private static final String MARKER = "MARKER42SECRET";

    // RFC 8032 test-vector public keys and independently computed kids (SHA-256, first 8 bytes).
    private static final String PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String KID_1 = "21fe31dfa154a261";
    private static final String PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String KID_2 = "39f713d0a644253f";
    private static final String PUB_3 =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String KID_3 = "dac073e0123bdea5";

    /** Published SHA-256 vector for "abc". */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    /** Inclusive upper bound for a requested byte length inside {@link Hex}. */
    private static final int MAX_BYTE_LENGTH = Integer.MAX_VALUE / 4;

    private static void assertNoEcho(Executable executable) {
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, executable);
        assertNotNull(e.getMessage(), "rejection message must not be null");
        assertFalse(e.getMessage().contains(MARKER),
                "error echoed the supplied input: " + e.getMessage());
    }

    // ---- SHA-256 kid derivation --------------------------------------------------------------

    @Test
    void rfc8032PublicKeysDeriveIndependentlyKnownKids() throws Exception {
        assertEquals(KID_1, Hex.kid(PUB_1));
        assertEquals(KID_2, Hex.kid(PUB_2));
        assertEquals(KID_3, Hex.kid(PUB_3));
        assertEquals(16, Hex.kid(PUB_1).length());
    }

    @Test
    void sha256MatchesPublishedAbcVector() {
        assertEquals(SHA256_ABC, Hex.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(64, Hex.sha256(new byte[0]).length());
    }

    // ---- encode / decode ---------------------------------------------------------------------

    @Test
    void encodeDecodeFullByteRangeRoundTrips() throws Exception {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        String hex = Hex.encode(all);
        assertEquals(512, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"), "encode must be lowercase hex only");
        assertArrayEquals(all, Hex.decode(hex, 256));

        byte[] sample = {0x00, 0x0f, 0x10, (byte) 0xab};
        assertArrayEquals(sample, Hex.decode(Hex.encode(sample), 4));
    }

    @Test
    void encodeIsLowercaseAndZeroPadded() {
        assertEquals("000f10ab", Hex.encode(new byte[] {0x00, 0x0f, 0x10, (byte) 0xab}));
        assertEquals("", Hex.encode(new byte[0]));
    }

    // ---- rejections --------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"AB", "aB", "0A", "FF", "0aBc"})
    void decodeRejectsUppercase(String text) {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode(text, text.length() / 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zz", "g0", "0g", "0 ", " 0", "0.", "+-", "\u00e9\u00e9"})
    void decodeRejectsNonHexCharacters(String text) {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode(text, text.length() / 2));
    }

    @Test
    void decodeRejectsOddLength() {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("a", 1));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("abc", 2));
    }

    @Test
    void decodeRejectsWrongLength() {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("00", 2));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("000000", 1));
    }

    @Test
    void decodeRejectsNullText() {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode(null, 1));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode(null, 0));
    }

    @Test
    void decodeRejectsNegativeLength() {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("", -1));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("00", -1));
    }

    @Test
    void decodeRejectsOverflowLength() {
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("", MAX_BYTE_LENGTH + 1));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("", Integer.MAX_VALUE / 2));
        assertThrows(UpdateRejectedException.class, () -> Hex.decode("", Integer.MAX_VALUE));
    }

    @Test
    void kidRejectsMalformedPublicKeyHex() {
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(null));
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(""));
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(PUB_1.toUpperCase(Locale.ROOT)));
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(PUB_1.substring(0, 62)));
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(PUB_1 + "00"));
        assertThrows(UpdateRejectedException.class, () -> Hex.kid("zz".repeat(32)));
    }

    static Stream<String> malformedForKid() {
        return Stream.of(PUB_1.toUpperCase(Locale.ROOT), PUB_1.substring(0, 62), PUB_1 + "00",
                "zz".repeat(32), "");
    }

    @ParameterizedTest
    @MethodSource("malformedForKid")
    void kidRejectsEveryMalformedShape(String pubHex) {
        assertThrows(UpdateRejectedException.class, () -> Hex.kid(pubHex));
    }

    @Test
    void rejectionMessagesNeverEchoSuppliedInput() {
        assertNoEcho(() -> Hex.decode(MARKER, 3));
        assertNoEcho(() -> Hex.decode(MARKER + MARKER, 7));
        assertNoEcho(() -> Hex.decode(MARKER.toUpperCase(Locale.ROOT), 6));
        assertNoEcho(() -> Hex.kid(MARKER));
    }
}
