// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit;

import java.util.HashMap;
import java.util.Map;

/** Minimal {@code --key value} / {@code --flag} parser. */
public final class Args {
    private final Map<String, String> values;

    private Args(Map<String, String> values) {
        this.values = values;
    }

    public static Args parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --key, got " + args[i]);
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                values.put(key, args[++i]);
            } else {
                values.put(key, "true");
            }
        }
        return new Args(values);
    }

    public String string(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public String required(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    public int integer(String key, int fallback) {
        String value = values.get(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    public double decimal(String key, double fallback) {
        String value = values.get(key);
        return value == null ? fallback : Double.parseDouble(value);
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(values.getOrDefault(key, "false"));
    }
}
