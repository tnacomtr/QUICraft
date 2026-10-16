// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import rs.sudoe.quicraft.core.profile.ServerProfile;

class StreamSenderPolicyTest {
    @Test void fencesAndEpochKeepSequencesConnectionScoped() {
        StreamSequencer s=new StreamSequencer(2);
        Record c=s.assign(0,StreamSequencer.Fence.CONTROL_FENCE,new byte[0],false);
        Record r=s.assign(1,StreamSequencer.Fence.REALTIME,new byte[0],false);
        assertEquals(c.sequence(),r.dependencies().get(0).sequence());
        Record ordinary=s.assign(2,StreamSequencer.Fence.CHUNK,new byte[0],false);
        assertTrue(ordinary.dependencies().isEmpty());
        s.nextEpoch(0);
        assertEquals(2,s.assign(0,StreamSequencer.Fence.ORDINARY_CONTROL,new byte[0],false).sequence());
    }
    @Test void joinTrackersExposeCoveredFrontiersAndBounds() {
        AckJoinTracker a=new AckJoinTracker(); a.add(1,10,100,new long[]{2,3});
        assertArrayEquals(new long[]{2,3},a.resolve(1,99));
        a.add(2,10,100,new long[]{1}); assertNull(a.resolve(2,100));
        BatchJoinTracker b=new BatchJoinTracker(); b.begin(1,2); b.chunk(1,0,4); b.chunk(1,1,7);
        assertEquals(2,b.finish(1).length);
        assertThrows(IllegalArgumentException.class,()->b.finish(1));
    }
    @Test void schedulerLetsChunksAdvanceAndPromotesPrerequisites() {
        ServerProfile p=new ServerProfile(1,1,1024,0.5,10,1024,1,8,1);
        SendScheduler q=new SendScheduler(p);
        Record prerequisite=new Record(0,1,0,Collections.<Record.Dependency>emptyList(),0,new byte[0]);
        Record urgent=new Record(1,1,0,Collections.singletonList(new Record.Dependency(0,1)),0,new byte[0]);
        q.offer(prerequisite,2); q.offer(urgent,2);
        assertEquals(0,q.poll(new long[]{0,0,0}).streamIndex());
        for(int i=1;i<=8;i++) q.offer(new Record(1,i,0,Collections.<Record.Dependency>emptyList(),0,new byte[0]),1);
        q.offer(new Record(2,1,0,Collections.<Record.Dependency>emptyList(),0,new byte[0]),1);
        boolean progressed=false;
        for(int i=0;i<8;i++) {Record next=q.poll(new long[]{100,100,100}); if(next!=null&&next.streamIndex()>=2)progressed=true;}
        assertTrue(progressed);
    }
    @Test void bundleMemberLimitIs126() { assertEquals(126,StreamSequencer.MAX_BUNDLE_MEMBERS); }
}
