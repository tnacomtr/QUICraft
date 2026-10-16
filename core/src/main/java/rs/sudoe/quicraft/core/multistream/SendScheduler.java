// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import rs.sudoe.quicraft.core.profile.ServerProfile;

/** Dependency-aware local record selection before transport writes (13.20). */
public final class SendScheduler {
    public static final int QUANTUM_BYTES=64*1024;
    private final List<Item> queue=new ArrayList<Item>();
    private final int realtimeWeight,chunkWeight,controlWeight;
    private long clock;
    private int lastChunk=1, realtimeBurst;
    /** Initializes scheduler weights from the immutable server profile (13.20). */
    public SendScheduler(ServerProfile profile){if(profile==null)throw new IllegalArgumentException("profile");realtimeWeight=Math.max(1,profile.realtimeWeight);chunkWeight=Math.max(1,profile.chunkWeight);controlWeight=Math.max(1,profile.controlWeight);}
    /** Enqueues a pre-sequenced record and its encoded byte cost (13.20). */
    public synchronized void offer(Record record,int bytes){if(record==null||bytes<0)throw new IllegalArgumentException("item");queue.add(new Item(record,bytes,clock++));}
    /** Selects the next dependency-ready item, with age and stream-class weighting (13.20). */
    public synchronized Record poll(long[] delivered){
        if(delivered==null)return null;
        Item urgent=null;
        for(Item i:queue) if(ready(i.record,delivered) && i.record.streamIndex()==1 && (urgent==null||i.created<urgent.created)) urgent=i;
        Item best=null;
        for(Item i:queue){if(!ready(i.record,delivered))continue;
            boolean chunk=i.record.streamIndex()>=2;
            if(urgent!=null && realtimeBurst>=4 && i.record.streamIndex()==1) continue;
            if(urgent!=null && realtimeBurst>=4 && chunk) { if(best==null||chunkRank(i)<chunkRank(best))best=i; continue; }
            if(urgent!=null && realtimeBurst<4 && i.record.streamIndex()==1) { if(best==null||i.created<best.created)best=i; continue; }
            if(best==null||score(i)>score(best))best=i;
        }
        if(best==null)return null;
        queue.remove(best);
        if(best.record.streamIndex()==1)realtimeBurst++;else if(best.record.streamIndex()>=2){realtimeBurst=0;lastChunk=best.record.streamIndex();}
        return best.record;
    }
    /** Returns pending item count for bounded external queue management (13.20). */
    public synchronized int size(){return queue.size();}
    private long score(Item i){int stream=i.record.streamIndex();long weight=stream==1?realtimeWeight:stream>=2?chunkWeight:controlWeight;long age=clock-i.created;long priority=stream==1?3:stream>=2?2:1;return priority*1_000_000L+weight*1024L+Math.min(age,1_000_000L);}
    private int chunkRank(Item i){return (i.record.streamIndex()-lastChunk+6)%6;}
    private boolean ready(Record r,long[] d){if(d==null||r.streamIndex()>=d.length)return false;for(Record.Dependency x:r.dependencies())if(x.streamIndex()>=d.length||d[x.streamIndex()]<x.sequence())return false;return true;}
    private static final class Item{final Record record;final int bytes;final long created;Item(Record r,int b,long c){record=r;bytes=b;created=c;}}
}
