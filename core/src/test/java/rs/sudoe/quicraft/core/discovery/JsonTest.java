// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parser's two entry points. {@link Json#parse(String)} is the one discovery uses for status
 * responses and keeps its last-wins behaviour for a repeated member name; the tests here pin that
 * behaviour so adding {@link Json#parseRejectingDuplicateKeys(String)} for core.routing
 * (docs/protocol.md 13.7) cannot have changed it.
 */
class JsonTest {

    @Test
    void parseKeepsTheLastValueOfARepeatedMember() {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(
                "{\"schema\":9,\"schema\":1,\"nested\":{\"lane\":\"world\",\"lane\":\"control\"}}");
        assertEquals(1, ((BigDecimal) root.get("schema")).intValueExact());
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) root.get("nested");
        assertEquals("control", nested.get("lane"));
    }

    @Test
    void parseStillRejectsMissingAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(null));
        StringBuilder oversized = new StringBuilder(Json.MAX_LENGTH + 1);
        for (int i = 0; i <= Json.MAX_LENGTH; i++) {
            oversized.append(' ');
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parse(oversized.toString()));
    }

    @Test
    void rejectingDuplicateKeysRejectsRepeatedMemberNamesAtEveryLevel() {
        for (String duplicate : new String[] {
            "{\"schema\":9,\"schema\":1}",
            "{\"a\":{\"lane\":\"world\",\"lane\":\"control\"}}",
            "{\"routes\":[{\"lane\":\"world\",\"lane\":\"control\"}]}",
        }) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Json.parseRejectingDuplicateKeys(duplicate), duplicate);
            assertTrue(e.getMessage().contains("duplicate member name"), e.getMessage());
        }
    }

    @Test
    void rejectingDuplicateKeysAcceptsCleanInputLikeParse() {
        String clean = "{\"a\":1,\"b\":{\"c\":[1,\"x\",true,null]},\"d\":-1.5e3}";
        assertEquals(Json.parse(clean).toString(), Json.parseRejectingDuplicateKeys(clean).toString());
    }

    @Test
    void rejectingDuplicateKeysStillRejectsMalformedInput() {
        for (String bad : new String[] {"{\"a\":1,}", "{\"a\":01}", "{'a':1}", "{\"a\":1", "tru"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Json.parseRejectingDuplicateKeys(bad), bad);
        }
    }
}
