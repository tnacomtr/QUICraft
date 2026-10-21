// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.transport.LaneLoopback;

/**
 * End-to-end /3 sessions over loopback QUIC with the synthetic ACK-lane table (13.21): the ACK stream is the sixth
 * server-opened stream, carries 4 and 11, and delivers them in TCP-equivalent order behind earlier block updates.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NStreamSessionAckLaneLoopbackTest {
    private static final int CLOSE_ORDERING = 0x11;

    private static NRig.Config config(int shards) {
        NRig.Config config = new NRig.Config();
        config.shards = shards;
        config.table = AckLaneTables.table();
        return config;
    }

    private static int indexOf(List<NRig.Got> units, int packetId, String label) {
        for (int i = 0; i < units.size(); i++) {
            NRig.Pkt pkt = (NRig.Pkt) units.get(i).packets.get(0);
            if (pkt.id == packetId && pkt.label.endsWith(label)) {
                return i;
            }
        }
        return -1;
    }

    private static NRig.Got unit(List<NRig.Got> units, int packetId, String label) {
        int index = indexOf(units, packetId, label);
        assertTrue(index >= 0, packetId + ":" + label + " delivered: " + units);
        return units.get(index);
    }

    @Test
    void nEqualsFiveOpensSixServerStreamsIncludingTheAckStream() throws Exception {
        try (NRig rig = NRig.start(config(5))) {
            rig.activateBoth();
            SessionContext context = SessionContext.of(5);
            int serverOpened = 0;
            for (int i = 0; i < 8; i++) {
                QuicStreamChannel server = NRig.channel(rig.server, i);
                assertEquals(context.quicIdOf(i), server.streamId(), "server stream " + i);
                assertEquals(context.quicIdOf(i), NRig.channel(rig.client, i).streamId(), "client stream " + i);
                if (i >= 2) {
                    assertTrue(server.isLocalCreated(), "stream " + i + " is server-opened");
                    serverOpened++;
                }
            }
            assertEquals(6, serverOpened, "five shards and the ACK stream");
            assertEquals(21L, NRig.channel(rig.server, 7).streamId(), "ACK is QUIC id 1+4N");
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void everyNFromOneToFiveOpensTheAckStreamAtNPlusTwo() throws Exception {
        for (int n = 1; n <= 5; n++) {
            try (NRig rig = NRig.start(config(n))) {
                rig.activateBoth();
                QuicStreamChannel ack = NRig.channel(rig.server, n + 2);
                assertEquals(1L + 4L * n, ack.streamId(), "n=" + n);
                rig.server.send(ProtocolState.PLAY, AckLaneTables.BLOCK_ACK, NRig.frame(AckLaneTables.BLOCK_ACK, "a"));
                assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(AckLaneTables.BLOCK_ACK) == 1, 15_000),
                        "n=" + n + " " + rig.clientGame.units());
                NRig.Got got = unit(rig.clientGame.units(), AckLaneTables.BLOCK_ACK, "a");
                assertEquals(n + 2, got.stream, "the ACK packet travels on the ACK stream, n=" + n);
                assertEquals(Lane.CONTROL, got.lane);
            }
        }
    }

    @Test
    void anAckFollowsEveryEarlierBlockUpdateAndABlockEntityRecordFollowsTheAck() throws Exception {
        try (NRig rig = NRig.start(config(3))) {
            rig.activateBoth();
            // A big block update on its shard first, so the shard lags: the ACK must still come after it (TCP order).
            byte[] big = new byte[300_000];
            java.util.Arrays.fill(big, (byte) 'x');
            String bigLabel = new String(big, java.nio.charset.StandardCharsets.ISO_8859_1);
            rig.server.send(ProtocolState.PLAY, AckLaneTables.CHUNK_PLAIN,
                    AckLaneTables.chunkFrame(AckLaneTables.CHUNK_PLAIN, 3, 4, bigLabel),
                    AckLaneTables.chunkUncompressed(AckLaneTables.CHUNK_PLAIN, 3, 4));
            rig.server.send(ProtocolState.PLAY, AckLaneTables.CHUNK_PLAIN,
                    AckLaneTables.chunkFrame(AckLaneTables.CHUNK_PLAIN, -9, 2, "update2"),
                    AckLaneTables.chunkUncompressed(AckLaneTables.CHUNK_PLAIN, -9, 2));
            rig.server.send(ProtocolState.PLAY, AckLaneTables.BLOCK_ACK, NRig.frame(AckLaneTables.BLOCK_ACK, "ack1"));
            rig.server.send(ProtocolState.PLAY, AckLaneTables.BLOCK_ENTITY,
                    AckLaneTables.chunkFrame(AckLaneTables.BLOCK_ENTITY, 3, 4, "entity"),
                    AckLaneTables.chunkUncompressed(AckLaneTables.BLOCK_ENTITY, 3, 4));
            rig.server.send(ProtocolState.PLAY, AckLaneTables.BATCH_FINISHED,
                    NRig.frame(AckLaneTables.BATCH_FINISHED, "done"));
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.units().size() == 5, 20_000),
                    () -> "delivered " + rig.clientGame.units() + " " + rig.clientGame.closed.getNow(null));
            List<NRig.Got> units = rig.clientGame.units();
            int update1 = indexOf(units, AckLaneTables.CHUNK_PLAIN, bigLabel);
            int update2 = indexOf(units, AckLaneTables.CHUNK_PLAIN, "update2");
            int ack = indexOf(units, AckLaneTables.BLOCK_ACK, "ack1");
            int entity = indexOf(units, AckLaneTables.BLOCK_ENTITY, "entity");
            int finished = indexOf(units, AckLaneTables.BATCH_FINISHED, "done");
            assertTrue(update1 >= 0 && update2 >= 0 && ack >= 0 && entity >= 0 && finished >= 0, units.toString());
            assertTrue(ack > update1 && ack > update2, "the ACK is applied after every earlier block update: " + units);
            assertTrue(entity > ack, "block entity data never overtakes the pending ACK: " + units);
            assertTrue(finished > ack, "the ACK stream keeps its own order: " + units);
            assertEquals(5, units.get(ack).stream, "ACK stream index N+2 for N=3");
            assertEquals(5, units.get(finished).stream);
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void anOrdinaryRecordSentAfterAnAckDoesNotWaitForItsStream() throws Exception {
        try (NRig rig = NRig.start(config(2))) {
            rig.activateBoth();
            rig.server.send(ProtocolState.PLAY, AckLaneTables.BLOCK_ACK, NRig.frame(AckLaneTables.BLOCK_ACK, "ack"));
            rig.server.send(ProtocolState.PLAY, AckLaneTables.ORDINARY, NRig.frame(AckLaneTables.ORDINARY, "after"));
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.units().size() == 2, 15_000),
                    () -> rig.clientGame.units().toString());
            // Both arrive; the receiver imposes no order between them (the sequencer test proves the dependency).
            assertEquals(1, rig.clientGame.count(AckLaneTables.ORDINARY));
            assertEquals(1, rig.clientGame.count(AckLaneTables.BLOCK_ACK));
        }
    }

    // --- A raw server peer writes the ACK stream: the client's receive path is under test.

    private static QuicStreamChannel rawServerStream(NRig rig, int logicalIndex) throws Exception {
        QuicChannel connection = rig.net.serverConnection.get(10, TimeUnit.SECONDS);
        QuicStreamChannel stream = connection.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS);
        LaneLoopback.write(stream, AuxPreamble.encode(logicalIndex));
        return stream;
    }

    private static byte[] ackWire(int stream, long sequence, int packetId) {
        return RecordCodec.encodeRecord(new Record(stream, sequence, 0,
                Collections.<Record.Dependency>emptyList(), RecordCodec.KIND_GAME,
                SessionReadPumpTest.inner(new byte[] {(byte) packetId, 'a'})));
    }

    @Test
    void aRawPeersValidAckRecordIsDelivered() throws Exception {
        NRig.Config config = config(2);
        config.serverSession = false;
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            rawServerStream(rig, 2);
            rawServerStream(rig, 3);
            QuicStreamChannel ack = rawServerStream(rig, 4);
            LaneLoopback.write(ack, ackWire(4, 1, AckLaneTables.BLOCK_ACK));
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(AckLaneTables.BLOCK_ACK) == 1, 10_000),
                    () -> rig.clientGame.units() + " " + rig.clientGame.closed.getNow(null));
            assertEquals(4, rig.clientGame.units().get(0).stream);
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void aRawPeersNonAckPacketOnTheAckStreamClosesTheClientWith0x11() throws Exception {
        for (int packetId : new int[] {AckLaneTables.ORDINARY, AckLaneTables.CHUNK_PLAIN, AckLaneTables.INSTALL,
                AckLaneTables.REALTIME, 99}) {
            NRig.Config config = config(2);
            config.serverSession = false;
            try (NRig rig = NRig.start(config)) {
                rig.activateClient();
                rawServerStream(rig, 2);
                rawServerStream(rig, 3);
                QuicStreamChannel ack = rawServerStream(rig, 4);
                LaneLoopback.write(ack, ackWire(4, 1, packetId));
                assertEquals(CLOSE_ORDERING, rig.clientGame.closed.get(15, TimeUnit.SECONDS).code, "packet " + packetId);
                assertEquals(0, rig.clientGame.count(packetId), "packet " + packetId);
            }
        }
    }

    @Test
    void aRawPeersOversizeAckRecordClosesTheClientWith0x11() throws Exception {
        NRig.Config config = config(2);
        config.serverSession = false;
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            rawServerStream(rig, 2);
            rawServerStream(rig, 3);
            QuicStreamChannel ack = rawServerStream(rig, 4);
            byte[] filler = new byte[100];
            filler[0] = (byte) AckLaneTables.BLOCK_ACK;
            LaneLoopback.write(ack, RecordCodec.encodeRecord(new Record(4, 1, 0,
                    Collections.<Record.Dependency>emptyList(), RecordCodec.KIND_GAME,
                    SessionReadPumpTest.inner(filler))));
            assertEquals(CLOSE_ORDERING, rig.clientGame.closed.get(15, TimeUnit.SECONDS).code);
            assertEquals(0, rig.clientGame.count(AckLaneTables.BLOCK_ACK));
        }
    }

    @Test
    void aSecondAckStreamOrAnExtraStreamClosesTheClientWith0x11() throws Exception {
        NRig.Config config = config(2);
        config.serverSession = false;
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            rawServerStream(rig, 2);
            rawServerStream(rig, 3);
            rawServerStream(rig, 4);
            rawServerStream(rig, 4); // a second server-opened stream after the ACK stream: id 13, outside N+3
            assertEquals(CLOSE_ORDERING, rig.clientGame.closed.get(15, TimeUnit.SECONDS).code);
        }
    }

    @Test
    void anAckPreambleOnAShardStreamClosesTheClientWith0x11() throws Exception {
        NRig.Config config = config(2);
        config.serverSession = false;
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            rawServerStream(rig, 4); // id 1 is shard 0, but names the ACK index
            assertEquals(CLOSE_ORDERING, rig.clientGame.closed.get(15, TimeUnit.SECONDS).code);
        }
    }
}
