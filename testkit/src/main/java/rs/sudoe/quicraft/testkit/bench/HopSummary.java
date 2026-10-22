// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The flight-hop fields of a row written by ExternalArmGameTest with -Pquicraft.armHop=true: six
 * legs, each with a fill time in milliseconds (from the leg's first tick until 90% of the columns
 * around its final position are loaded). Reading only; a row is complete when every requested leg
 * completed and the row reports no error, anything else is not hop evidence.
 */
final class HopSummary {
    /** Legs per hop run; must match HOP_LEGS in ExternalArmGameTest. */
    static final int REQUESTED_LEGS = 6;

    private HopSummary() {}

    /** True when the row says it ran the hop window (every row carries the key; false for other runs). */
    static boolean isHop(JsonObject run) {
        JsonElement hop = run.get("hop");
        return hop != null && hop.isJsonPrimitive() && hop.getAsJsonPrimitive().isBoolean() && hop.getAsBoolean();
    }

    /** A hop row that completed every requested leg with no error and finite fill statistics. */
    static boolean complete(JsonObject run) {
        if (!isHop(run) || integer(run, "hopRequested") != REQUESTED_LEGS || integer(run, "hopCount") != REQUESTED_LEGS) {
            return false;
        }
        JsonElement error = run.get("hopError");
        if (error != null && !error.isJsonNull()) {
            return false;
        }
        return Double.isFinite(number(run, "hopFillMsMedian")) && Double.isFinite(number(run, "hopFillMsP95"));
    }

    /** The finite numeric field, or NaN when it is absent, not a number or not finite. */
    static double number(JsonObject run, String field) {
        JsonElement value = run.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return Double.NaN;
        }
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : Double.NaN;
    }

    /** The integer field, or -1 when it is absent or not a number. */
    static int integer(JsonObject run, String field) {
        JsonElement value = run.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        return value.getAsInt();
    }
}
