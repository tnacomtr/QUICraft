// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Black-box tests for release version parsing and ordering. */
class UpdateVersionTest {
    /** Normative maximum value of any numeric component. */
    private static final int NORMATIVE_MAX_COMPONENT = 99_999;
    /** Marker that must never appear in a rejection message. */
    private static final String MARKER = "MARKER42SECRET";

    private static UpdateVersion parse(String text) throws UpdateRejectedException {
        return UpdateVersion.parse(text);
    }

    private static UpdateRejectedException rejects(String text) {
        return assertThrows(UpdateRejectedException.class, () -> UpdateVersion.parse(text));
    }

    private static void assertRejectsWithoutEcho(String text) {
        UpdateRejectedException e = rejects(text);
        assertNotNull(e.getMessage(), "rejection message must not be null");
        assertFalse(e.getMessage().contains(MARKER), "error echoed the supplied input: " + e.getMessage());
    }

    // ---- accepted forms ----------------------------------------------------------------------

    @Test
    void parsesNumericTripleIncludingBoundaries() throws Exception {
        assertEquals("0.0.0", parse("0.0.0").toString());
        assertEquals("1.2.3", parse("1.2.3").toString());
        assertEquals(
                "99999.99999.99999",
                parse(NORMATIVE_MAX_COMPONENT + "." + NORMATIVE_MAX_COMPONENT + "." + NORMATIVE_MAX_COMPONENT)
                        .toString());
    }

    @Test
    void parsesEveryPrereleaseChannel() throws Exception {
        assertEquals("1.2.3-alpha.7", parse("1.2.3-alpha.7").toString());
        assertEquals("1.2.3-beta.7", parse("1.2.3-beta.7").toString());
        assertEquals("1.2.3-rc.7", parse("1.2.3-rc.7").toString());
        assertEquals("1.2.3-alpha." + NORMATIVE_MAX_COMPONENT, parse("1.2.3-alpha.99999").toString());
    }

    // ---- ordering ----------------------------------------------------------------------------

    @Test
    void numericTripleDominatesPrereleaseChannel() throws Exception {
        assertTrue(parse("1.2.4-alpha.1").compareTo(parse("1.2.3")) > 0);
        assertTrue(parse("1.3.0-alpha.1").compareTo(parse("1.2.99-rc.99")) > 0);
        assertTrue(parse("2.0.0-alpha.1").compareTo(parse("1.99999.99999-rc.99999")) > 0);
    }

    @Test
    void stableOrdersAboveRcAboveBetaAboveAlpha() throws Exception {
        UpdateVersion stable = parse("1.2.3");
        UpdateVersion rc = parse("1.2.3-rc.1");
        UpdateVersion beta = parse("1.2.3-beta.1");
        UpdateVersion alpha = parse("1.2.3-alpha.1");
        assertTrue(stable.compareTo(rc) > 0);
        assertTrue(rc.compareTo(beta) > 0);
        assertTrue(beta.compareTo(alpha) > 0);
        assertTrue(alpha.compareTo(stable) < 0);
    }

    @Test
    void prereleaseNumberIsNumericNotLexicographic() throws Exception {
        assertTrue(parse("1.2.3-alpha.2").compareTo(parse("1.2.3-alpha.10")) < 0);
        assertTrue(parse("1.2.3-alpha.9").compareTo(parse("1.2.3-alpha.100")) < 0);
        assertTrue(parse("1.2.3-beta.2").compareTo(parse("1.2.3-beta.10")) < 0);
        assertTrue(parse("1.2.3-rc.2").compareTo(parse("1.2.3-rc.10")) < 0);
    }

    // ---- equals / hashCode / compareTo / toString contract ------------------------------------

    @Test
    void compareToEqualsHashCodeAndToStringAgree() throws Exception {
        UpdateVersion a = parse("1.2.3-rc.4");
        UpdateVersion b = parse("1.2.3-rc.4");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(0, a.compareTo(b));
        assertEquals(a.toString(), b.toString());
        assertNotEquals(a, parse("1.2.3"));
        assertNotEquals(a, parse("1.2.3-beta.4"));
        assertNotEquals(a, parse("1.2.3-rc.5"));
        assertNotEquals(a, parse("1.2.4-rc.4"));
        assertNotEquals(a, "1.2.3-rc.4");
    }

    @Test
    void compareToIsSymmetricInSign() throws Exception {
        List<String> texts = List.of("0.0.0", "1.2.3-alpha.1", "1.2.3-beta.2", "1.2.3-rc.10", "1.2.3", "1.2.4");
        for (String left : texts) {
            for (String right : texts) {
                int forward = Integer.signum(parse(left).compareTo(parse(right)));
                int backward = Integer.signum(parse(right).compareTo(parse(left)));
                assertEquals(-forward, backward, left + " vs " + right);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0", "1.2.3", "99999.99999.99999", "1.2.3-alpha.0", "1.2.3-beta.99", "1.2.3-rc.5"})
    void toStringRoundTripsAcceptedInput(String text) throws Exception {
        assertEquals(text, parse(text).toString());
    }

    // ---- rejections --------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"01.2.3", "1.02.3", "1.2.03", "00.0.0", "1.2.3-alpha.01", "1.2.3-rc.00"})
    void leadingZerosRejected(String text) {
        rejects(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100000.1.1", "1.100000.1", "1.1.100000", "1.2.3-alpha.100000"})
    void componentOverUpperBoundRejected(String text) {
        rejects(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.2.3+build",
        "1.2.3-alpha.1+build",
        "1.2.3-gamma.1",
        "1.2.3-rc",
        "1.2.3-alpha",
        "1.2.3-rc.1.2"
    })
    void buildMetadataAndUnknownLabelsRejected(String text) {
        rejects(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.2", "1.2.", ".1.2", "", "...", "1..2", "1.2.3."})
    void missingComponentsRejected(String text) {
        rejects(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3-ALPHA.1", "1.2.3-RC.1", " 1.2.3", "1.2.3 ", "1 .2.3", "1.2.3\n", "1.2.3\t"})
    void uppercaseAndWhitespaceRejected(String text) {
        rejects(text);
    }

    @Test
    void nullRejected() {
        UpdateRejectedException e = rejects(null);
        assertNotNull(e.getMessage());
    }

    @Test
    void rejectionMessagesNeverEchoSuppliedInput() {
        assertRejectsWithoutEcho(MARKER + ".2.3");
        assertRejectsWithoutEcho("1.2.3-" + MARKER + ".1");
        assertRejectsWithoutEcho("1.2.3+" + MARKER);
    }
}
