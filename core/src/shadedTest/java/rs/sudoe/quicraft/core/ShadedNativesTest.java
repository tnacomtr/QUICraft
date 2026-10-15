// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Runs with only the shaded core jar on the classpath. Proves that relocated Netty finds the
 * renamed quiche native for this platform inside the jar. The CI matrix runs it on every native
 * platform.
 */
class ShadedNativesTest {
    @Test
    void unshadedNettyIsNotOnTheClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.netty.util.Version"));
    }

    @Test
    void nettyIsRelocatedIntoTheShadedJar() throws Exception {
        Class<?> quic = Class.forName("rs.sudoe.quicraft.shaded.io.netty.handler.codec.quic.Quic");
        assertEquals(QuicSupport.class.getProtectionDomain().getCodeSource().getLocation(),
                quic.getProtectionDomain().getCodeSource().getLocation(),
                "relocated Netty must come from the same (shaded) jar as core");
    }

    @Test
    void quicheNativeLoadsFromTheShadedJar() {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC unavailable: " + QuicSupport.unavailabilityCause());
    }

    /**
     * The patched native registers its extra JNI method under the relocated class names, so the
     * relaxed loss threshold must work from the shaded jar exactly where Gradle built a patched
     * native for this platform (core/build.gradle.kts, quicraft.patchedNatives).
     */
    @Test
    void relaxedLossThresholdMatchesTheNativeBuiltForThisPlatform() {
        String expected = System.getProperty("quicraft.test.expectRelaxedLossThreshold");
        assertNotNull(expected, "run through Gradle");
        assertEquals(Boolean.parseBoolean(expected), QuicSupport.hasRelaxedLossThreshold());
    }

    /**
     * Same for the TLS exporter (natives/patches/netty/0004): the binding of the shaded jar is what
     * channel binding needs, and this is the only run that proves the relocated names resolve.
     */
    @Test
    void keyingMaterialExportMatchesTheNativeBuiltForThisPlatform() {
        String expected = System.getProperty("quicraft.test.expectKeyingMaterialExport");
        assertNotNull(expected, "run through Gradle");
        assertEquals(Boolean.parseBoolean(expected), QuicSupport.hasKeyingMaterialExport());
    }

    /**
     * Same for the maximum flow-control windows (natives/patches/netty/0005), which ship in the
     * same patched native as the exporter, so they share its expectation.
     */
    @Test
    void maxFlowControlWindowsMatchTheNativeBuiltForThisPlatform() {
        String expected = System.getProperty("quicraft.test.expectKeyingMaterialExport");
        assertNotNull(expected, "run through Gradle");
        assertEquals(Boolean.parseBoolean(expected), QuicSupport.hasMaxFlowControlWindows());
    }
}
