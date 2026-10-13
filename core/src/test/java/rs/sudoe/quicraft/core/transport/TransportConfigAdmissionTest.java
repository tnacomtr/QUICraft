// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Admission settings on {@link TransportConfig} (docs/protocol.md §9).
 *
 * <p>Case 17 of the matrix (system-property parsing) is intentionally not tested here:
 * the class reads {@code quicraft.admission.*} once at class load and exposes no parse helper
 * ({@code limitProperty}/{@code retryModeProperty} are private), so the behavior cannot be driven
 * from a test without global JVM state.
 */
class TransportConfigAdmissionTest {

    @Test
    void defaultsMatchTheDocumentedAdmissionLimits() {
        TransportConfig config = TransportConfig.DEFAULT;
        assertEquals(200, config.globalAttemptsPerSecond());
        assertEquals(20, config.perIpAttemptsPerSecond());
        assertEquals(50, config.retryThresholdPerSecond());
        assertEquals(2048, config.maxOpen());
        assertEquals(32, config.maxOpenPerIp());
        assertEquals(8192, config.maxTrackedIps());
        assertEquals(4000, config.ingressInitialsPerSecond());
        assertEquals(200, config.ingressInitialsPerIpPerSecond());
        assertEquals(TransportConfig.RetryMode.ADAPTIVE, config.retryMode());
    }

    @Test
    void builderFallsBackToDefaultsForNonPositiveValues() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(0)
                .perIpAttemptsPerSecond(-1)
                .retryThresholdPerSecond(0)
                .maxOpen(0)
                .maxOpenPerIp(-5)
                .maxTrackedIps(0)
                .ingressInitialsPerSecond(0)
                .ingressInitialsPerIpPerSecond(-1)
                .build();

        assertEquals(200, config.globalAttemptsPerSecond());
        assertEquals(20, config.perIpAttemptsPerSecond());
        assertEquals(50, config.retryThresholdPerSecond());
        assertEquals(2048, config.maxOpen());
        assertEquals(32, config.maxOpenPerIp());
        assertEquals(8192, config.maxTrackedIps());
        assertEquals(4000, config.ingressInitialsPerSecond());
        assertEquals(200, config.ingressInitialsPerIpPerSecond());
    }

    @Test
    void builderFallsBackToDefaultsForValuesAboveTheMaximum() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(10_000_001)
                .perIpAttemptsPerSecond(10_000_001)
                .ingressInitialsPerSecond(10_000_001)
                .build();

        assertEquals(200, config.globalAttemptsPerSecond());
        assertEquals(20, config.perIpAttemptsPerSecond());
        assertEquals(4000, config.ingressInitialsPerSecond());
    }

    @Test
    void builderClampsRetryThresholdToTheGlobalBudget() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(30)
                .retryThresholdPerSecond(100)
                .build();

        assertEquals(30, config.globalAttemptsPerSecond());
        assertEquals(30, config.retryThresholdPerSecond(), "threshold above the global budget is clamped");
    }

    @Test
    void builderClampsTheDefaultThresholdWhenTheGlobalBudgetIsTiny() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(10)
                .build();

        assertEquals(10, config.retryThresholdPerSecond(), "the default threshold 50 also clamps to 10");
    }
}
