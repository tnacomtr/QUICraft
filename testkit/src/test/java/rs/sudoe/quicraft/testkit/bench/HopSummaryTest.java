// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HopSummaryTest {
    private static JsonObject row(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String COMPLETE = "{\"hop\":true,\"hopRequested\":6,\"hopCount\":6,"
            + "\"hopFillMsMedian\":210.5,\"hopFillMsP95\":260.0,\"hopError\":null}";

    @Test
    void isHopOnlyForARowThatSaysSo() {
        assertTrue(HopSummary.isHop(row(COMPLETE)));
        assertFalse(HopSummary.isHop(row("{\"hop\":false}")));
        assertFalse(HopSummary.isHop(row("{\"hop\":\"true\"}")));
        assertFalse(HopSummary.isHop(row("{}")));
    }

    @Test
    void completeNeedsEveryLegAndNoError() {
        assertTrue(HopSummary.complete(row(COMPLETE)));
        assertFalse(HopSummary.complete(row(COMPLETE.replace("\"hopCount\":6", "\"hopCount\":5"))));
        assertFalse(HopSummary.complete(row(COMPLETE.replace("\"hopRequested\":6", "\"hopRequested\":4"))));
        assertFalse(HopSummary.complete(row(COMPLETE.replace("\"hopError\":null",
                "\"hopError\":\"leg 3 did not reach 90%\""))));
        assertFalse(HopSummary.complete(row(COMPLETE.replace("\"hopFillMsP95\":260.0", "\"hopFillMsP95\":null"))));
        assertFalse(HopSummary.complete(row("{\"hop\":false,\"hopRequested\":6,\"hopCount\":6}")));
    }

    @Test
    void missingErrorFieldIsNotAnError() {
        JsonObject run = row(COMPLETE);
        run.remove("hopError");
        assertTrue(HopSummary.complete(run));
        run.add("hopError", JsonNull.INSTANCE);
        assertTrue(HopSummary.complete(run));
    }

    @Test
    void numbersAndIntegersAreMissingWhenAbsentOrNotNumeric() {
        JsonObject run = row("{\"a\":1.5,\"b\":\"x\",\"c\":null,\"inf\":1e999}");
        assertEquals(1.5, HopSummary.number(run, "a"));
        assertTrue(Double.isNaN(HopSummary.number(run, "b")));
        assertTrue(Double.isNaN(HopSummary.number(run, "c")));
        assertTrue(Double.isNaN(HopSummary.number(run, "absent")));
        assertEquals(6, HopSummary.integer(COMPLETE_ROW, "hopCount"));
        assertEquals(-1, HopSummary.integer(run, "absent"));
        assertEquals(-1, HopSummary.integer(run, "b"));
    }

    private static final JsonObject COMPLETE_ROW = row(COMPLETE);
}
