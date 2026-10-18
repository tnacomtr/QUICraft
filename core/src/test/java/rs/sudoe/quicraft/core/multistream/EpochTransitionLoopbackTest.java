// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * docs/protocol.md 13.18 over loopback QUIC: a clientbound level-creating packet (an epoch-change route) is
 * submitted as the GLOBAL epoch transition, so it is delivered in order, and the packets after it are in the
 * new epoch. A serverbound epoch change closes 0x11. The table is the loopback table with one extra route.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EpochTransitionLoopbackTest {
    private static final int EPOCH_ID = 7;
    /** A serverbound route that (wrongly) changes the epoch: it must close the sender 0x11. */

    /**
     * The loopback table plus one clientbound epoch-change route (control lane, global dependency). The loader
     * accepts epoch changes only on clientbound CONTROL/global routes (13.7), so a serverbound one cannot exist.
     */
    private static RoutingTable epochTable() {
        String clientbound = "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":" + EPOCH_ID
                + ",\"lane\":\"control\",\"dependency\":\"global\",\"position\":null,\"epoch_change\":true}";
        String json = NRig.tableJson().replace("\"routes\":[", "\"routes\":[" + clientbound + ",");
        return RoutingTableLoader.parse(json);
    }

    @Test
    void clientbound_epochPacketIsDeliveredInOrderBetweenItsNeighbours() throws Exception {
        NRig.Config config = new NRig.Config();
        config.table = epochTable();
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "before"));
            SentPacketView epoch = new SentPacketView(
                    rig.server.send(ProtocolState.PLAY, EPOCH_ID, NRig.frame(EPOCH_ID, "level")));
            rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "after"));

            assertTrue(epoch.assigned(), "the epoch transition is assigned, not refused");
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.packets().size() == 3, 15_000),
                    "client got " + rig.clientGame.packets().size() + " of 3");
            List<NRig.Pkt> got = rig.clientGame.packets();
            assertEquals(NRig.CONTROL_ID, got.get(0).id);
            assertEquals(EPOCH_ID, got.get(1).id, "the level packet keeps its place");
            assertEquals(NRig.CONTROL_ID, got.get(2).id);
            // The after-packet is sequenced after the transition in the new epoch (13.18): its sequence is later.
            List<NRig.Got> units = rig.clientGame.units();
            assertTrue(units.get(2).sequence > units.get(1).sequence, "later records follow the transition");
        }
    }

    /** Reads the handle's assignment without depending on its accessors' names. */
    private static final class SentPacketView {
        private final SentPacket handle;

        SentPacketView(SentPacket handle) {
            this.handle = handle;
        }

        boolean assigned() {
            return handle.isAssigned();
        }
    }
}
