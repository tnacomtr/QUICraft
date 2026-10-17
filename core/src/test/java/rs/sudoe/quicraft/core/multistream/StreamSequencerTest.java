// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Sender frontier construction for /3 (docs/protocol.md 13.4, 13.9, 13.15, 13.18, 13.19). */
class StreamSequencerTest {
    private static final byte[] NONE = new byte[0];
    private static final StreamSequencer.Fence ORDINARY = StreamSequencer.Fence.ORDINARY_CONTROL;
    private static final StreamSequencer.Fence CONTROL_FENCE = StreamSequencer.Fence.CONTROL_FENCE;
    private static final StreamSequencer.Fence GLOBAL = StreamSequencer.Fence.GLOBAL;
    private static final StreamSequencer.Fence VIEW = StreamSequencer.Fence.VIEW;
    private static final StreamSequencer.Fence REALTIME = StreamSequencer.Fence.REALTIME;
    private static final StreamSequencer.Fence CHUNK = StreamSequencer.Fence.CHUNK;

    /** Expected sparse dependencies as stream/sequence pairs, e.g. pairs(0, 3, 2, 1). */
    private static Map<Integer, Long> pairs(long... flat) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (int i = 0; i < flat.length; i += 2) {
            result.put((int) flat[i], flat[i + 1]);
        }
        return result;
    }

    private static Map<Integer, Long> deps(Record record) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (Record.Dependency dependency : record.dependencies()) {
            result.put(dependency.streamIndex(), dependency.sequence());
        }
        return result;
    }

    @Test void ordinaryControlWaitsOnlyForTheGlobalFrontier() {
        StreamSequencer sequencer = new StreamSequencer(2);
        assertEquals(pairs(), deps(sequencer.assign(2, CHUNK, NONE, false)));
        assertEquals(pairs(), deps(sequencer.assign(1, REALTIME, NONE, false)));
        Record global = sequencer.assign(0, GLOBAL, NONE, false);
        assertEquals(pairs(1, 1, 2, 1), deps(global));
        Record realtime = sequencer.assign(1, REALTIME, NONE, false);
        assertEquals(pairs(0, global.sequence()), deps(realtime));
        Record ordinary = sequencer.assign(0, ORDINARY, NONE, false);
        assertEquals(pairs(), deps(ordinary), "ordinary CONTROL must not wait for REALTIME");
    }

    @Test void controlFenceWaitsForPreviousControlAndRealtimeOnly() {
        StreamSequencer sequencer = new StreamSequencer(2);
        sequencer.assign(1, REALTIME, NONE, false);
        Record fence = sequencer.assign(0, CONTROL_FENCE, NONE, false);
        assertEquals(pairs(1, 1), deps(fence));
        assertEquals(fence.sequence(), sequencer.controlFence());
        assertEquals(pairs(), deps(sequencer.assign(2, CHUNK, NONE, false)), "chunks ignore the control fence");
        assertEquals(pairs(0, fence.sequence()), deps(sequencer.assign(1, REALTIME, NONE, false)));
    }

    @Test void viewGateWaitsForEveryStreamAndBecomesControlFrontier() {
        StreamSequencer sequencer = new StreamSequencer(2);
        sequencer.assign(3, CHUNK, NONE, false);
        sequencer.assign(1, REALTIME, NONE, false);
        sequencer.assign(0, ORDINARY, NONE, false);
        Record view = sequencer.assign(0, VIEW, NONE, false);
        assertEquals(pairs(1, 1, 3, 1), deps(view));
        assertEquals(view.sequence(), sequencer.viewFrontier());
        assertEquals(view.sequence(), sequencer.controlFence());
        assertEquals(pairs(0, view.sequence()), deps(sequencer.assign(1, REALTIME, NONE, false)));
    }

    @Test void chunkWaitsOnlyForTheViewGateNotControlOrRealtime() {
        StreamSequencer sequencer = new StreamSequencer(2);
        Record view = sequencer.assign(0, VIEW, NONE, false);
        sequencer.assign(1, REALTIME, NONE, false);
        sequencer.assign(0, ORDINARY, NONE, false);
        Record chunk = sequencer.assign(3, CHUNK, NONE, false);
        assertEquals(pairs(0, view.sequence()), deps(chunk));
        assertFalse(deps(chunk).containsKey(1), "chunks must never wait for REALTIME");
    }

    @Test void globalFenceDrainsEveryStreamAndAdvancesGlobalFrontier() {
        StreamSequencer sequencer = new StreamSequencer(2);
        sequencer.assign(1, REALTIME, NONE, false);
        sequencer.assign(2, CHUNK, NONE, false);
        sequencer.assign(3, CHUNK, NONE, false);
        Record global = sequencer.assign(0, GLOBAL, NONE, false);
        assertEquals(pairs(1, 1, 2, 1, 3, 1), deps(global));
        assertEquals(global.sequence(), sequencer.globalFrontier());
        assertEquals(pairs(0, global.sequence()), deps(sequencer.assign(2, CHUNK, NONE, false)));
    }

    @Test void epochTransitionCarriesOldEpochAndAdvancesDirectionOnce() {
        StreamSequencer sequencer = new StreamSequencer(1);
        Record transition = sequencer.assignEpochTransition(NONE);
        assertEquals(0, transition.epoch());
        assertEquals(1, sequencer.epoch());
        Record realtime = sequencer.assign(1, REALTIME, NONE, false);
        assertEquals(1, realtime.epoch());
        assertEquals(pairs(0, transition.sequence()), deps(realtime));
        Record chunk = sequencer.assign(2, CHUNK, NONE, false);
        assertEquals(1, chunk.epoch());
        assertEquals(pairs(0, transition.sequence()), deps(chunk));
        Record next = sequencer.assign(0, ORDINARY, NONE, false);
        assertEquals(transition.sequence() + 1, next.sequence(), "sequences never restart at an epoch");
    }

    @Test void serverboundDirectionRestrictsInteractionAndEpochs() {
        StreamSequencer serverbound = new StreamSequencer(1, true);
        serverbound.assign(1, REALTIME, NONE, false);
        Record interaction = serverbound.assign(0, ORDINARY, NONE, true);
        assertEquals(pairs(1, 1), deps(interaction));
        assertThrows(IllegalArgumentException.class, () -> serverbound.assignEpochTransition(NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamSequencer(1).assign(0, ORDINARY, NONE, true));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamSequencer(1, true).assign(1, REALTIME, NONE, true));
    }

    @Test void serverboundDirectionRejectsChunkFencesRoutesAndJoins() {
        // Chunk routes are clientbound only (13.18), the same way interaction and epoch are restricted.
        StreamSequencer serverbound = new StreamSequencer(1, true);
        assertThrows(IllegalArgumentException.class, () -> serverbound.assign(2, CHUNK, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> serverbound.assign(0, CHUNK, NONE, false));
        assertThrows(IllegalArgumentException.class,
                () -> serverbound.assign(2, StreamSequencer.Fence.ORDINARY_CONTROL, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> serverbound.assignJoin(2, NONE));
        assertEquals(pairs(), deps(serverbound.assign(1, REALTIME, NONE, false)), "non-chunk routes still work");
        assertEquals(pairs(), deps(new StreamSequencer(1).assign(2, CHUNK, NONE, false)),
                "clientbound chunk routes are unchanged");
    }

    @Test void serverboundRealtimeEncodesTheLaterOfGlobalAndControl() {
        StreamSequencer serverbound = new StreamSequencer(1, true);
        serverbound.assign(1, REALTIME, NONE, false);
        serverbound.assign(0, GLOBAL, NONE, false);
        Record control = serverbound.assign(0, CONTROL_FENCE, NONE, false);
        assertEquals(pairs(0, control.sequence()), deps(serverbound.assign(1, REALTIME, NONE, false)));
        Record global = serverbound.assign(0, GLOBAL, NONE, false);
        assertEquals(pairs(0, global.sequence()), deps(serverbound.assign(1, REALTIME, NONE, false)));
    }

    @Test void joinsDependOnCoveredFramesButNeverBecomeFrontiers() {
        StreamSequencer sequencer = new StreamSequencer(2);
        Record update = sequencer.assign(3, CHUNK, NONE, false);
        Record join = sequencer.assignJoin(2, NONE, new Record.Dependency(3, update.sequence()));
        assertEquals(1, join.sequence());
        assertEquals(pairs(3, 1), deps(join));
        assertEquals(0, sequencer.viewFrontier());
        assertEquals(pairs(), deps(sequencer.assign(0, ORDINARY, NONE, false)));
        assertEquals(pairs(), deps(sequencer.assign(1, REALTIME, NONE, false)));
        assertEquals(pairs(), deps(sequencer.assign(2, CHUNK, NONE, false)));
    }

    @Test void joinRejectsWrongKindForwardCoverageAndWrongRoute() {
        StreamSequencer sequencer = new StreamSequencer(2);
        assertThrows(IllegalArgumentException.class, () -> sequencer.assignJoin(1, NONE));
        assertThrows(IllegalArgumentException.class,
                () -> sequencer.assignJoin(2, NONE, new Record.Dependency(3, 5)));
        assertThrows(IllegalArgumentException.class, () -> sequencer.assign(1, CHUNK, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> sequencer.assign(2, REALTIME, NONE, false));
    }

    @Test void bundleAtTheMemberCapIsOneContiguousControlRun() {
        StreamSequencer sequencer = new StreamSequencer(1);
        List<byte[]> members = new ArrayList<byte[]>();
        StreamSequencer.Fence[] scopes = new StreamSequencer.Fence[StreamSequencer.MAX_BUNDLE_MEMBERS];
        for (int i = 0; i < scopes.length; i++) {
            members.add(NONE);
            scopes[i] = ORDINARY;
        }
        List<Record> bundle = sequencer.assignBundle(NONE, members, scopes, NONE);
        assertEquals(128, bundle.size());
        for (int i = 0; i < bundle.size(); i++) {
            assertEquals(i + 1, bundle.get(i).sequence());
            assertEquals(0, bundle.get(i).streamIndex());
        }
        assertEquals(pairs(), deps(bundle.get(1)), "members carry no explicit dependencies");
        assertEquals(bundle.get(127).sequence(), sequencer.controlFence());
    }

    @Test void bundleOverTheMemberCapIsRejectedWithoutMutation() {
        StreamSequencer sequencer = new StreamSequencer(1);
        List<byte[]> members = new ArrayList<byte[]>();
        StreamSequencer.Fence[] scopes = new StreamSequencer.Fence[127];
        for (int i = 0; i < scopes.length; i++) {
            members.add(NONE);
            scopes[i] = ORDINARY;
        }
        assertThrows(IllegalArgumentException.class, () -> sequencer.assignBundle(NONE, members, scopes, NONE));
        assertEquals(0, sequencer.assigned()[0]);
    }

    @Test void bundleExternalDependenciesAttachToOpeningRecordOnly() {
        StreamSequencer sequencer = new StreamSequencer(1);
        sequencer.assign(1, REALTIME, NONE, false);
        List<Record> bundle = sequencer.assignBundle(NONE,
                Collections.singletonList(NONE), new StreamSequencer.Fence[] {ORDINARY}, NONE,
                new Record.Dependency(1, 1));
        assertEquals(pairs(1, 1), deps(bundle.get(0)));
        assertEquals(pairs(), deps(bundle.get(1)));
        assertEquals(pairs(), deps(bundle.get(2)));
        assertEquals(pairs(0, bundle.get(2).sequence()),
                deps(sequencer.assign(1, REALTIME, NONE, false)));
    }

    @Test void bundleScopeRulePromotesChunkMembersToGlobal() {
        assertEquals(StreamSequencer.Fence.GLOBAL, StreamSequencer.bundleScope(ORDINARY, CHUNK));
        assertEquals(StreamSequencer.Fence.VIEW, StreamSequencer.bundleScope(ORDINARY, VIEW));
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE, StreamSequencer.bundleScope(ORDINARY));
        assertThrows(IllegalArgumentException.class, () -> StreamSequencer.bundleScope(REALTIME));
        assertThrows(IllegalArgumentException.class, () -> StreamSequencer.bundleScope((StreamSequencer.Fence) null));

        StreamSequencer sequencer = new StreamSequencer(2);
        sequencer.assign(2, CHUNK, NONE, false);
        sequencer.assign(3, CHUNK, NONE, false);
        List<Record> bundle = sequencer.assignBundle(NONE, Collections.singletonList(NONE),
                new StreamSequencer.Fence[] {CHUNK}, NONE);
        assertEquals(pairs(2, 1, 3, 1), deps(bundle.get(0)), "promoted bundle waits for every stream");
        assertEquals(bundle.get(2).sequence(), sequencer.globalFrontier());
    }

    @Test void bundleEncodedSizeBoundCountsEveryRecordHeaderAtExactlyAndOneOver() {
        assertEquals(2L * 1024 * 1024 + 128, StreamSequencer.MAX_BUNDLE_ENCODED_BYTES);
        // Opening (sequence 1), member (sequence 2) and closing (sequence 3) are ORDINARY CONTROL
        // with no dependency entries. Each record is VarInt(record_length) + kind + VarInt(sequence)
        // + VarInt(epoch) + VarInt(dependency_count) + body (13.4). The opening and closing records
        // are 5 bytes of header each (1-byte length prefix). The member body is about 2 MiB, so its
        // record_length takes a 4-byte VarInt and its header is 8 bytes.
        long memberHeader = 8;
        // The opener is kind 4 (13.4): its body adds VarInt(unit_records), 1 byte for 3 records, and
        // VarInt(unit_wire_bytes), 4 bytes for a total near B (above 2^21), so 5 bytes more than a kind 0 opener.
        long delimiterHeaders = 5 + 5 + 5;
        StreamSequencer exact = new StreamSequencer(1);
        byte[] atLimit = new byte[(int) (StreamSequencer.MAX_BUNDLE_ENCODED_BYTES - delimiterHeaders - memberHeader)];
        List<Record> accepted = exact.assignBundle(NONE, Collections.singletonList(atLimit),
                new StreamSequencer.Fence[] {ORDINARY}, NONE);
        assertEquals(3, accepted.size(), "wire size with headers equal to B is accepted");
        StreamSequencer over = new StreamSequencer(1);
        // Payload alone is B - 17 (below the bound), but payload plus headers is B + 1.
        byte[] headerOnlyOver = new byte[(int) (StreamSequencer.MAX_BUNDLE_ENCODED_BYTES - delimiterHeaders - memberHeader + 1)];
        assertThrows(IllegalArgumentException.class,
                () -> over.assignBundle(NONE, Collections.singletonList(headerOnlyOver),
                        new StreamSequencer.Fence[] {ORDINARY}, NONE));
        assertEquals(0, over.assigned()[0], "a rejected bundle assigns nothing");
    }

    @Test void bundleDecodingScratchBoundIsEightMebibytesAtExactlyAndOneOver() {
        assertEquals(8L * 1024 * 1024, StreamSequencer.MAX_BUNDLE_SCRATCH_BYTES);
        StreamSequencer sequencer = new StreamSequencer(1);
        List<Record> accepted = sequencer.assignBundle(NONE, Collections.singletonList(NONE),
                new StreamSequencer.Fence[] {ORDINARY}, NONE, StreamSequencer.MAX_BUNDLE_SCRATCH_BYTES);
        assertEquals(3, accepted.size(), "scratch equal to 8 MiB is accepted");
        assertThrows(IllegalArgumentException.class,
                () -> sequencer.assignBundle(NONE, Collections.singletonList(NONE),
                        new StreamSequencer.Fence[] {ORDINARY}, NONE,
                        StreamSequencer.MAX_BUNDLE_SCRATCH_BYTES + 1));
        assertEquals(3, sequencer.assigned()[0], "the rejected scratch bundle assigns nothing");
    }

    @Test void safeSupersetCoversChunksAndControlButNotRealtime() {
        StreamSequencer sequencer = new StreamSequencer(2);
        sequencer.assign(1, REALTIME, NONE, false);
        sequencer.assign(0, ORDINARY, NONE, false);
        sequencer.assign(3, CHUNK, NONE, false);
        assertEquals(pairs(0, 1, 3, 1), pairsOf(sequencer.safeSupersetDependencies()));
    }

    @Test void randomizedGeneratedEdgesAlwaysPointBackward() {
        Random random = new Random(9137L);
        int shards = 6;
        StreamSequencer sequencer = new StreamSequencer(shards);
        long[] assignedSoFar = new long[shards + 2];
        long expectedEpoch = 0;
        for (int i = 0; i < 4000; i++) {
            int roll = random.nextInt(100);
            if (roll < 3) {
                Record transition = sequencer.assignEpochTransition(NONE);
                checkBackward(transition, assignedSoFar);
                assertEquals(expectedEpoch, transition.epoch());
                expectedEpoch++;
                assignedSoFar[0]++;
                assertEquals(assignedSoFar[0], transition.sequence());
            } else if (roll < 15) {
                int covered = random.nextInt(shards + 2);
                if (assignedSoFar[covered] == 0) {
                    continue;
                }
                long seq = 1 + random.nextInt((int) assignedSoFar[covered]);
                Record join = sequencer.assignJoin(2 + random.nextInt(2), NONE,
                        new Record.Dependency(covered, seq));
                checkBackward(join, assignedSoFar);
                assignedSoFar[2]++;
                assertEquals(assignedSoFar[2], join.sequence());
            } else {
                StreamSequencer.Fence fence = StreamSequencer.Fence.values()[random.nextInt(6)];
                int stream;
                if (fence == REALTIME) {
                    stream = 1;
                } else if (fence == CHUNK) {
                    stream = 2 + random.nextInt(shards);
                } else {
                    stream = 0;
                }
                Record record = sequencer.assign(stream, fence, NONE, false);
                checkBackward(record, assignedSoFar);
                assignedSoFar[stream]++;
                assertEquals(assignedSoFar[stream], record.sequence());
                assertEquals(expectedEpoch, record.epoch());
            }
        }
    }

    private static void checkBackward(Record record, long[] assignedSoFar) {
        for (Record.Dependency dependency : record.dependencies()) {
            assertNotEquals(record.streamIndex(), dependency.streamIndex(), "own stream is implicit");
            assertTrue(dependency.sequence() <= assignedSoFar[dependency.streamIndex()],
                    "forward edge on stream " + dependency.streamIndex());
        }
    }

    private static Map<Integer, Long> pairsOf(Record.Dependency[] dependencies) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (Record.Dependency dependency : dependencies) {
            result.put(dependency.streamIndex(), dependency.sequence());
        }
        return result;
    }
}
