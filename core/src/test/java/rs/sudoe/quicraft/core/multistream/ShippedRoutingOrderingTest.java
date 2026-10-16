// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.OrderingCoreTest.Harness;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.Route;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * The ordering of the shipped 26.1.2 routes (protocol 775, docs/protocol.md 13.7 to 13.11), driven
 * through the real table: each lane is looked up by packet id, the sender assigns sequences and
 * dependencies exactly as on the wire, and the receiver is withheld one predecessor at a time.
 *
 * <p>The packet ids are the ones {@code RoutingTableGameTest} pins to the live game, so these tests
 * use the same routing the game does. The per-route test covers every non-CONTROL entry of the table;
 * the named tests show the 13.8 invariants that justify the lane choices.
 */
class ShippedRoutingOrderingTest {
    private static final int PROTOCOL = 775;
    /** Clientbound play ids of the shipped table (see RoutingTableGameTest for the live names). */
    private static final int ADD_ENTITY = 1;
    private static final int MOVE_ENTITY_POS = 53;
    private static final int LEVEL_CHUNK_WITH_LIGHT = 45;
    private static final int BLOCK_UPDATE = 8;
    private static final int REMOVE_ENTITIES = 77;

    private static RoutingTable table() {
        return RoutingRegistry.forProtocol(PROTOCOL).orElseThrow(() -> new AssertionError("no table"));
    }

    private static Lane laneOf(int packetId) {
        return table().laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, packetId);
    }

    /** The delivered records as {@code LANE:sequence}, in delivery order. */
    private static List<String> order(Harness h) {
        List<String> out = new ArrayList<String>();
        for (Envelope e : h.delivered) {
            out.add(e.lane() + ":" + e.sequence());
        }
        return out;
    }

    /** 13.11 for entity movement: a REALTIME move after a CONTROL spawn waits for the spawn. */
    @Test void moveAfterSpawnIsHeldUntilTheSpawnIsDelivered() {
        assertEquals(Lane.CONTROL, laneOf(ADD_ENTITY));
        assertEquals(Lane.REALTIME, laneOf(MOVE_ENTITY_POS));
        SenderSequencer s = new SenderSequencer();
        Envelope spawn = s.assign(Lane.CONTROL, 1, "add_entity", null, 0);
        Envelope move = s.assign(Lane.REALTIME, 1, "move_entity_pos", null, 0);
        assertArrayEquals(new long[] {1, 0, 0}, move.dependencies());
        Harness h = new Harness();
        h.scheduler.offer(move, 0);
        assertTrue(h.delivered.isEmpty(), "the move must wait for the spawn it depends on");
        h.offer(spawn);
        assertEquals(Arrays.asList("CONTROL:1", "REALTIME:1"), order(h));
    }

    /** 13.8 and 13.11: a WORLD block update after a CONTROL chunk install waits for the install. */
    @Test void blockUpdateAfterChunkInstallIsHeldUntilTheInstallIsDelivered() {
        assertEquals(Lane.CONTROL, laneOf(LEVEL_CHUNK_WITH_LIGHT));
        assertEquals(Lane.WORLD, laneOf(BLOCK_UPDATE));
        SenderSequencer s = new SenderSequencer();
        Envelope install = s.assign(Lane.CONTROL, 1, "level_chunk_with_light", null, 0);
        Envelope update = s.assign(Lane.WORLD, 1, "block_update", null, 0);
        assertArrayEquals(new long[] {1, 0, 0}, update.dependencies());
        Harness h = new Harness();
        h.scheduler.offer(update, 0);
        assertTrue(h.delivered.isEmpty(), "the block update must wait for the chunk install");
        h.offer(install);
        assertEquals(Arrays.asList("CONTROL:1", "WORLD:1"), order(h));
    }

    /** 13.11: a CONTROL destroy after REALTIME moves cannot overtake the moves. */
    @Test void destroyAfterRealtimeMovesWaitsForEveryMove() {
        SenderSequencer s = new SenderSequencer();
        Envelope spawn = s.assign(Lane.CONTROL, 1, "add_entity", null, 0);
        Envelope move1 = s.assign(Lane.REALTIME, 1, "move_entity_pos", null, 0);
        Envelope move2 = s.assign(Lane.REALTIME, 1, "move_entity_pos", null, 0);
        Envelope destroy = s.assign(Lane.CONTROL, 1, "remove_entities", null, 0);
        assertArrayEquals(new long[] {1, 2, 0}, destroy.dependencies());
        Harness h = new Harness();
        h.offer(spawn);
        h.offer(destroy);
        assertEquals(Arrays.asList("CONTROL:1"), order(h), "the destroy waits for the moves");
        h.offer(move1);
        assertEquals(Arrays.asList("CONTROL:1", "REALTIME:1"), order(h),
                "the destroy still waits for move 2, which the destroy depends on");
        h.offer(move2);
        assertEquals(Arrays.asList("CONTROL:1", "REALTIME:1", "REALTIME:2", "CONTROL:2"), order(h));
    }

    /**
     * Every non-CONTROL route of the shipped table, one at a time: its lane predecessor is the
     * CONTROL fence before it, so the record stays held until that fence is delivered, whatever
     * the packet is. Covers every entry, not just the examples above.
     */
    @Test void everyShippedNonControlRouteWaitsForItsControlFence() {
        int checked = 0;
        for (Map.Entry<RouteKey, Route> entry : table().routes().entrySet()) {
            RouteKey key = entry.getKey();
            Lane lane = entry.getValue().lane();
            SenderSequencer s = new SenderSequencer();
            Envelope fence = s.assign(Lane.CONTROL, 1, "fence", null, 0);
            Envelope routed = s.assign(lane, 1, key.packetId(), null, 0);
            assertEquals(1L, routed.dependency(Lane.CONTROL), key.toString());
            Harness h = new Harness();
            h.scheduler.offer(routed, 0);
            assertTrue(h.delivered.isEmpty(), key + " must wait for its CONTROL fence");
            h.offer(fence);
            assertEquals(Arrays.asList("CONTROL:1", lane + ":1"), order(h), key.toString());
            checked++;
        }
        assertEquals(table().routes().size(), checked);
        assertTrue(checked > 0);
    }
}
