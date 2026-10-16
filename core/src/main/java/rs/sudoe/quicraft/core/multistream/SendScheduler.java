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
    /** Initializes scheduler weights from the immutable server profile (13.20). */
    public SendScheduler(ServerProfile profile){if(profile==null)throw new IllegalArgumentException("profile");realtimeWeight=Math.max(1,profile.realtimeWeight);chunkWeight=Math.max(1,profile.chunkWeight);controlWeight=Math.max(1,profile.controlWeight);}
    /** Enqueues a pre-sequenced record and its encoded byte cost (13.20). */
    public synchronized void offer(Record record,int bytes){if(record==null||bytes<0)throw new IllegalArgumentException("item");queue.add(new Item(record,bytes,clock++));}
    /** Selects the next dependency-ready item, with age and stream-class weighting (13.20). */
    public synchronized Record poll(long[] delivered){Item best=null;for(Item i:queue){if(!ready(i.record,delivered))continue;if(best==null||score(i)>score(best))best=i;}if(best==null)return null;queue.remove(best);return best.record;}
    /** Returns pending item count for bounded external queue management (13.20). */
    public synchronized int size(){return queue.size();}
    private long score(Item i){int stream=i.record.streamIndex();long weight=stream==1?realtimeWeight:stream>=2?chunkWeight:controlWeight;long age=clock-i.created;return weight*1024L+Math.min(age,1_000_000L);}
    private boolean ready(Record r,long[] d){if(d==null||r.streamIndex()>=d.length)return false;for(Record.Dependency x:r.dependencies())if(x.streamIndex()>=d.length||d[x.streamIndex()]<x.sequence())return false;return true;}
    private static final class Item{final Record record;final int bytes;final long created;Item(Record r,int b,long c){record=r;bytes=b;created=c;}}
}
