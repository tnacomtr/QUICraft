// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, bounded JSON reader for untrusted updater documents.
 *
 * <p>Accepts only the grammar the updater needs: strict UTF-8 without a BOM, ASCII JSON whitespace,
 * unique object keys, non-negative integers up to 2^53-1 without leading zeros, and a root object.
 * Results are immutable and never expose mutable containers. JSON null is preserved as Java null.
 * Schema-specific rules (field names, types, ranges) are enforced by callers.
 */
public final class StrictJson {
    /** Largest integer accepted for a JSON number (2^53-1). */
    static final long MAX_NUMBER = 9_007_199_254_740_991L;
    /** Maximum nesting depth, counting the root container as 1. */
    static final int MAX_DEPTH = 4;
    /** Maximum element count for any single array or object. */
    static final int MAX_CONTAINER_ENTRIES = 1024;
    /** Maximum number of decimal digits in an integer before parsing. */
    private static final int MAX_NUMBER_DIGITS = 16;

    private static final String REJECT_INPUT = "invalid json document";

    private StrictJson() {
    }

    /**
     * Parses one JSON object document.
     *
     * @param bytes the raw document bytes; must not be null
     * @param maxBytes the maximum accepted byte length, positive
     * @param maxStringChars the maximum length, in UTF-16 code units, of each decoded string or
     *     object key, positive
     * @return an immutable root map
     * @throws UpdateRejectedException on any violation, with a fixed message
     */
    public static Map<String, Object> parse(byte[] bytes, int maxBytes, int maxStringChars)
            throws UpdateRejectedException {
        if (bytes == null) {
            throw new UpdateRejectedException("json input is null");
        }
        if (maxBytes <= 0) {
            throw new UpdateRejectedException("json byte limit must be positive");
        }
        if (maxStringChars <= 0) {
            throw new UpdateRejectedException("json string limit must be positive");
        }
        if (bytes.length > maxBytes) {
            throw new UpdateRejectedException("json document exceeds byte limit");
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            throw new UpdateRejectedException("json document has a byte order mark");
        }
        String text = decodeStrictUtf8(bytes);
        Parser parser = new Parser(text, maxStringChars);
        parser.skipWhitespace();
        if (!parser.peekIs('{')) {
            throw new UpdateRejectedException("json root must be an object");
        }
        Object root = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new UpdateRejectedException("json has trailing data");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;
        return rootMap;
    }

    private static String decodeStrictUtf8(byte[] bytes) throws UpdateRejectedException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new UpdateRejectedException("json document is not valid utf-8", e);
        }
    }

    /** Recursive-descent parser over an already decoded, valid Unicode string. */
    private static final class Parser {
        private final String text;
        private final int maxStringChars;
        private int pos;

        Parser(String text, int maxStringChars) {
            this.text = text;
            this.maxStringChars = maxStringChars;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        boolean peekIs(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char next() throws UpdateRejectedException {
            if (pos >= text.length()) {
                throw new UpdateRejectedException(REJECT_INPUT);
            }
            return text.charAt(pos++);
        }

        /** Parses any value. {@code depth} is the number of enclosing containers. */
        Object parseValue(int depth) throws UpdateRejectedException {
            skipWhitespace();
            if (atEnd()) {
                throw new UpdateRejectedException(REJECT_INPUT);
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c >= '0' && c <= '9') {
                        return parseNumber();
                    }
                    throw new UpdateRejectedException(REJECT_INPUT);
            }
        }

        private void expectLiteral(String literal) throws UpdateRejectedException {
            if (!text.startsWith(literal, pos)) {
                throw new UpdateRejectedException(REJECT_INPUT);
            }
            pos += literal.length();
        }

        private Map<String, Object> parseObject(int depth) throws UpdateRejectedException {
            if (depth > MAX_DEPTH) {
                throw new UpdateRejectedException("json nesting exceeds depth limit");
            }
            pos++; // consume '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peekIs('}')) {
                pos++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                skipWhitespace();
                if (!peekIs('"')) {
                    throw new UpdateRejectedException(REJECT_INPUT);
                }
                String key = parseString();
                skipWhitespace();
                if (next() != ':') {
                    throw new UpdateRejectedException(REJECT_INPUT);
                }
                Object value = parseValue(depth);
                if (map.size() >= MAX_CONTAINER_ENTRIES) {
                    throw new UpdateRejectedException("json object exceeds entry limit");
                }
                if (map.containsKey(key)) {
                    throw new UpdateRejectedException("json object has a duplicate key");
                }
                map.put(key, value);
                skipWhitespace();
                char sep = next();
                if (sep == '}') {
                    return Collections.unmodifiableMap(map);
                }
                if (sep != ',') {
                    throw new UpdateRejectedException(REJECT_INPUT);
                }
            }
        }

        private List<Object> parseArray(int depth) throws UpdateRejectedException {
            if (depth > MAX_DEPTH) {
                throw new UpdateRejectedException("json nesting exceeds depth limit");
            }
            pos++; // consume '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peekIs(']')) {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                Object value = parseValue(depth);
                if (list.size() >= MAX_CONTAINER_ENTRIES) {
                    throw new UpdateRejectedException("json array exceeds entry limit");
                }
                list.add(value);
                skipWhitespace();
                char sep = next();
                if (sep == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (sep != ',') {
                    throw new UpdateRejectedException(REJECT_INPUT);
                }
            }
        }

        private Long parseNumber() throws UpdateRejectedException {
            int start = pos;
            char first = text.charAt(pos);
            if (first == '0') {
                pos++;
                if (pos < text.length() && isDigit(text.charAt(pos))) {
                    throw new UpdateRejectedException("json number has a leading zero");
                }
            } else {
                while (pos < text.length() && isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            int digits = pos - start;
            if (digits > MAX_NUMBER_DIGITS) {
                throw new UpdateRejectedException("json number exceeds digit limit");
            }
            if (pos < text.length()) {
                char after = text.charAt(pos);
                if (after == '.' || after == 'e' || after == 'E' || after == '-' || after == '+') {
                    throw new UpdateRejectedException("json number is not a non-negative integer");
                }
            }
            long value = Long.parseLong(text.substring(start, pos));
            if (value > MAX_NUMBER) {
                throw new UpdateRejectedException("json number exceeds 2^53-1");
            }
            return value;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private String parseString() throws UpdateRejectedException {
            pos++; // consume opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new UpdateRejectedException(REJECT_INPUT);
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new UpdateRejectedException("json string has a raw control character");
                }
                if (c == '\\') {
                    appendEscape(out);
                } else {
                    out.append(c);
                }
                if (out.length() > maxStringChars) {
                    throw new UpdateRejectedException("json string exceeds length limit");
                }
            }
        }

        private void appendEscape(StringBuilder out) throws UpdateRejectedException {
            char e = next();
            switch (e) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    appendUnicodeEscape(out);
                    break;
                default:
                    throw new UpdateRejectedException("json string has an invalid escape");
            }
        }

        private void appendUnicodeEscape(StringBuilder out) throws UpdateRejectedException {
            int unit = readHex4();
            if (Character.isLowSurrogate((char) unit)) {
                throw new UpdateRejectedException("json string has a lone surrogate escape");
            }
            if (Character.isHighSurrogate((char) unit)) {
                if (pos + 1 >= text.length() || text.charAt(pos) != '\\' || text.charAt(pos + 1) != 'u') {
                    throw new UpdateRejectedException("json string has a lone surrogate escape");
                }
                pos += 2;
                int low = readHex4();
                if (!Character.isLowSurrogate((char) low)) {
                    throw new UpdateRejectedException("json string has a lone surrogate escape");
                }
                out.append((char) unit).append((char) low);
            } else {
                out.append((char) unit);
            }
        }

        private int readHex4() throws UpdateRejectedException {
            if (pos + 4 > text.length()) {
                throw new UpdateRejectedException("json string has an invalid unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(pos + i), 16);
                char raw = text.charAt(pos + i);
                if (digit < 0 || raw > 0x7F) {
                    throw new UpdateRejectedException("json string has an invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            pos += 4;
            return value;
        }
    }
}
