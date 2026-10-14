// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * The one timestamp encoding of the updater documents: {@code YYYY-MM-DDTHH:MM:SSZ}, UTC, whole
 * seconds, no fractions and no offsets (docs/updater.md 12.3).
 *
 * <p>Calendar-invalid values and {@code 24:00} are rejected rather than normalised, exactly as the
 * updater's parser rejects them.
 */
final class Timestamps {

    private static final Pattern SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                    .withResolverStyle(ResolverStyle.STRICT);

    private Timestamps() {
    }

    /**
     * Parses a strict UTC timestamp.
     *
     * @throws ToolException if the text is not exactly the schema form
     */
    static Instant parse(String text) throws ToolException {
        if (text == null || text.length() != 20 || !SHAPE.matcher(text).matches()) {
            throw new ToolException(
                    "timestamp must be exactly YYYY-MM-DDTHH:MM:SSZ (UTC): " + text);
        }
        try {
            return LocalDateTime.parse(text, FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new ToolException("timestamp is not a valid calendar instant: " + text);
        }
    }

    /**
     * Formats an instant in the schema form.
     *
     * @throws ToolException if the year does not fit the four-digit field
     */
    static String format(Instant instant) throws ToolException {
        String text = FORMAT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        if (text.length() != 20) {
            throw new ToolException("instant is outside the four-digit year range: " + text);
        }
        return text;
    }

    /** The current UTC time with whole seconds (fractions are not representable in the schema). */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Adds whole days, failing if the result leaves the four-digit year range. */
    static Instant plusDays(Instant issued, long days) throws ToolException {
        return issued.plus(days, ChronoUnit.DAYS);
    }

    /**
     * Formats for display only, never failing: every accepted timestamp already fits the schema, and
     * anything else falls back to ISO-8601 rather than refusing to print a diagnosis.
     */
    static String formatUnchecked(Instant instant) {
        try {
            return format(instant);
        } catch (ToolException e) {
            return instant.toString();
        }
    }
}
