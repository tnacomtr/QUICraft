// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny option reader for one subcommand. Options are {@code --name value} or {@code --name=value};
 * an unknown or repeated non-repeatable option is a usage error, never silently ignored.
 */
final class Cli {

    private final Map<String, List<String>> values = new LinkedHashMap<>();

    private Cli() {
    }

    /**
     * Parses {@code argv} from {@code from} on.
     *
     * @param known the option names this subcommand accepts
     * @param repeatable the option names that may be given more than once
     */
    static Cli parse(String[] argv, int from, Set<String> known, Set<String> repeatable)
            throws ToolException {
        Cli cli = new Cli();
        for (int i = from; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                throw new ToolException("unexpected argument '" + arg + "'");
            }
            String name = arg;
            String value = null;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                name = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            }
            if (!known.contains(name)) {
                throw new ToolException("unknown option '" + name + "'");
            }
            if (value == null) {
                if (i + 1 >= argv.length) {
                    throw new ToolException("option '" + name + "' needs a value");
                }
                value = argv[++i];
            }
            if (!repeatable.contains(name) && cli.values.containsKey(name)) {
                throw new ToolException("option '" + name + "' given more than once");
            }
            cli.values.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return cli;
    }

    /** True when the option was given at least once. */
    boolean has(String name) {
        return values.containsKey(name);
    }

    /** The single value of a required option. */
    String require(String name) throws ToolException {
        if (!values.containsKey(name)) {
            throw new ToolException("missing required option '" + name + "'");
        }
        return values.get(name).get(0);
    }

    /** All values of a repeatable option, in command-line order. */
    List<String> list(String name) {
        List<String> result = values.get(name);
        return result == null ? List.of() : List.copyOf(result);
    }

    Path requirePath(String name) throws ToolException {
        return Path.of(require(name));
    }

    /** The option's path, or {@code fallback} when it was not given. */
    Path optionalPath(String name, Path fallback) throws ToolException {
        return has(name) ? Path.of(values.get(name).get(0)) : fallback;
    }

    /** A required integer in {@code min..max}, written without sign or leading zeros. */
    long requireLong(String name, long min, long max) throws ToolException {
        String text = require(name);
        if (text.isEmpty() || (text.length() > 1 && text.charAt(0) == '0')) {
            throw new ToolException("'" + name + "' must be a decimal integer: " + text);
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ToolException("'" + name + "' must be a decimal integer: " + text);
        }
        if (value < min || value > max) {
            throw new ToolException("'" + name + "' must be " + min + ".." + max + ": " + value);
        }
        return value;
    }

    /** A required timestamp in the one schema form. */
    Instant requireTimestamp(String name) throws ToolException {
        return Timestamps.parse(require(name));
    }

    /** The timestamp option, or {@code fallback} when it was not given. */
    Instant optionalTimestamp(String name, Instant fallback) throws ToolException {
        return has(name) ? Timestamps.parse(values.get(name).get(0)) : fallback;
    }
}
