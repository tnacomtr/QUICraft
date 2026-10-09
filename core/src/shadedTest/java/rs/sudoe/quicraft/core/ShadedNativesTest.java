// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
