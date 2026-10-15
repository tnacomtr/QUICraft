// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The small strict JSON reader and the typed accessors the pin and re-bind state files are read with.
 *
 * <p>{@code core.discovery.Json} is the project's JSON parser, but it is tuned for a status
 * response: it caps its input at 1 MiB, while a full pin store (4096 pins) or a full re-bind
 * history (65536 notices) is larger than that, and its quoting helper is package-private. This
 * parser is deliberately smaller: it reads exactly the shapes these files contain and still rejects
 * everything malformed, including duplicate member names, trailing data, over-deep nesting and
 * oversized input. Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, strings
 * {@link String}, and integral numbers {@link Long}. Any other number becomes a
 * {@link BigDecimal}, which the integer accessor refuses, so {@code 1.0} or {@code 1e3} is not
 * accepted where a port, version or failure count belongs.</p>
 */
final class StrictJson {
    private static final int MAX_DEPTH = 8;
    /** Above the largest file the pin and re-bind stores can produce (about 10 MiB). */
    private static final int MAX_LENGTH = 32 << 20;
    /** Digits and sign a number may have before it is called malformed rather than merely large. */
    private static final int MAX_NUMBER_LENGTH = 100;

    private final String text;
    private int position;

    private StrictJson(String text) {
        this.text = text;
    }

    /**
     * Parses {@code text}, rejecting anything RFC 8259 does not allow and any repeated member name.
     *
     * @throws IllegalArgumentException when the text is null, oversized or malformed
     */
    static Object parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("missing or oversized JSON");
        }
        StrictJson json = new StrictJson(text);
        json.whitespace();
        Object value = json.value(0);
        json.whitespace();
        if (json.position != text.length()) {
            throw json.error("trailing data");
        }
        return value;
    }

    /** {@code value} as an object, or an {@link IllegalArgumentException} naming {@code what}. */
    static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(what + " is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    /** {@code value} as an array, or an {@link IllegalArgumentException} naming {@code what}. */
    static List<Object> array(Object value, String what) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(what + " is not an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    /** {@code value} as a string, or an {@link IllegalArgumentException} naming {@code what}. */
    static String string(Object value, String what) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(what + " is not a string");
        }
        return (String) value;
    }

    /** {@code value} as an integer inside {@code min}..{@code max}. */
    static int integer(Object value, String what, int min, int max) {
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException(what + " is not an integer");
        }
        long number = ((Long) value).longValue();
        if (number < min || number > max) {
            throw new IllegalArgumentException(what + " is out of range: " + number);
        }
        return (int) number;
    }

    /**
     * {@code value} as a UTC ISO-8601 timestamp, the form both state files store time in. Anything
     * with an offset other than {@code Z} is rejected rather than silently converted.
     */
    static Instant instant(Object value, String what) {
        String timestamp = string(value, what);
        if (!timestamp.endsWith("Z")) {
            throw new IllegalArgumentException(what + " is not a UTC timestamp: " + timestamp);
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " is not an ISO-8601 timestamp: " + timestamp, e);
        }
    }

    /** {@code value} as a quoted JSON string literal, with the timestamp's second precision kept. */
    static String quotedInstant(Instant value) {
        return quote(DateTimeFormatter.ISO_INSTANT.format(value));
    }

    /** {@code value} as a JSON string literal, escaping what RFC 8259 requires. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        if (c == '{') {
            return object(depth);
        }
        if (c == '[') {
            return array(depth);
        }
        if (c == '"') {
            return string();
        }
        if (c == 't') {
            return literal("true", Boolean.TRUE);
        }
        if (c == 'f') {
            return literal("false", Boolean.FALSE);
        }
        if (c == 'n') {
            return literal("null", null);
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return number();
        }
        throw error("unexpected character");
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        whitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            whitespace();
            if (peek() != '"') {
                throw error("expected member name");
            }
            String name = string();
            whitespace();
            expect(':');
            whitespace();
            if (map.containsKey(name)) {
                throw error("duplicate member name");
            }
            map.put(name, value(depth + 1));
            whitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        position++;
        whitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            whitespace();
            list.add(value(depth + 1));
            whitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        position++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char escaped = next();
            if (escaped == '"') {
                sb.append('"');
            } else if (escaped == '\\') {
                sb.append('\\');
            } else if (escaped == '/') {
                sb.append('/');
            } else if (escaped == 'b') {
                sb.append('\b');
            } else if (escaped == 'f') {
                sb.append('\f');
            } else if (escaped == 'n') {
                sb.append('\n');
            } else if (escaped == 'r') {
                sb.append('\r');
            } else if (escaped == 't') {
                sb.append('\t');
            } else if (escaped == 'u') {
                sb.append(unicodeEscape());
            } else {
                throw error("bad escape");
            }
        }
    }

    private char unicodeEscape() {
        if (position + 4 > text.length()) {
            throw error("short unicode escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexDigit(text.charAt(position + i));
            if (digit < 0) {
                throw error("bad unicode escape");
            }
            code = (code << 4) | digit;
        }
        position += 4;
        return (char) code;
    }

    private Object number() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                position++;
            }
        } else {
            throw error("bad number");
        }
        if (peek() == '.') {
            position++;
            if (!isDigit(peek())) {
                throw error("bad fraction");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            if (!isDigit(peek())) {
                throw error("bad exponent");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        String literal = text.substring(start, position);
        if (literal.length() > MAX_NUMBER_LENGTH) {
            throw error("number too long");
        }
        if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
            try {
                return Long.valueOf(literal);
            } catch (NumberFormatException e) {
                // Larger than a long: still a valid JSON number, but never a port or a count.
                return new BigDecimal(literal);
            }
        }
        return new BigDecimal(literal);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("bad literal");
        }
        position += word.length();
        return value;
    }

    private void whitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private char next() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(position++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** The value of {@code c} as an ASCII hexadecimal digit, or -1 when it is not one. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + position);
    }
}
