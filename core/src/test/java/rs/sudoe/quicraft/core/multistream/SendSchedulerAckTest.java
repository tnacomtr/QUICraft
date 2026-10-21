// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.profile.ServerProfile;

/** The ACK stream is scheduled in the CONTROL class, never as a CHUNK stream and never as REALTIME (13.20, 13.21). */
class SendSchedulerAckTest {
    private static final IntPredicate ALL = stream -> true;
    private static final byte[] NONE = new byte[0];
    /** N=3: CONTROL 0, REALTIME 1, shards 2..4, ACK 5. */
    private static final int ACK = 5;

    private static ServerProfile profile(int control, int realtime, int chunk) {
        return new ServerProfile(1, 1, 1024, .5, 1_000_000, 1L << 40, control, realtime, chunk);
    }

    private static Record rec(int stream, long seq, long... flat) {
        List<Record.Dependency> dependencies = new ArrayList<Record.Dependency>();
        for (int i = 0; i < flat.length; i += 2) {
            dependencies.add(new Record.Dependency((int) flat[i], flat[i + 1]));
        }
        return new Record(stream, seq, 0, dependencies, 0, NONE);
    }

    private static List<Integer> drainStreams(SendScheduler scheduler, int limit) {
        List<Integer> order = new ArrayList<Integer>();
        SendScheduler.Selection selection;
        while (order.size() < limit && (selection = scheduler.pollSelection(ALL)) != null) {
            order.add(selection.record().streamIndex());
        }
        return order;
    }

    @Test
    void aReadyAckRecordIsNotQueuedBehindASaturatedChunkShard() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1), ACK);
        for (int i = 1; i <= 40; i++) {
            scheduler.offer(rec(2, i), 60_000);
        }
        scheduler.offer(rec(ACK, 1), 8);
        List<Integer> order = drainStreams(scheduler, 6);
        int position = order.indexOf(ACK);
        assertTrue(position >= 0 && position < 3, "the tiny ACK record goes out within the first picks: " + order);
    }

    @Test
    void anAckRecordWaitsForTheStreamsItDependsOnAndIsThenSent() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1), ACK);
        scheduler.offer(rec(2, 1), 1000);
        scheduler.offer(rec(ACK, 1, 2, 1), 8);
        scheduler.offer(rec(2, 2), 1000);
        List<Integer> order = drainStreams(scheduler, 10);
        assertEquals(3, order.size());
        assertTrue(order.indexOf(ACK) > order.indexOf(2), "trailing: the ACK follows the record it depends on");
        assertEquals(2, (int) order.get(0));
    }

    @Test
    void anAckHeadAgesLikeAControlHeadAndTakesTurnsWithStreamZero() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1), ACK);
        for (int i = 1; i <= 30; i++) {
            scheduler.offer(rec(0, i), 8);
            scheduler.offer(rec(ACK, i), 8);
        }
        List<Integer> order = drainStreams(scheduler, 60);
        assertEquals(60, order.size());
        for (int i = 0; i + 1 < order.size(); i += 2) {
            assertTrue(order.get(i) != order.get(i + 1).intValue(), "alternates while both are ready: " + order);
        }
    }

    @Test
    void anAckRecordNeverUsesTheRealtimeBurstOrTheChunkSkipCounters() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1), ACK);
        // Realtime and chunk load saturate; the ACK stream is CONTROL class and still progresses.
        for (int i = 1; i <= 100; i++) {
            scheduler.offer(rec(1, i), 1000);
            scheduler.offer(rec(2, i), 1000);
        }
        for (int i = 1; i <= 10; i++) {
            scheduler.offer(rec(ACK, i), 8);
        }
        List<Integer> order = drainStreams(scheduler, 80);
        int acks = 0;
        for (int stream : order) {
            if (stream == ACK) acks++;
        }
        assertTrue(acks >= 3, "the ACK stream progressed: " + acks + " of " + order.size());
    }

    @Test
    void withoutAnAckStreamTheSchedulerBehavesAsBefore() {
        SendScheduler scheduler = new SendScheduler(profile(1, 8, 1));
        scheduler.offer(rec(2, 1), 10);
        scheduler.offer(rec(0, 1), 10);
        assertNotNull(scheduler.pollSelection(ALL));
        assertEquals(1, scheduler.size());
    }
}
