// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link QuicServer#defaultSocketCount()}: the number of UDP sockets a listener binds unless a
 * caller asks for another (docs/protocol.md §11). No QUIC native is needed.
 */
class UdpSocketCountTest {
    private static final String PROPERTY = "quicraft.udpSockets";

    @Test
    void theDefaultIsOneEverywhereButLinux() {
        int expected = System.getProperty("os.name", "").startsWith("Linux")
                ? Math.min(Runtime.getRuntime().availableProcessors(), 16)
                : 1;
        assertEquals(expected, QuicServer.defaultSocketCount());
    }

    @Test
    void thePropertyOverridesTheCount() {
        assumeNoProperty();
        try {
            for (int count : new int[] {1, 3, 64}) {
                System.setProperty(PROPERTY, Integer.toString(count));
                assertEquals(count, QuicServer.defaultSocketCount(), "quicraft.udpSockets=" + count);
            }
        } finally {
            System.clearProperty(PROPERTY);
        }
    }

    @Test
    void anInvalidPropertyKeepsTheDefault() {
        assumeNoProperty();
        int expected = System.getProperty("os.name", "").startsWith("Linux")
                ? Math.min(Runtime.getRuntime().availableProcessors(), 16)
                : 1;
        try {
            for (String invalid : new String[] {"0", "65", "-1", "sockets", ""}) {
                System.setProperty(PROPERTY, invalid);
                assertEquals(expected, QuicServer.defaultSocketCount(), "quicraft.udpSockets=" + invalid);
            }
            // A valid value is still honoured after invalid ones (the property is read per call).
            System.setProperty(PROPERTY, "2");
            assertEquals(2, QuicServer.defaultSocketCount());
        } finally {
            System.clearProperty(PROPERTY);
        }
    }

    private static void assumeNoProperty() {
        assumeTrue(System.getProperty(PROPERTY) == null, "a " + PROPERTY + " system property is already set");
    }
}
