// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The experiment-only initial congestion window setting (docs/protocol.md §9). */
class TransportConfigTest {
    @Test
    void initialCongestionWindowPacketsRoundTripsThroughTheBuilder() {
        assertEquals(32, TransportConfig.builder().initialCongestionWindowPackets(32).build()
                .initialCongestionWindowPackets());
    }
}
