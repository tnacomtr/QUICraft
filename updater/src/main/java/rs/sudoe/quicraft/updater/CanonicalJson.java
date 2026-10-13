// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small deterministic JSON writer for the persisted state files. Object keys are written in
 * lexicographic order at every level, with no whitespace. Only {@link Map}, {@link List},
 * {@link String}, {@link Long} and {@link Integer} values are accepted.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /** Renders {@code value} as canonical JSON text. */
    static String render(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    /**
     * Formats a UTC timestamp as {@code YYYY-MM-DDTHH:MM:SSZ}.
     *
     * @throws IllegalArgumentException if the instant has sub-second precision or its year is
     *     outside 0000..9999
     */
    static String timestamp(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("null timestamp");
        }
        if (instant.getNano() != 0) {
            throw new IllegalArgumentException("timestamp has sub-second precision");
        }
        int year = instant.atOffset(ZoneOffset.UTC).getYear();
        if (year < 0 || year > 9999) {
            throw new IllegalArgumentException("timestamp year out of range");
        }
        return DocumentParser.TIMESTAMP_FORMAT.format(instant.atZone(ZoneOffset.UTC));
    }

    private static void append(StringBuilder sb, Object value) {
        if (value instanceof String text) {
            appendString(sb, text);
        } else if (value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            // Sorted copy: the output order never depends on the caller's map type.
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put((String) e.getKey(), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, e.getKey());
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value");
        }
    }

    private static void appendString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
