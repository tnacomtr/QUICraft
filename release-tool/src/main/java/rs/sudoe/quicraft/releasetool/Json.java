// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

/**
 * Minimal JSON string encoding for the documents the tool writes.
 *
 * <p>Every document is written compactly (no whitespace, no trailing newline) and re-parsed by the
 * updater's strict parser before the tool reports success, so a mistake here fails the command
 * rather than reaching a release.
 */
final class Json {

    private Json() {
    }

    /** Returns the JSON string literal for {@code text}, escaping what JSON requires. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
