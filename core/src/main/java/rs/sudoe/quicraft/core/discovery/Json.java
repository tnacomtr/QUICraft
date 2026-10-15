// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 8259 parser for status responses. The input comes from untrusted servers, so depth
 * and length are bounded and every malformed input throws {@link IllegalArgumentException}. Objects
 * become {@code Map<String, Object>}, arrays {@code List<Object>}, numbers
 * {@link BigDecimal}, and JSON null {@link #NULL}.
 *
 * <p>{@link #parse(String)} keeps the last value of a repeated member name (RFC 8259 leaves that
 * undefined, and a status response only ever reads a member it expects). Callers that must reject
 * duplicates, like {@code core.routing} for routing resources (docs/protocol.md 13.7), use
 * {@link #parseRejectingDuplicateKeys(String)}.
 *
 * <p>Public so {@code core.routing} can reuse it for routing resources (docs/protocol.md 13.7)
 * instead of adding a JSON dependency or a second parser.
 */
public final class Json {
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };
    static final int MAX_DEPTH = 64;
    /** Generous bound above the 32767-character status cap. */
    static final int MAX_LENGTH = 1 << 20;

    private final String s;
    private final boolean rejectDuplicateKeys;
    private int pos;

    private Json(String s, boolean rejectDuplicateKeys) {
        this.s = s;
        this.rejectDuplicateKeys = rejectDuplicateKeys;
    }

    /** Parses {@code text}, letting the last of repeated member names win. */
    static Object parse(String text) {
        return parse(text, false);
    }

    /**
     * Parses {@code text} like {@link #parse(String)}, but throws
     * {@link IllegalArgumentException} when any object, at any level, repeats a member name.
     * docs/protocol.md 13.7 requires routing resources to be rejected on duplicates.
     */
    public static Object parseRejectingDuplicateKeys(String text) {
        return parse(text, true);
    }

    private static Object parse(String text, boolean rejectDuplicateKeys) {
        if (text == null || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("missing or oversized JSON");
        }
        Json json = new Json(text, rejectDuplicateKeys);
        json.ws();
        Object value = json.value(0);
        json.ws();
        if (json.pos != text.length()) {
            throw json.error("trailing data");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", NULL);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("expected member name");
            }
            String key = string();
            ws();
            expect(':');
            ws();
            if (rejectDuplicateKeys && map.containsKey(key)) {
                throw error("duplicate member name " + quote(key));
            }
            map.put(key, value(depth + 1));
            ws();
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
        pos++; // [
        ws();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            ws();
            list.add(value(depth + 1));
            ws();
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
        pos++; // "
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
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("short unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape");
            }
        }
    }

    private BigDecimal number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("bad number");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("bad fraction");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("bad exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (pos - start > 100) {
            throw error("number too long");
        }
        return new BigDecimal(s.substring(start, pos));
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) {
            throw error("bad literal");
        }
        pos += word.length();
        return value;
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + pos);
    }

    /** JSON string literal for {@code value}, escaping what RFC 8259 requires. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
