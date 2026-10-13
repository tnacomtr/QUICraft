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
    /** {@code TransportConfig.MAX_LIMIT}, the largest accepted numeric limit. */
    private static final int MAX_LIMIT = 10_000_000;

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
    void builderPropagatesEveryDistinctAdmissionSetting() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(1000)
                .perIpAttemptsPerSecond(40)
                .retryThresholdPerSecond(300)
                .maxOpen(5000)
                .maxOpenPerIp(60)
                .maxTrackedIps(9000)
                .ingressInitialsPerSecond(7000)
                .ingressInitialsPerIpPerSecond(400)
                .retryMode(TransportConfig.RetryMode.ALWAYS)
                .build();

        assertEquals(1000, config.globalAttemptsPerSecond());
        assertEquals(40, config.perIpAttemptsPerSecond());
        assertEquals(300, config.retryThresholdPerSecond());
        assertEquals(5000, config.maxOpen());
        assertEquals(60, config.maxOpenPerIp());
        assertEquals(9000, config.maxTrackedIps());
        assertEquals(7000, config.ingressInitialsPerSecond());
        assertEquals(400, config.ingressInitialsPerIpPerSecond());
        assertEquals(TransportConfig.RetryMode.ALWAYS, config.retryMode());
    }

    @Test
    void builderAcceptsEveryRetryModeAndNullFallsBackToTheDefault() {
        assertEquals(TransportConfig.RetryMode.ADAPTIVE,
                TransportConfig.builder().retryMode(TransportConfig.RetryMode.ADAPTIVE).build().retryMode());
        assertEquals(TransportConfig.RetryMode.ALWAYS,
                TransportConfig.builder().retryMode(TransportConfig.RetryMode.ALWAYS).build().retryMode());
        assertEquals(TransportConfig.RetryMode.NEVER,
                TransportConfig.builder().retryMode(TransportConfig.RetryMode.NEVER).build().retryMode());
        assertEquals(TransportConfig.RetryMode.ADAPTIVE,
                TransportConfig.builder().retryMode(null).build().retryMode(), "null means the default");
    }

    @Test
    void builderAcceptsEachNumericUpperBoundary() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(MAX_LIMIT)
                .perIpAttemptsPerSecond(MAX_LIMIT)
                .retryThresholdPerSecond(MAX_LIMIT)
                .maxOpen(MAX_LIMIT)
                .maxOpenPerIp(MAX_LIMIT)
                .maxTrackedIps(MAX_LIMIT)
                .ingressInitialsPerSecond(MAX_LIMIT)
                .ingressInitialsPerIpPerSecond(MAX_LIMIT)
                .build();

        assertEquals(MAX_LIMIT, config.globalAttemptsPerSecond());
        assertEquals(MAX_LIMIT, config.perIpAttemptsPerSecond());
        assertEquals(MAX_LIMIT, config.retryThresholdPerSecond());
        assertEquals(MAX_LIMIT, config.maxOpen());
        assertEquals(MAX_LIMIT, config.maxOpenPerIp());
        assertEquals(MAX_LIMIT, config.maxTrackedIps());
        assertEquals(MAX_LIMIT, config.ingressInitialsPerSecond());
        assertEquals(MAX_LIMIT, config.ingressInitialsPerIpPerSecond());
    }

    @Test
    void builderFallsBackToDefaultsForValuesAboveTheMaximum() {
        TransportConfig config = TransportConfig.builder()
                .globalAttemptsPerSecond(MAX_LIMIT + 1)
                .perIpAttemptsPerSecond(MAX_LIMIT + 1)
                .retryThresholdPerSecond(MAX_LIMIT + 1)
                .maxOpen(MAX_LIMIT + 1)
                .maxOpenPerIp(MAX_LIMIT + 1)
                .maxTrackedIps(MAX_LIMIT + 1)
                .ingressInitialsPerSecond(MAX_LIMIT + 1)
                .ingressInitialsPerIpPerSecond(MAX_LIMIT + 1)
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
