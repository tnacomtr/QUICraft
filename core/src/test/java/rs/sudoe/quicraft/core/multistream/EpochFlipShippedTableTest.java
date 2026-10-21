// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.assertAdmitted;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.assertViolation;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.dep;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.offer;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.rec;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.route;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * docs/protocol.md 13.18 with the SHIPPED protocol-775 table: login (49) and respawn (82) are the clientbound
 * epoch changes. The session sends them as epoch transitions, the receiver keeps new-epoch records opaque until
 * the transition has been applied and then advances exactly once per transition.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EpochFlipShippedTableTest {
    private static final int LOGIN = 49;
    private static final int RESPAWN = 82;
    /** custom_payload: an ordinary CONTROL/global clientbound id in the shipped table. */
    private static final int OTHER = 24;

    private static RoutingTable shipped() throws java.io.IOException {
        return RoutingTableLoader.loadResource(775);
    }

    private static RouteV2 clientbound(RoutingTable table, int id) {
        return table.routeV2For(new RouteKey(775, ProtocolState.PLAY, Direction.CLIENTBOUND, id));
    }

    private static long dependencyOf(Record record, int stream) {
        for (Record.Dependency dependency : record.dependencies()) {
            if (dependency.streamIndex() == stream) return dependency.sequence();
        }
        return 0;
    }

    @Test
    void theShippedTableMarksLoginAndRespawnAsEpochChangesAndNothingElseClientbound() throws Exception {
        RoutingTable table = shipped();
        assertTrue(clientbound(table, LOGIN).epochChange, "login (49)");
        assertTrue(clientbound(table, RESPAWN).epochChange, "respawn (82)");
        for (java.util.Map.Entry<RouteKey, RouteV2> entry : table.routesV2().entrySet()) {
            int id = entry.getKey().packetId();
            boolean expected = entry.getKey().direction() == Direction.CLIENTBOUND && (id == LOGIN || id == RESPAWN);
            assertEquals(expected, entry.getValue().epochChange, entry.getKey().toString());
        }
    }

    @Test
    void sendingLoginAndRespawnAdvancesTheSenderEpochAndTheClientApplyAdvancesItOnce() throws Exception {
        NRig.Config config = new NRig.Config();
        config.table = shipped();
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            assertEquals(0, rig.server.sendDriver().sendEpoch());
            SentPacket before = rig.server.send(ProtocolState.PLAY, OTHER, NRig.frame(OTHER, "before"));
            SentPacket login = rig.server.send(ProtocolState.PLAY, LOGIN, NRig.frame(LOGIN, "login"));
            assertEquals(1, rig.server.sendDriver().sendEpoch(), "login advanced the assignment epoch");
            SentPacket mid = rig.server.send(ProtocolState.PLAY, OTHER, NRig.frame(OTHER, "mid"));
            SentPacket respawn = rig.server.send(ProtocolState.PLAY, RESPAWN, NRig.frame(RESPAWN, "respawn"));
            // Same-dimension respawn twice in a row: each is its own transition (13.18).
            SentPacket respawnAgain = rig.server.send(ProtocolState.PLAY, RESPAWN, NRig.frame(RESPAWN, "again"));
            SentPacket after = rig.server.send(ProtocolState.PLAY, OTHER, NRig.frame(OTHER, "after"));
            for (SentPacket packet : new SentPacket[] {before, login, mid, respawn, respawnAgain, after}) {
                assertTrue(packet.isAssigned(), "assigned, not refused");
            }
            assertEquals(3, rig.server.sendDriver().sendEpoch());

            assertTrue(SessionRigSupport.await(() -> rig.clientGame.packets().size() == 6, 15_000),
                    "client got " + rig.clientGame.packets().size() + " of 6");
            int[] ids = {OTHER, LOGIN, OTHER, RESPAWN, RESPAWN, OTHER};
            List<NRig.Pkt> got = rig.clientGame.packets();
            for (int i = 0; i < ids.length; i++) {
                assertEquals(ids[i], got.get(i).id, "delivery order, packet " + i);
            }
            assertTrue(SessionRigSupport.await(() -> {
                try {
                    return NRig.onLoop(rig.client, rig.client::receiveEpoch) == 3L;
                } catch (Exception e) {
                    return false;
                }
            }, 15_000), "the client advanced to epoch 3, once per applied transition");
            assertFalse(rig.clientGame.closed.isDone() || rig.serverGame.closed.isDone(), "no close");
        }
    }

    @Test
    void theReceiverKeepsNewEpochRecordsOpaqueUntilEachShippedTransitionIsApplied() throws Exception {
        RoutingTable table = shipped();
        RouteV2 login = clientbound(table, LOGIN);
        RouteV2 respawn = clientbound(table, RESPAWN);
        ReceiveRoute loginRoute = new ReceiveRoute(ReceiveRoute.Lane.CONTROL, login.dependency, null,
                login.epochChange, false);
        ReceiveRoute respawnRoute = new ReceiveRoute(ReceiveRoute.Lane.CONTROL, respawn.dependency, null,
                respawn.epochChange, false);

        StreamSequencer sender = new StreamSequencer(2);
        Record transition1 = sender.assignEpochTransition(new byte[] {1});
        Record next1 = sender.assign(LogicalStream.REALTIME, StreamSequencer.Fence.REALTIME, new byte[] {1}, false);
        Record transition2 = sender.assignEpochTransition(new byte[] {1});
        Record next2 = sender.assign(LogicalStream.REALTIME, StreamSequencer.Fence.REALTIME, new byte[] {1}, false);
        assertEquals(0, transition1.epoch(), "a transition carries the old epoch");
        assertEquals(1, next1.epoch());
        assertEquals(transition1.sequence(), dependencyOf(next1, LogicalStream.CONTROL),
                "later records depend on the transition");
        assertEquals(1, transition2.epoch());
        assertEquals(2, next2.epoch());

        StreamReceiver receiver = new StreamReceiver(2, (s, r, route) -> true, () -> 0L);
        // The new-epoch record arrives first and stays opaque.
        assertAdmitted(receiver, next1);
        assertEquals(-1, receiver.nextToEnqueue(), "held until its transition is applied");
        assertAdmitted(receiver, transition1);
        assertEquals(LogicalStream.CONTROL, receiver.nextToEnqueue());
        assertNull(receiver.validateEligible(LogicalStream.CONTROL, loginRoute));
        receiver.enqueued(LogicalStream.CONTROL, transition1.sequence());
        assertEquals(-1, receiver.nextToEnqueue(), "enqueued is not applied: still opaque");
        assertEquals(0, receiver.epoch());
        receiver.applied(LogicalStream.CONTROL, transition1.sequence());
        assertEquals(1, receiver.epoch(), "exactly one advance");
        assertEquals(LogicalStream.REALTIME, receiver.nextToEnqueue());
        assertNull(receiver.validateEligible(LogicalStream.REALTIME, route(LogicalStream.REALTIME)));
        receiver.enqueued(LogicalStream.REALTIME, next1.sequence());
        receiver.applied(LogicalStream.REALTIME, next1.sequence());

        // Second transition (a respawn right after): epoch 2 records are opaque until it applies, and a
        // stale epoch 0 record is refused.
        assertAdmitted(receiver, next2);
        assertAdmitted(receiver, transition2);
        assertViolation(offer(receiver, rec(2, 1, 0)));
        assertEquals(LogicalStream.CONTROL, receiver.nextToEnqueue());
        assertNull(receiver.validateEligible(LogicalStream.CONTROL, respawnRoute));
        receiver.enqueued(LogicalStream.CONTROL, transition2.sequence());
        assertEquals(1, receiver.epoch());
        receiver.applied(LogicalStream.CONTROL, transition2.sequence());
        assertEquals(2, receiver.epoch());
        assertEquals(LogicalStream.REALTIME, receiver.nextToEnqueue());
        assertNull(receiver.pendingViolation());
        // A record two epochs ahead is a violation, never opaque-held.
        assertViolation(offer(receiver, rec(2, 1, 4, dep(LogicalStream.CONTROL, transition2.sequence()))));
    }
}
