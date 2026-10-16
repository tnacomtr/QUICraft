// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The F3 line says which transport carries the connection, and /3 only once its lanes are active. */
class DebugLineTest {
    @Test
    void aTcpConnectionSaysTcp() {
        assertEquals("TCP", DebugLine.label(false, null));
    }

    @Test
    void aQuicV2ConnectionSaysQuic() {
        assertEquals("QUIC", DebugLine.label(true, null));
    }

    @Test
    void aV3ConnectionSaysSingleStreamUntilItsLanesAreActive() {
        assertEquals("QUIC /3 (single stream)", DebugLine.label(true, V3Client.State.BINDING));
        assertEquals("QUIC /3 (single stream)", DebugLine.label(true, V3Client.State.ACTIVATING));
        assertEquals("QUIC /3 (3 streams)", DebugLine.label(true, V3Client.State.ACTIVE));
    }
}
