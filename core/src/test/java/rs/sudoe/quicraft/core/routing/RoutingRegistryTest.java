// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * The classpath lookup the {@code /3} selection and the advertisement both use
 * (docs/protocol.md 13.1, 13.7), over the routing fixtures in {@code core/src/test/resources}.
 */
class RoutingRegistryTest {
    /** The one valid fixture: {@code protocol-12345.json} with its mandatory-CONTROL set. */
    private static final int VALID = 12345;
    /** No resource at all for this protocol number. */
    private static final int ABSENT = 4242;

    @AfterEach
    void noFaults() {
        Faults.set();
    }

    @Test
    void returnsTheTableAndItsExactResourceDigest() throws Exception {
        Optional<RoutingTable> found = RoutingRegistry.forProtocol(VALID);
        assertTrue(found.isPresent(), "the fixture table must load");
        assertEquals(VALID, found.get().protocolNumber());
        // R is SHA-256 over the exact resource bytes (13.7), the same value the loader computes and
        // the same one the selection compares against the advertisement's routing member (13.1).
        assertEquals(RoutingTableLoader.loadResource(VALID).digestHex(), found.get().digestHex());
        assertEquals(64, found.get().digestHex().length());
    }

    @Test
    void cachesTheLoadedTable() {
        RoutingTable first = RoutingRegistry.forProtocol(VALID).get();
        assertSame(first, RoutingRegistry.forProtocol(VALID).get(),
                "the selection runs on every connect, so the table is loaded once");
    }

    @Test
    void anAbsentResourceIsAnEmptyAnswerAndIsCached() {
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(ABSENT));
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(ABSENT));
    }

    /**
     * 13.7 stores one resource per protocol number, so a fixture that declares another number is
     * unusable rather than a table for that other number.
     */
    @Test
    void aResourceDeclaringAnotherProtocolIsAnEmptyAnswer() {
        // protocol-763.json declares protocol 764.
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(763));
    }

    /** 13.5's must-use-CONTROL set is part of the load, so its absence makes the table unusable. */
    @Test
    void aTableWithoutItsMandatoryControlSetIsAnEmptyAnswer() {
        // protocol-9001.json has no protocol-9001-mandatory-control.json next to it.
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(9001));
    }

    @Test
    void aNegativeProtocolNumberHasNoTable() {
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(-1));
        assertEquals(Optional.empty(), RoutingRegistry.forProtocol(Integer.MIN_VALUE));
    }

    @Test
    void aFaultInTheSelectionStepThrowsAndIsNotCached() {
        Faults.set(Faults.V3_SELECT);
        assertThrows(IllegalStateException.class, () -> RoutingRegistry.forProtocol(VALID));
        Faults.set();
        assertTrue(RoutingRegistry.forProtocol(VALID).isPresent(),
                "the fault must not have been cached as an empty answer");
    }
}
