// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/**
 * The real client CHUNK shard check (13.7, 13.18): the table this build ships routes clientbound
 * PLAY 45 to the CHUNK lane, and the production shard validator accepts that record only on the
 * CHUNK stream its chunk position hashes to. The same record on any other stream closes the
 * session 0x11: another shard, CONTROL or REALTIME.
 */
class ClientChunkShardRouteTest {
    private static final int PROTOCOL = 775;
    private static final int SHARDS = 3;
    private static final int PID_CHUNK = 45;
    private static final int CHUNK_X = -7;
    private static final int CHUNK_Z = 8;
    private static final int CLOSE_ORDERING = 0x11;

    @BeforeAll
    static void faultsOff() {
        Faults.set();
    }

    @Test
    void theChunkRecordOnItsOwnShardIsDelivered() throws Exception {
        Harness harness = new Harness();
        harness.putChunk(ownShard());
        harness.drive();
        assertEquals(Collections.emptyList(), harness.codes, "the shard's own stream carries the chunk");
        assertEquals(Collections.singletonList(PID_CHUNK), harness.delivered, "the chunk reached the game");
    }

    @Test
    void theChunkRecordOnAnotherChunkStreamClosesTheSession() throws Exception {
        Harness harness = new Harness();
        harness.putChunk(2 + (ownShard() - 2 + 1) % SHARDS);
        harness.drive();
        assertEquals(Collections.singletonList(CLOSE_ORDERING), harness.codes,
                "a chunk on another shard closes 0x11");
        assertEquals(Collections.emptyList(), harness.delivered, "nothing was delivered");
    }

    @Test
    void theChunkRecordOnControlClosesTheSession() throws Exception {
        Harness harness = new Harness();
        harness.putChunk(LogicalStream.CONTROL);
        harness.drive();
        assertEquals(Collections.singletonList(CLOSE_ORDERING), harness.codes,
                "a CHUNK route on CONTROL closes 0x11");
        assertEquals(Collections.emptyList(), harness.delivered, "nothing was delivered");
    }

    @Test
    void theChunkRecordOnRealtimeClosesTheSession() throws Exception {
        Harness harness = new Harness();
        harness.putChunk(LogicalStream.REALTIME);
        harness.drive();
        assertEquals(Collections.singletonList(CLOSE_ORDERING), harness.codes,
                "a CHUNK route on REALTIME closes 0x11");
        assertEquals(Collections.emptyList(), harness.delivered, "nothing was delivered");
    }

    /** The CHUNK stream this position hashes to, for the record's epoch. */
    private static int ownShard() {
        return 2 + ShardHash.shardOf(CHUNK_X, CHUNK_Z, 0L, SHARDS);
    }

    /** One clientbound receive path: the shipped table, the production shard validator and the pump. */
    private static final class Harness {
        final long[] now = {0L};
        final SessionReadPumpTest.Streams source = new SessionReadPumpTest.Streams(SHARDS + 3, 1 << 20);
        final ReceiveSlots slots = new ReceiveSlots(SHARDS);
        final ClientReadPump pump = new ClientReadPump(SHARDS, slots, false, source, () -> now[0]);
        final List<Integer> codes = new ArrayList<Integer>();
        final List<Integer> delivered = new ArrayList<Integer>();
        final SessionReceiveDriver driver;

        Harness() {
            RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL)
                    .orElseThrow(() -> new AssertionError("this build ships no table for protocol " + PROTOCOL));
            Map<String, Extractor> extractors = new HashMap<String, Extractor>();
            for (String identifier : table.extractors().values()) {
                ExtractorRegistry.Decoder decoder = ExtractorRegistry.decoder(identifier);
                if (decoder.kind == ExtractorRegistry.Kind.SINGLE
                        || decoder.kind == ExtractorRegistry.Kind.JOIN) {
                    extractors.put(identifier, decoder);
                }
            }
            driver = new SessionReceiveDriver(SHARDS, pump, slots,
                    new NStreamSession.ShardValidator(SHARDS, Direction.CLIENTBOUND),
                    table::routeV2For, PROTOCOL, extractors, new Game(delivered), () -> now[0],
                    (code, reason) -> codes.add(code));
        }

        /** Puts one PLAY 45 record on {@code stream}, carrying the chunk position. */
        void putChunk(int stream) {
            source.put(stream, RecordCodec.encodeRecord(new Record(stream, 1L, 0L,
                    Collections.<Record.Dependency>emptyList(), RecordCodec.KIND_GAME,
                    SessionReadPumpTest.inner(packet()))));
        }

        /** Reads until the session closes or the chunk is delivered. */
        void drive() throws Exception {
            for (int i = 0; i < 30 && codes.isEmpty() && delivered.isEmpty(); i++) {
                driver.receive();
            }
        }

        /** The chunk packet: its id VarInt and the two chunk coordinates. */
        private static byte[] packet() {
            ByteBuffer buffer = ByteBuffer.allocate(9);
            buffer.put((byte) PID_CHUNK);
            buffer.putInt(CHUNK_X);
            buffer.putInt(CHUNK_Z);
            return buffer.array();
        }
    }

    /** A game that hands the packet bytes back, so the position extractor sees the real position. */
    private static final class Game implements LaneGame {
        private final List<Integer> delivered;

        Game(List<Integer> delivered) {
            this.delivered = delivered;
        }

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
                              Reservation reservation) {
            byte[] body = body(frame);
            int pid = body[0] & 0xff;
            if (pid != PID_CHUNK) {
                throw new IllegalStateException("unexpected packet id " + pid);
            }
            return new Decoded(Integer.valueOf(pid), ProtocolState.PLAY, pid, Envelope.Bundle.NONE, false,
                    0, body.length, body);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            for (Object packet : packets) {
                delivered.add((Integer) packet);
            }
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
        }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
        }

        /** The packet bytes of one frame: the VarInt length prefix without the body. */
        private static byte[] body(byte[] frame) {
            VarInt.Reader reader = new VarInt.Reader(CLOSE_ORDERING, "13.4", false);
            int offset = 0;
            try {
                while (!reader.done()) {
                    if (offset >= frame.length) {
                        throw new IllegalStateException("truncated frame");
                    }
                    reader.feed(frame[offset++] & 0xff);
                }
            } catch (MultistreamProtocolException e) {
                throw new IllegalStateException(e);
            }
            int length = reader.value();
            if (offset + length != frame.length) {
                throw new IllegalStateException("frame length " + length + " does not match the frame");
            }
            byte[] body = new byte[length];
            System.arraycopy(frame, offset, body, 0, length);
            return body;
        }
    }
}
