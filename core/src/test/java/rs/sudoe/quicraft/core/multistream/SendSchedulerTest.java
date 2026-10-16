// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Dependency-aware sender write policy (docs/protocol.md 13.20). */
class SendSchedulerTest {
    private static final IntPredicate ALL = stream -> true;
    private static final byte[] NONE = new byte[0];

    /** Profile with queue limits out of the way and the given class weights. */
    private static ServerProfile profile(int control, int realtime, int chunk) {
        return new ServerProfile(1, 1, 1024, .5, 1_000_000, 1L << 40, control, realtime, chunk);
    }

    /** Record on {@code stream} with sequence {@code seq} and flat (stream, sequence) pairs as dependencies. */
    private static Record rec(int stream, long seq, long... flat) {
        List<Record.Dependency> dependencies = new ArrayList<Record.Dependency>();
        for (int i = 0; i < flat.length; i += 2) {
            dependencies.add(new Record.Dependency((int) flat[i], flat[i + 1]));
        }
        return new Record(stream, seq, 0, dependencies, 0, NONE);
    }

    private static String name(SendScheduler.Selection selection) {
        return selection.record().streamIndex() + ":" + selection.record().sequence();
    }

    private static List<String> drain(SendScheduler scheduler, IntPredicate writable) {
        List<String> order = new ArrayList<String>();
        SendScheduler.Selection selection;
        while ((selection = scheduler.pollSelection(writable)) != null) {
            order.add(name(selection));
        }
        return order;
    }

    @Test void streamsAreFifoAndDependentHeadsWaitForTheirPrerequisites() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 2);
        scheduler.offer(rec(0, 2), 2);
        scheduler.offer(rec(2, 1, 0, 2), 1);
        assertEquals(Arrays.asList("0:1", "0:2", "2:1"), drain(scheduler, ALL));
        assertEquals(0, scheduler.size());
        assertEquals(0, scheduler.queuedBytes());
    }

    @Test void unofferedPrerequisiteHoldsTheDependentRecord() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(3, 1, 2, 1), 10);
        assertEquals(Collections.<String>emptyList(), drain(scheduler, ALL));
        scheduler.offer(rec(2, 1), 10);
        assertEquals(Arrays.asList("2:1", "3:1"), drain(scheduler, ALL));
    }

    @Test void urgentRealtimePromotesItsUnsentPrerequisiteChain() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(3, 1), 10);
        scheduler.offer(rec(2, 1), 10);
        scheduler.offer(rec(0, 1, 3, 1), 10);
        scheduler.offer(rec(1, 1, 0, 1), 10);
        List<String> order = drain(scheduler, ALL);
        assertEquals("3:1", order.get(0), "the chunk head needed through CONTROL is promoted first");
        assertEquals("0:1", order.get(1), "then the CONTROL fence the REALTIME record waits for");
        assertTrue(order.indexOf("1:1") > order.indexOf("0:1"));
        assertEquals(4, order.size());
    }

    @Test void chunksAlwaysProgressUnderSustainedRealtimeLoad() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        for (int i = 1; i <= 50; i++) {
            scheduler.offer(rec(2, i), 1000);
        }
        for (int i = 1; i <= 500; i++) {
            scheduler.offer(rec(1, i), 1000);
        }
        int chunks = 0;
        int run = 0;
        int longestRealtimeRun = 0;
        for (int i = 0; i < 300; i++) {
            SendScheduler.Selection selection = scheduler.pollSelection(ALL);
            assertNotNull(selection);
            if (selection.record().streamIndex() == 2) {
                chunks++;
                run = 0;
            } else {
                run++;
                longestRealtimeRun = Math.max(longestRealtimeRun, run);
            }
        }
        assertTrue(chunks >= 30, "chunks got " + chunks + " of 300 selections");
        assertTrue(longestRealtimeRun <= SendScheduler.MAX_REALTIME_BURST,
                "REALTIME burst was " + longestRealtimeRun);
    }

    @Test void independentChunkIsServedWithinTheSkipBoundUnderPromotedControlRealtimePairs() {
        // REALTIME weight far above CONTROL: each promoted CONTROL slice pushes the virtual time past
        // REALTIME's, so fairness alone would let the REALTIME record tie-win over the chunk forever.
        SendScheduler scheduler = new SendScheduler(profile(1, 1000, 1));
        scheduler.offer(rec(2, 1), 1000);
        // Every REALTIME record waits for its own CONTROL fence, so each pick is a promotion of that
        // CONTROL record followed by the REALTIME record, which used to keep the chunk out forever.
        for (int i = 1; i <= 200; i++) {
            scheduler.offer(rec(0, i), 1);
            scheduler.offer(rec(1, i, 0, i), 1000);
        }
        int position = -1;
        for (int i = 0; i < 40 && position < 0; i++) {
            SendScheduler.Selection selection = scheduler.pollSelection(ALL);
            assertNotNull(selection);
            if (selection.record().streamIndex() == 2) {
                position = i;
            }
        }
        assertTrue(position >= 0 && position <= SendScheduler.MAX_CHUNK_SKIPS,
                "independent chunk selected at position " + position + ", bound is "
                        + SendScheduler.MAX_CHUNK_SKIPS);
    }

    @Test void chunkShardsAreServedRoundRobin() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        for (int i = 1; i <= 50; i++) {
            scheduler.offer(rec(2, i), 100);
            scheduler.offer(rec(3, i), 100);
        }
        int previous = -1;
        for (int i = 0; i < 40; i++) {
            int stream = scheduler.pollSelection(ALL).record().streamIndex();
            assertNotEquals(previous, stream, "adjacent chunk selections must alternate shards");
            previous = stream;
        }
    }

    @Test void agedControlRecordProgressesUnderSustainedRealtimeLoad() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 1);
        for (int i = 1; i <= 200; i++) {
            scheduler.offer(rec(1, i), 1);
        }
        int position = -1;
        for (int i = 0; i < 40 && position < 0; i++) {
            if (name(scheduler.pollSelection(ALL)).equals("0:1")) {
                position = i;
            }
        }
        assertTrue(position >= 0 && position <= SendScheduler.AGE_LIMIT + 8,
                "CONTROL record selected at position " + position);
    }

    @Test void weightsShareBytesAndREALTIMEStaysWithinItsBurst() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        for (int i = 1; i <= 500; i++) {
            scheduler.offer(rec(2, i), 1000);
            scheduler.offer(rec(1, i), 1000);
        }
        int chunks = 0;
        int realtime = 0;
        for (int i = 0; i < 450; i++) {
            int stream = scheduler.pollSelection(ALL).record().streamIndex();
            if (stream == 2) {
                chunks++;
            } else {
                realtime++;
            }
        }
        assertTrue(chunks >= 45, "chunk share was " + chunks + " of 450");
        assertTrue(realtime >= 300, "REALTIME share was " + realtime + " of 450");
    }

    @Test void largeRecordIsWrittenInQuantumSlicesThatSumToItsSize() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 2_000_000);
        int slices = 0;
        long total = 0;
        SendScheduler.Selection selection;
        int expectedOffset = 0;
        while (true) {
            selection = scheduler.pollSelection(ALL);
            assertNotNull(selection);
            assertEquals(2_000_000, selection.bytes());
            assertEquals(expectedOffset, selection.offset());
            assertTrue(selection.length() <= SendScheduler.QUANTUM_BYTES);
            expectedOffset += selection.length();
            total += selection.length();
            slices++;
            if (selection.last()) {
                break;
            }
        }
        assertEquals(31, slices, "ceil(2 000 000 / 65 536)");
        assertEquals(2_000_000L, total);
        assertEquals(0, scheduler.size());
        assertEquals(0, scheduler.queuedBytes());
    }

    @Test void largeChunkRecordDoesNotBlockRealtimeForMoreThanOneQuantum() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(2, 1), 2_000_000);
        assertEquals("2:1", name(scheduler.pollSelection(ALL)), "first slice of the chunk record");
        scheduler.offer(rec(1, 1), 100);
        SendScheduler.Selection next = scheduler.pollSelection(ALL);
        assertEquals("1:1", name(next), "REALTIME goes before the next chunk slice");
        assertTrue(next.last());
    }

    @Test void equalVirtualTimesPreferRealtimeThenChunkThenControl() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 1);
        scheduler.offer(rec(2, 1), 1);
        scheduler.offer(rec(1, 1), 1);
        assertEquals("1:1", name(scheduler.pollSelection(ALL)));
        assertEquals("2:1", name(scheduler.pollSelection(ALL)));
        assertEquals("0:1", name(scheduler.pollSelection(ALL)));
    }

    @Test void quantumIsCappedAndOversizedOrMisplacedOffersAreRejected() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 2_000_000);
        SendScheduler.Selection selection = scheduler.pollSelection(ALL);
        assertEquals(2_000_000, selection.bytes());
        assertEquals(SendScheduler.QUANTUM_BYTES, selection.quantumBytes());
        assertFalse(selection.last());
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.offer(rec(0, 2), SendScheduler.MAX_RECORD_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> scheduler.offer(rec(0, 2), -1));
        assertThrows(IllegalArgumentException.class, () -> scheduler.offer(rec(0, 3), 1), "gap in stream 0");
        assertThrows(IllegalArgumentException.class, () -> scheduler.offer(rec(1, 1), 1, true),
                "bundle flag is CONTROL-only");
    }

    @Test void unwritableStreamsAreNeverSelected() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(2, 1), 1);
        scheduler.offer(rec(0, 1), 1);
        assertEquals("0:1", name(scheduler.pollSelection(s -> s == 0)));
        assertNull(scheduler.pollSelection(s -> s == 0), "the chunk stream is not writable");
        assertEquals("2:1", name(scheduler.pollSelection(ALL)));
    }

    @Test void bundleRunHoldsStreamZeroUntilTheClosingRecordIsOffered() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(0, 1), 1, true);
        scheduler.offer(rec(0, 2), 1, true);
        scheduler.offer(rec(1, 1), 1);
        scheduler.offer(rec(1, 2), 1);
        scheduler.offer(rec(2, 1), 1);
        scheduler.offer(rec(2, 2), 1);
        List<String> first = drain(scheduler, ALL);
        int open = first.indexOf("0:1");
        assertTrue(open >= 0 && first.size() > open + 1 && first.get(open + 1).equals("0:2"),
                "the bundle members must stay contiguous: " + first);
        assertTrue(scheduler.size() > 0, "REALTIME and chunk must wait while the bundle is open");
        scheduler.offer(rec(0, 3), 1);
        assertEquals("0:3", drain(scheduler, ALL).get(0));
    }

    @Test void randomizedScheduleNeverSendsARecordBeforeItsPrerequisites() {
        Random random = new Random(20261017L);
        StreamSequencer sequencer = new StreamSequencer(3);
        List<Record> records = new ArrayList<Record>();
        List<Boolean> continues = new ArrayList<Boolean>();
        for (int i = 0; i < 1500; i++) {
            int roll = random.nextInt(100);
            if (roll < 8) {
                Record record = sequencer.assignEpochTransition(NONE);
                records.add(record);
                continues.add(false);
            } else if (roll < 20) {
                long[] assigned = sequencer.assigned();
                int covered = random.nextInt(assigned.length);
                if (assigned[covered] == 0) {
                    continue;
                }
                records.add(sequencer.assignJoin(2 + random.nextInt(2), NONE,
                        new Record.Dependency(covered, 1 + random.nextInt((int) assigned[covered]))));
                continues.add(false);
            } else if (roll < 26) {
                int members = random.nextInt(4);
                List<byte[]> payloads = new ArrayList<byte[]>();
                StreamSequencer.Fence[] scopes = new StreamSequencer.Fence[members];
                for (int m = 0; m < members; m++) {
                    payloads.add(NONE);
                    scopes[m] = StreamSequencer.Fence.ORDINARY_CONTROL;
                }
                List<Record> bundle = sequencer.assignBundle(NONE, payloads, scopes, NONE);
                for (int m = 0; m < bundle.size(); m++) {
                    records.add(bundle.get(m));
                    continues.add(m < bundle.size() - 1);
                }
            } else {
                StreamSequencer.Fence[] fences = StreamSequencer.Fence.values();
                StreamSequencer.Fence fence = fences[random.nextInt(fences.length)];
                int stream = fence == StreamSequencer.Fence.REALTIME ? 1
                        : fence == StreamSequencer.Fence.CHUNK ? 2 + random.nextInt(3) : 0;
                records.add(sequencer.assign(stream, fence, NONE, false));
                continues.add(false);
            }
        }
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        long[] sent = new long[5];
        int offered = 0;
        int selected = 0;
        int spins = 0;
        while (selected < records.size()) {
            assertTrue(++spins < 2_000_000, "scheduler made no progress");
            if (offered < records.size() && random.nextInt(100) < 55) {
                Record record = records.get(offered);
                int size = random.nextInt(10) == 0 ? 100_000 + random.nextInt(400_000) : 1 + random.nextInt(5000);
                scheduler.offer(record, size, continues.get(offered));
                offered++;
                continue;
            }
            IntPredicate writable = offered == records.size() ? ALL
                    : stream -> random.nextInt(100) < 80;
            SendScheduler.Selection selection = scheduler.pollSelection(writable);
            if (selection == null) {
                continue;
            }
            Record record = selection.record();
            if (selection.offset() == 0) {
                assertEquals(sent[record.streamIndex()] + 1, record.sequence(), "per-stream order");
                for (Record.Dependency dependency : record.dependencies()) {
                    assertTrue(sent[dependency.streamIndex()] >= dependency.sequence(),
                            "sent " + record.streamIndex() + ":" + record.sequence()
                                    + " before prerequisite " + dependency.streamIndex() + ":" + dependency.sequence());
                }
            }
            if (selection.last()) {
                sent[record.streamIndex()] = record.sequence();
                selected++;
            }
        }
        assertEquals(0, scheduler.size());
    }
}
