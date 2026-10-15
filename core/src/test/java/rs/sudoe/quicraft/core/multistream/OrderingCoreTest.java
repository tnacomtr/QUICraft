// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import rs.sudoe.quicraft.core.routing.Lane;
import static org.junit.jupiter.api.Assertions.*;

class OrderingCoreTest {
    private static final Lane C = Lane.CONTROL, R = Lane.REALTIME, W = Lane.WORLD;
    private static final long T = 10000000000L;
    private static Envelope e(Lane lane, long seq, long c, long r, long w) {
        return new Envelope(lane, Envelope.Kind.GAME, seq, new long[]{c,r,w}, 0, null,
                Envelope.Bundle.NONE, false);
    }
    private static void fails(Runnable action) {
        assertEquals(0x11, assertThrows(OrderingException.class, action::run).closeCode());
    }
    private static final class Harness {
        final List<ReceiverScheduler.Delivery> calls = new ArrayList<ReceiverScheduler.Delivery>();
        final List<Envelope> delivered = new ArrayList<Envelope>();
        final ReceiverScheduler scheduler = new ReceiverScheduler(d -> {
            calls.add(d);
            // At callback time every external predecessor must already be committed.
            delivered.addAll(d.records());
        });
        void drain(long now) {
            while (!calls.isEmpty()) {
                ReceiverScheduler.Delivery d = calls.remove(0);
                scheduler.applied(d.lane(), d.sequence(), now);
            }
        }
        void offer(Envelope e) { scheduler.offer(e, 0); drain(0); }
    }

    @Test void senderFencesAndSemanticPredecessors() {
        SenderSequencer s = new SenderSequencer();
        Envelope a = s.assign(R,0,"move",null,0);
        assertArrayEquals(new long[]{0,0,0}, a.dependencies());
        Envelope b = s.assign(W,0,null,new long[]{0,1,0},0);
        assertArrayEquals(new long[]{0,1,0}, b.dependencies());
        Envelope c = s.assign(C,0,null,null,0);
        assertArrayEquals(new long[]{0,1,1}, c.dependencies());
        assertArrayEquals(new long[]{1,1,0}, s.assign(R,0,null,null,0).dependencies());
        fails(() -> s.assign(W,0,null,new long[]{2,0,0},0));
        assertArrayEquals(new long[]{1,2,1}, s.end(0).dependencies());
        fails(() -> s.assign(C,0,null,null,0));
    }

    @Test void unsignedSequenceCrossesSignedBoundaryAndNeverWraps() throws Exception {
        SenderSequencer s = new SenderSequencer();
        Field f = SenderSequencer.class.getDeclaredField("assigned"); f.setAccessible(true);
        long[] a = (long[]) f.get(s);
        a[0] = Long.MAX_VALUE;
        assertEquals(Long.MIN_VALUE, s.assign(C,0,null,null,0).sequence());
        a[0] = -2L;
        assertEquals(-1L, s.assign(C,0,null,null,0).sequence());
        fails(() -> s.assign(C,0,null,null,0));
        a[0] = -4L;
        s.beginBundle(0); s.addBundleMember(0,0,null,false,0);
        assertEquals(-1L, s.endBundle(0,null,0,null,0).get(2).sequence());
    }

    @Test void handlerAcknowledgementIsTheOnlyCommitPoint() {
        Harness h = new Harness();
        h.scheduler.offer(e(C,1,0,0,0),0);
        h.scheduler.offer(e(R,1,1,0,0),0);
        assertArrayEquals(new long[3],h.scheduler.delivered());
        assertEquals(1,h.calls.size());
        fails(() -> h.scheduler.applied(C,2,0));
        h.drain(0);
        assertArrayEquals(new long[]{1,1,0},h.scheduler.delivered());
        fails(() -> h.scheduler.applied(R,1,0));
    }

    private static void seedBarrier(Harness h) {
        for (int i = 1; i <= 4; i++) h.offer(e(C,i,i-1,0,0));
        for (int i = 1; i <= 7; i++) h.offer(e(R,i,4,i-1,0));
        for (int i = 1; i <= 7; i++) h.offer(e(W,i,4,0,i-1));
        h.delivered.clear();
    }
    @Test void exactBarrierExampleWithEachPredecessorWithheld() {
        // Each lane remains ordered on the wire, even when another lane's bytes are withheld.
        for (boolean withholdSpawn : new boolean[]{false,true}) {
            Harness h = new Harness(); seedBarrier(h);
            Envelope spawn = e(C,5,4,8,7), move8 = e(R,8,4,7,0);
            if (withholdSpawn) h.offer(move8);
            else {
                // Withhold acceptance of move 8, not its bytes: lane bytes cannot skip 8.
                h.scheduler.offer(move8,0);
                h.scheduler.offer(spawn,0);
            }
            h.scheduler.offer(e(R,9,5,8,0),0);
            assertEquals(0,h.delivered.stream().filter(x -> x.sequence()==9).count());
            if (withholdSpawn) h.offer(spawn); else h.drain(0);
            assertEquals(9,h.scheduler.delivered()[1]);
            h.offer(e(C,6,5,9,7));
            h.offer(e(W,8,7,0,7));
            assertEquals(7,h.scheduler.delivered()[2]);
            h.offer(e(C,7,6,9,7));
            assertArrayEquals(new long[]{7,9,8},h.scheduler.delivered());
        }
        Harness h = new Harness(); seedBarrier(h);
        h.offer(e(C,5,4,7,7));
        h.offer(e(C,6,5,9,7)); // destroy withheld by both move 8 and 9
        h.offer(e(C,7,6,9,7));
        h.offer(e(W,8,7,0,7));
        assertEquals(5,h.scheduler.delivered()[0]);
        h.offer(e(R,8,5,7,0));
        assertEquals(5,h.scheduler.delivered()[0]);
        h.offer(e(R,9,5,8,0));
        assertArrayEquals(new long[]{7,9,8},h.scheduler.delivered());
    }

    @Test void all720ArrivalPermutationsEitherRejectLaneReorderingOrRespectTopology() {
        SenderSequencer s = new SenderSequencer();
        List<Envelope> input = new ArrayList<Envelope>();
        for (Lane lane : new Lane[]{C,R,W,R,C,W}) input.add(s.assign(lane,0,null,null,0));
        int[] totals = {0,0};
        permutations(input,0,totals);
        assertEquals(720,totals[0]);
        assertEquals(90,totals[1]); // 6!/(2! 2! 2!) lane-preserving wire interleavings
    }
    private static void permutations(List<Envelope> input, int start, int[] totals) {
        if (start == input.size()) {
            totals[0]++;
            long[] seen = new long[3]; boolean ordered = true;
            for (Envelope e : input) if (e.sequence() != ++seen[e.lane().ordinal()]) ordered = false;
            Harness h = new Harness();
            if (!ordered) {
                fails(() -> { for (Envelope e : input) h.offer(e); });
                return;
            }
            totals[1]++;
            for (Envelope e : input) h.offer(e);
            assertEquals(6,h.delivered.size());
            assertTopological(h.delivered);
            return;
        }
        for (int i=start;i<input.size();i++) {
            Collections.swap(input,start,i); permutations(input,start+1,totals);
            Collections.swap(input,start,i);
        }
    }
    private static void assertTopological(List<Envelope> records) {
        long[] d = new long[3];
        for (Envelope e : records) {
            for (Lane lane : Lane.values()) assertTrue(Long.compareUnsigned(e.dependency(lane),d[lane.ordinal()])<=0);
            assertEquals(d[e.lane().ordinal()]+1,e.sequence()); d[e.lane().ordinal()]++;
        }
    }

    @Test void seededSenderReceiverRoundTripsRandomLaneInterleavings() {
        Random random = new Random(0x51ced);
        for (int trial=0;trial<100;trial++) {
            SenderSequencer s = new SenderSequencer();
            List<List<Envelope>> lanes = Arrays.asList(new ArrayList<Envelope>(),new ArrayList<Envelope>(),new ArrayList<Envelope>());
            for (int i=0;i<100;i++) {
                Lane lane = Lane.values()[random.nextInt(3)];
                long[] semantic = s.assigned();
                for (int j=0;j<3;j++) if (random.nextBoolean()) semantic[j]=0;
                lanes.get(lane.ordinal()).add(s.assign(lane,0,i,semantic,0));
            }
            Harness h = new Harness();
            while (h.delivered.size()<100) {
                List<Envelope> lane = lanes.get(random.nextInt(3));
                if (!lane.isEmpty()) h.offer(lane.remove(0));
            }
            assertTopological(h.delivered);
            assertArrayEquals(s.assigned(),h.scheduler.delivered());
        }
    }

    @Test void bundleIsStagedAtomicallyAndAuxiliaryCannotUseProvisionalWatermark() {
        SenderSequencer s = new SenderSequencer();
        s.beginBundle(0); s.addBundleMember(2,2,"member",false,0);
        List<Envelope> bundle = s.endBundle(1,"open",1,"close",0);
        Harness h = new Harness();
        h.scheduler.offer(bundle.get(0),0);
        h.scheduler.offer(bundle.get(1),0);
        h.scheduler.offer(e(R,1,2,0,0),0);
        assertTrue(h.calls.isEmpty());
        h.scheduler.offer(bundle.get(2),0);
        assertEquals(3,h.calls.get(0).records().size());
        assertArrayEquals(new long[3],h.scheduler.delivered());
        fails(() -> h.scheduler.applied(C,2,0));
        h.drain(0);
        assertArrayEquals(new long[]{3,1,0},h.scheduler.delivered());
    }

    @Test void bundleExternalDependenciesUseCommittedWatermarks() {
        SenderSequencer s = new SenderSequencer();
        Envelope world = s.assign(W,0,null,null,0);
        s.beginBundle(0); s.addBundleMember(0,0,null,false,0);
        List<Envelope> b = s.endBundle(0,null,0,null,0);
        Harness h = new Harness();
        for (Envelope e : b) h.scheduler.offer(e,0);
        assertTrue(h.calls.isEmpty()); h.offer(world);
        assertArrayEquals(new long[]{3,0,1},h.scheduler.delivered());
    }

    @Test void senderBundleCapsAndIllegalInterleaving() {
        SenderSequencer s = new SenderSequencer(); s.beginBundle(0);
        for (int i=0;i<4096;i++) s.addBundleMember(2048,0,null,false,0);
        fails(() -> s.addBundleMember(0,0,null,false,0));
        assertEquals(4098,s.endBundle(0,null,0,null,0).size());
        s.beginBundle(0); s.addBundleMember(8*1024*1024,0,null,false,0);
        fails(() -> s.addBundleMember(1,0,null,false,0));
        fails(() -> s.addBundleMember(0,0,null,true,0));
        fails(() -> s.assign(R,0,null,null,0));
        fails(() -> s.end(0)); fails(() -> s.beginBundle(0));
        s.checkDeadlines(T-1); fails(() -> s.checkDeadlines(T));
    }

    @Test void receiverBundleCapsAndMalformedMarkers() {
        Harness h = new Harness();
        h.scheduler.offer(marked(1,Envelope.Bundle.OPEN),0);
        for (int i=0;i<4096;i++) {
            // Partial reservation is needed to fit a bundle this large in the general cap?
            // The stricter 1024 queue limit wins before the 4096 bundle ceiling.
            if (i==1023) {
                final int seq=i+2;
                fails(() -> h.scheduler.offer(marked(seq,Envelope.Bundle.MEMBER),0));
                break;
            }
            h.scheduler.offer(marked(i+2,Envelope.Bundle.MEMBER),0);
        }
        Harness bytes = new Harness(); bytes.scheduler.offer(marked(1,Envelope.Bundle.OPEN),0);
        bytes.scheduler.offer(marked(2,Envelope.Bundle.MEMBER),34,8*1024*1024,0);
        fails(() -> bytes.scheduler.offer(marked(3,Envelope.Bundle.MEMBER),34,1,0));
        Harness invalid = new Harness();
        fails(() -> invalid.scheduler.offer(marked(1,Envelope.Bundle.CLOSE),0));
        invalid.scheduler.offer(marked(1,Envelope.Bundle.OPEN),0);
        fails(() -> invalid.scheduler.offer(e(C,2,1,0,0),0));
        fails(() -> markedStateChange());
    }
    private static Envelope marked(long seq, Envelope.Bundle b) {
        return new Envelope(C,Envelope.Kind.GAME,seq,new long[]{seq-1,0,0},0,null,b,false);
    }
    private static void markedStateChange() {
        new Envelope(C,Envelope.Kind.GAME,1,new long[3],0,null,Envelope.Bundle.MEMBER,true);
    }

    @Test void auxiliarySlotsStopAt960AndAll64ControlSlotsRemainUsable() {
        Harness h = new Harness();
        for (int i=0;i<960;i++) h.scheduler.reservePartial(R,1,0);
        assertFalse(h.scheduler.auxiliaryCreditAvailable(1,1));
        fails(() -> h.scheduler.reservePartial(W,1,0));
        for (int i=0;i<64;i++) h.scheduler.reservePartial(C,1,0);
        assertEquals(1024,h.scheduler.pendingRecords());
        fails(() -> h.scheduler.reservePartial(C,1,0));
    }

    @Test void sharedAndControlByteReservesAtLimitAndLimitPlusOne() {
        Harness h = new Harness();
        ReceiverScheduler.Partial p = h.scheduler.reservePartial(W,16L*1024*1024,0);
        assertFalse(h.scheduler.auxiliaryCreditAvailable(1,0));
        fails(() -> h.scheduler.growPartial(p,1,0));
        ReceiverScheduler.Partial c = h.scheduler.reservePartial(C,1024*1024,0);
        assertEquals(17L*1024*1024,h.scheduler.pendingBytes());
        fails(() -> h.scheduler.growPartial(c,1,0));
        h.scheduler.releasePartial(c,0); h.scheduler.releasePartial(p,0);
        assertEquals(0,h.scheduler.pendingBytes()); assertEquals(0,h.scheduler.pendingRecords());
        Harness control = new Harness();
        control.scheduler.reservePartial(C,17L*1024*1024,0);
        assertFalse(control.scheduler.auxiliaryCreditAvailable(1,1));
        fails(() -> control.scheduler.reservePartial(C,1,0));
    }

    @Test void partialTransferRetainsExactlyOneSlotAndAccountsDecodedOutput() {
        Harness h = new Harness();
        ReceiverScheduler.Partial p = h.scheduler.reservePartial(R,1,0);
        h.scheduler.growPartial(p,33,1);
        h.scheduler.complete(p,e(R,1,1,0,0),100,66,2);
        assertEquals(1,h.scheduler.pendingRecords()); assertEquals(100,h.scheduler.pendingBytes());
        fails(() -> h.scheduler.releasePartial(p,2));
        h.offer(e(C,1,0,0,0));
        assertEquals(0,h.scheduler.pendingBytes());
        fails(() -> h.scheduler.offer(e(W,1,0,0,0),34,8*1024*1024+1,0));
        h.scheduler.offer(e(W,1,0,0,0),34+8*1024*1024,8*1024*1024,0);
        h.drain(0); assertEquals(0,h.scheduler.pendingBytes());
    }

    @Test void dependenciesPartialHandlerAndBundleTimeoutsAtExactlyTenSeconds() {
        Harness dependency = new Harness();
        dependency.scheduler.offer(e(R,1,1,0,0),0);
        // Other-lane progress must not restart REALTIME's head timer.
        dependency.scheduler.offer(e(W,1,0,0,0),T-2);
        dependency.drain(T-2);
        dependency.scheduler.checkDeadlines(T-1);
        fails(() -> dependency.scheduler.checkDeadlines(T));
        Harness partial = new Harness();
        ReceiverScheduler.Partial p=partial.scheduler.reservePartial(C,1,0);
        partial.scheduler.growPartial(p,1,T-1);
        fails(() -> partial.scheduler.checkDeadlines(T));
        Harness handler = new Harness(); handler.scheduler.offer(e(C,1,0,0,0),0);
        handler.scheduler.checkDeadlines(T-1);
        fails(() -> handler.scheduler.applied(C,1,T));
        Harness bundle = new Harness(); bundle.scheduler.offer(marked(1,Envelope.Bundle.OPEN),0);
        bundle.scheduler.checkDeadlines(T-1);
        fails(() -> bundle.scheduler.checkDeadlines(T));
    }

    @Test void blockedHeadTimerStartsAtPromotionNotArrival() {
        Harness h = new Harness();
        h.scheduler.offer(e(R,1,0,0,0),0);
        h.scheduler.offer(e(R,2,1,1,0),0);
        h.drain(T-1);
        h.scheduler.checkDeadlines(2*T-2);
        fails(() -> h.scheduler.checkDeadlines(2*T-1));
    }

    @Test void duplicateSkipAndWrongOwnLaneDependencyAreErrors() {
        Harness h=new Harness();
        fails(() -> h.scheduler.offer(e(R,2,0,1,0),0));
        fails(() -> h.scheduler.offer(e(R,1,0,1,0),0));
        h.scheduler.offer(e(R,1,99,0,0),0);
        fails(() -> h.scheduler.offer(e(R,1,99,0,0),0));
        fails(() -> h.scheduler.offer(e(R,2,99,0,0),0));
        Harness done = new Harness(); done.offer(e(W,1,0,0,0));
        fails(() -> done.scheduler.offer(e(W,1,0,0,0),0));
    }

    @Test void endIsAControlFullFenceAndTerminalOnlyOnApplication() {
        SenderSequencer s=new SenderSequencer();
        Envelope world=s.assign(W,0,null,null,0), end=s.end(0);
        Harness h=new Harness(); h.scheduler.offer(end,0);
        assertTrue(h.calls.isEmpty()); h.scheduler.offer(world,0);
        ReceiverScheduler.Delivery d=h.calls.remove(0);
        h.scheduler.applied(d.lane(),d.sequence(),0);
        assertFalse(h.scheduler.ended());
        h.drain(0); assertTrue(h.scheduler.ended());
        fails(() -> h.scheduler.offer(e(R,1,0,0,0),0));
    }

    @Test void synchronousAcknowledgementsDoNotRecurseThroughHandlerStack() {
        ReceiverScheduler[] ref=new ReceiverScheduler[1]; int[] depth={0}, max={0};
        ref[0]=new ReceiverScheduler(d -> {
            depth[0]++; max[0]=Math.max(max[0],depth[0]);
            ref[0].applied(d.lane(),d.sequence(),0); depth[0]--;
        });
        for (int i=1;i<=960;i++) ref[0].offer(e(R,i,1,i-1,0),0);
        ref[0].offer(e(C,1,0,0,0),0);
        assertEquals(1,max[0]); assertArrayEquals(new long[]{1,960,0},ref[0].delivered());
    }
}
