// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The initial congestion window default and its override, {@code quicraft.initialCwndPackets}
 * (docs/protocol.md §9): 64 packets unless the property selects 0 (quiche's native default) or a
 * value in 1..1000; a malformed or out-of-range value falls back to 64.
 *
 * <p>The tests mutate the JVM-wide system property, so they take the system-properties resource
 * lock to stay safe if JUnit parallel execution is ever enabled.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TransportConfigTest {

    private static final String PROPERTY = "quicraft.initialCwndPackets";

    @Test
    void defaultCwndIsSixtyFourWhenPropertyAbsent() {
        withProperty(null, () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void malformedPropertyAbcFallsBackToSixtyFour() {
        withProperty("abc", () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void emptyPropertyFallsBackToSixtyFour() {
        withProperty("", () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void integerOverflowPropertyFallsBackToSixtyFour() {
        withProperty("99999999999999999999", () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void negativeValuePropertyFallsBackToSixtyFour() {
        withProperty("-1", () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void aboveMaximumPropertyFallsBackToSixtyFour() {
        withProperty("1001", () -> assertEquals(64,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void explicitZeroPropertySelectsNativeDefault() {
        withProperty("0", () -> assertEquals(0,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void onePropertySelectsOne() {
        withProperty("1", () -> assertEquals(1,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void oneThousandPropertySelectsOneThousand() {
        withProperty("1000", () -> assertEquals(1000,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void whitespacePaddedPropertyIsTrimmed() {
        withProperty(" 32 ", () -> assertEquals(32,
                TransportConfig.builder().build().initialCongestionWindowPackets()));
    }

    @Test
    void newBuilderReadsPropertyChange() {
        withProperty("32", () -> {
            assertEquals(32, TransportConfig.builder().build().initialCongestionWindowPackets());

            System.setProperty(PROPERTY, "64");
            assertEquals(64, TransportConfig.builder().build().initialCongestionWindowPackets());
        });
    }

    @Test
    void builderExplicitValueOverridesSystemProperty() {
        withProperty("64", () -> {
            assertEquals(0, TransportConfig.builder()
                    .initialCongestionWindowPackets(0).build()
                    .initialCongestionWindowPackets());

            assertEquals(32, TransportConfig.builder()
                    .initialCongestionWindowPackets(32).build()
                    .initialCongestionWindowPackets());
        });
    }

    @Test
    void initialCongestionWindowPacketsRoundTripsThroughTheBuilder() {
        assertEquals(32, TransportConfig.builder().initialCongestionWindowPackets(32).build()
                .initialCongestionWindowPackets());
    }

    /**
     * Runs {@code assertion} with {@link #PROPERTY} set to {@code value} (cleared when
     * {@code null}) and restores the original value afterwards, so the JVM-wide property is left
     * exactly as it was found.
     */
    private static void withProperty(String value, Runnable assertion) {
        String original = System.getProperty(PROPERTY);
        try {
            if (value == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, value);
            }
            assertion.run();
        } finally {
            if (original != null) {
                System.setProperty(PROPERTY, original);
            } else {
                System.clearProperty(PROPERTY);
            }
        }
    }
}
