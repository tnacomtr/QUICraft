// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Black-box tests for the strict, bounded JSON reader. */
class StrictJsonTest {
    /** Normative nesting limit: root container counts as depth 1, so depth 4 is legal. */
    private static final int NORMATIVE_DEPTH = 4;
    /** Normative per-container entry limit. */
    private static final int NORMATIVE_ENTRIES = 1024;
    /** Normative largest accepted integer, 2^53-1. */
    private static final long NORMATIVE_MAX_NUMBER = 9_007_199_254_740_991L;
    /** Marker that must never appear in a rejection message. */
    private static final String MARKER = "MARKER42SECRET";

    private static final int HUGE = 1_000_000;
    private static final int STRING_CAP = 4096;

    // ---- helpers -----------------------------------------------------------------------------

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static Map<String, Object> parse(String json) throws UpdateRejectedException {
        return StrictJson.parse(utf8(json), HUGE, STRING_CAP);
    }

    private static Map<String, Object> parse(byte[] bytes, int maxBytes, int maxStringChars)
            throws UpdateRejectedException {
        return StrictJson.parse(bytes, maxBytes, maxStringChars);
    }

    private static UpdateRejectedException rejects(String json) {
        return rejects(utf8(json), HUGE, STRING_CAP);
    }

    private static UpdateRejectedException rejects(byte[] bytes, int maxBytes, int maxStringChars) {
        return assertThrows(
                UpdateRejectedException.class, () -> StrictJson.parse(bytes, maxBytes, maxStringChars));
    }

    private static void assertRejectsWithoutEcho(String json) {
        UpdateRejectedException e = rejects(json);
        assertNotNull(e.getMessage(), "rejection message must not be null");
        assertFalse(e.getMessage().contains(MARKER), "error echoed the supplied input: " + e.getMessage());
    }

    private static String objectWithEntries(int count) {
        StringBuilder sb = new StringBuilder("{\"k0\":0");
        for (int i = 1; i < count; i++) {
            sb.append(",\"k").append(i).append("\":").append(i);
        }
        return sb.append('}').toString();
    }

    private static String nestedArray(String inner, int brackets) {
        StringBuilder sb = new StringBuilder("{\"a\":");
        for (int i = 0; i < brackets; i++) {
            sb.append('[');
        }
        sb.append(inner);
        for (int i = 0; i < brackets; i++) {
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    // ---- accepted shape ----------------------------------------------------------------------

    @Test
    void validRootMapPreservesEveryValueTypeAndNull() throws Exception {
        Map<String, Object> m = parse(
                "{\"num\":42,\"t\":true,\"f\":false,\"str\":\"hi\",\"nil\":null,"
                        + "\"arr\":[1,\"x\",null,true],\"obj\":{\"inner\":\"v\"}}");
        assertEquals(42L, m.get("num"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertEquals("hi", m.get("str"));
        assertTrue(m.containsKey("nil"));
        assertNull(m.get("nil"));
        List<?> arr = (List<?>) m.get("arr");
        assertEquals(4, arr.size());
        assertEquals(1L, arr.get(0));
        assertEquals("x", arr.get(1));
        assertNull(arr.get(2));
        assertEquals(Boolean.TRUE, arr.get(3));
        assertEquals("v", ((Map<?, ?>) m.get("obj")).get("inner"));
    }

    @Test
    void nullValueIsDistinctFromAbsentKey() throws Exception {
        Map<String, Object> m = parse("{\"a\":null,\"b\":1}");
        assertTrue(m.containsKey("a"));
        assertNull(m.get("a"));
        assertFalse(m.containsKey("c"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void containersAreImmutableAtEveryLevelIncludingNullValues() throws Exception {
        Map<String, Object> root = parse("{\"a\":1,\"nil\":null,\"obj\":{\"b\":[{\"c\":2}]}}");
        assertThrows(UnsupportedOperationException.class, () -> root.put("z", 1));
        assertThrows(UnsupportedOperationException.class, () -> root.remove("a"));
        Map obj = (Map) root.get("obj");
        assertThrows(UnsupportedOperationException.class, () -> obj.put("z", 1));
        List list = (List) obj.get("b");
        assertThrows(UnsupportedOperationException.class, () -> list.add("x"));
        Map nested = (Map) list.get(0);
        assertThrows(UnsupportedOperationException.class, () -> nested.put("z", 1));
    }

    // ---- byte cap and limits -----------------------------------------------------------------

    @Test
    void exactByteCapAcceptedAndOneOverRejected() throws Exception {
        byte[] exact = utf8("{\"a\":1}");
        assertEquals(1L, parse(exact, exact.length, STRING_CAP).get("a"));
        rejects(exact, exact.length - 1, STRING_CAP);
        rejects(utf8("{\"a\":10}"), exact.length, STRING_CAP);
    }

    @Test
    void nullByteArrayRejected() {
        UpdateRejectedException e = rejects(null, 16, 16);
        assertNotNull(e.getMessage());
    }

    @Test
    void nonPositiveByteLimitRejected() {
        byte[] bytes = utf8("{}");
        rejects(bytes, 0, 16);
        rejects(bytes, -1, 16);
    }

    @Test
    void nonPositiveStringLimitRejected() {
        byte[] bytes = utf8("{\"a\":\"x\"}");
        rejects(bytes, 16, 0);
        rejects(bytes, 16, -3);
    }

    // ---- encoding ----------------------------------------------------------------------------

    @Test
    void validRawSupplementaryCharacterAccepted() throws Exception {
        String smile = "\uD83D\uDE00";
        Map<String, Object> m = parse("{\"a\":\"" + smile + "\"}");
        assertEquals(smile, m.get("a"));
        assertEquals(2, ((String) m.get("a")).length());
    }

    @Test
    void bomAtStartRejected() {
        byte[] bytes = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, utf8("{}"));
        rejects(bytes, 64, 16);
    }

    static Stream<byte[]> malformedUtf8() {
        return Stream.of(
                new byte[] {(byte) 0xC3, 0x28}, // bad continuation
                new byte[] {(byte) 0xE2, (byte) 0x82}, // truncated 3-byte sequence
                new byte[] {(byte) 0xC0, (byte) 0xAF}, // overlong encoding of '/'
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // UTF-8-encoded lone surrogate
                new byte[] {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80}, // above U+10FFFF
                new byte[] {(byte) 0xFF}); // never valid
    }

    @ParameterizedTest
    @MethodSource("malformedUtf8")
    void malformedTruncatedOverlongOrSurrogateUtf8Rejected(byte[] bad) {
        byte[] bytes = concat(utf8("{\"a\":\""), bad, utf8("\"}"));
        rejects(bytes, 64, 16);
    }

    // ---- strings and escapes -----------------------------------------------------------------

    @Test
    void everyJsonEscapeAcceptedIncludingNullEscape() throws Exception {
        String payload = "\\\"" + "\\\\" + "\\/" + "\\b" + "\\f" + "\\n" + "\\r" + "\\t" + "\\u0000";
        String expected = "\"" + "\\" + "/" + "\b" + "\f" + "\n" + "\r" + "\t" + "\u0000";
        assertEquals(9, expected.length());
        assertEquals(expected, parse("{\"a\":\"" + payload + "\"}").get("a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":\"line\nbreak\"}", "{\"a\":\"bell\u0007\"}", "{\"a\":\"nul\u0000\"}"})
    void rawControlCharacterInStringRejected(String json) {
        rejects(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":\"\\x\"}", "{\"a\":\"\\a\"}", "{\"a\":\"\\'\"}", "{\"a\":\"\\ \"}"})
    void invalidEscapeSequenceRejected(String json) {
        rejects(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":\"\\uZZZZ\"}", "{\"a\":\"\\u12G4\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u\"}"})
    void malformedUnicodeHexEscapeRejected(String json) {
        rejects(json);
    }

    @Test
    void nonAsciiCharacterInUnicodeEscapeRejected() {
        rejects("{\"a\":\"\\u" + '\u00e9' + "ab\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":\"\\uD800\"}", // lone high surrogate
        "{\"a\":\"\\uDC00\"}", // lone low surrogate
        "{\"a\":\"\\uD83D\\u0041\"}", // high surrogate not followed by a low one
        "{\"a\":\"\\uD83D\"}" // high surrogate at end of string
    })
    void loneEscapedSurrogateRejected(String json) {
        rejects(json);
    }

    @Test
    void pairedEscapedSurrogatesAccepted() throws Exception {
        String smile = "\uD83D\uDE00";
        assertEquals(smile, parse("{\"a\":\"\\uD83D\\uDE00\"}").get("a"));
    }

    @Test
    void rawSupplementaryStringCountsTwoUtf16UnitsForCap() throws Exception {
        String smile = "\uD83D\uDE00";
        byte[] bytes = utf8("{\"a\":\"" + smile + "\"}");
        assertEquals(smile, parse(bytes, HUGE, 2).get("a"));
        rejects(bytes, HUGE, 1);
    }

    @Test
    void stringLengthCapExactAcceptedOneOverRejected() throws Exception {
        byte[] bytes = utf8("{\"a\":\"xx\"}");
        assertEquals("xx", parse(bytes, HUGE, 2).get("a"));
        rejects(bytes, HUGE, 1);
    }

    @Test
    void keyLengthCapExactAcceptedOneOverRejected() throws Exception {
        byte[] bytes = utf8("{\"ab\":1}");
        assertEquals(1L, parse(bytes, HUGE, 2).get("ab"));
        rejects(bytes, HUGE, 1);
    }

    // ---- object semantics --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1,\"a\":2}",
        "{\"a\":null,\"a\":1}",
        "{\"a\":null,\"a\":null}",
        "{\"a\":1,\"\\u0061\":2}", // escaped key aliases an earlier key
        "{\"o\":{\"x\":1,\"x\":2}}"
    })
    void duplicateKeysRejected(String json) {
        rejects(json);
    }

    @Test
    void objectAtEntryLimitAcceptedAndOneOverRejected() throws Exception {
        assertEquals(NORMATIVE_ENTRIES, parse(objectWithEntries(NORMATIVE_ENTRIES)).size());
        rejects(objectWithEntries(NORMATIVE_ENTRIES + 1));
    }

    @Test
    void arrayAtEntryLimitAcceptedAndOneOverRejected() throws Exception {
        assertEquals(NORMATIVE_ENTRIES, ((List<?>) parse(arrayWithEntries(NORMATIVE_ENTRIES)).get("a")).size());
        rejects(arrayWithEntries(NORMATIVE_ENTRIES + 1));
    }

    private static String arrayWithEntries(int count) {
        StringBuilder sb = new StringBuilder("{\"a\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('0');
        }
        return sb.append("]}").toString();
    }

    // ---- depth -------------------------------------------------------------------------------

    @Test
    void depthFourAccepted() throws Exception {
        assertNotNull(parse("{\"a\":{\"b\":{\"c\":{\"d\":1}}}}")); // NORMATIVE_DEPTH
        assertNotNull(parse(nestedArray("1", NORMATIVE_DEPTH - 1)));
    }

    @Test
    void depthFiveRejected() {
        rejects("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}");
        rejects(nestedArray("1", NORMATIVE_DEPTH));
    }

    // ---- numbers -----------------------------------------------------------------------------

    @Test
    void largestAcceptedIntegerIsUint53Max() throws Exception {
        assertEquals(NORMATIVE_MAX_NUMBER, parse("{\"n\":9007199254740991}").get("n"));
    }

    @Test
    void oneAboveUint53MaxRejected() {
        rejects("{\"n\":9007199254740992}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"n\":9999999999999999}", "{\"n\":12345678901234567}"})
    void numberOverUint53Rejected(String json) {
        rejects(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"n\":-1}",
        "{\"n\":+1}",
        "{\"n\":1.0}",
        "{\"n\":1e3}",
        "{\"n\":1E3}",
        "{\"n\":01}",
        "{\"n\":00}",
        "{\"n\":-0}"
    })
    void invalidNumberFormsRejected(String json) {
        rejects(json);
    }

    // ---- structure ---------------------------------------------------------------------------

    @Test
    void asciiJsonWhitespaceAcceptedAroundTokens() throws Exception {
        assertEquals(1L, parse(" \t\r\n{\"a\" \t: \r\n1}\n").get("a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\f\"a\":1}", "{\u000B\"a\":1}", "{\"a\":\f1}"})
    void nonJsonWhitespaceRejected(String json) {
        rejects(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1} x", // trailing garbage
        "[1]", // root array
        "\"x\"", // root string
        "1", // root number
        "true", // root literal
        "", // empty document
        "   ", // whitespace only
        "{\"a\":1", // unclosed object
        "{\"a\":[1", // unclosed array
        "{\"a\":1,}", // trailing comma in object
        "{\"a\":[1,]}", // trailing comma in array
        "{\"a\":1\"b\":2}", // missing separator
        "{\"a\" 1}", // missing colon
        "{a:1}", // unquoted key
        "{}extra"
    })
    void malformedDocumentsRejected(String json) {
        rejects(json);
    }

    // ---- error hygiene -----------------------------------------------------------------------

    @Test
    void rejectionMessagesNeverEchoSuppliedInput() {
        assertRejectsWithoutEcho("{\"" + MARKER + "\":}");
        assertRejectsWithoutEcho("{\"a\":1,\"a\":" + MARKER + "}");
        assertRejectsWithoutEcho("{" + MARKER + "}");
        assertRejectsWithoutEcho("{\"a\":\"" + MARKER);
        assertRejectsWithoutEcho("{\"a\":12" + MARKER + "}");
        assertRejectsWithoutEcho("{\"x\":\"" + MARKER + "\"}junk");
        assertRejectsWithoutEcho("{\"a\":\"" + MARKER + "\",\"a\":\"other\"}");
    }

    @Test
    void utf8RejectionMessageNeverEchoesSuppliedInput() {
        byte[] bytes = concat(utf8("{\"a\":\"" + MARKER + "\""), new byte[] {(byte) 0xC3, 0x28});
        UpdateRejectedException e = rejects(bytes, 256, STRING_CAP);
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().contains(MARKER), "error echoed the supplied input: " + e.getMessage());
    }
}
