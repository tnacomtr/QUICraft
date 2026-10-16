// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks bounded ACK_JOIN frontiers and their covered update sequences (13.19). */
public final class AckJoinTracker {
    public static final int MAX_ENTRIES=128, MAX_BYTES=64*1024;
    public static final long DEADLINE_NANOS=10_000_000_000L;
    private final LinkedHashMap<Long, Pending> pending=new LinkedHashMap<Long, Pending>();
    private int bytes;
    /** Adds an acknowledgement join, rejecting count/byte overflow (13.19). */
    public synchronized void add(long id, int size, long deadline, long[] frontier) {
        if(id<=0||size<0||frontier==null||pending.containsKey(id)||pending.size()>=MAX_ENTRIES||bytes+size>MAX_BYTES) throw new IllegalStateException("ACK_JOIN capacity/invalid");
        pending.put(id,new Pending(size,deadline,frontier.clone())); bytes+=size;
    }
    /** Returns covered update frontier, or null when missing/expired (13.19). */
    public synchronized long[] resolve(long id,long now) { Pending p=pending.get(id); if(p==null||now>=p.deadline){drop(id);return null;} long[] result=p.frontier.clone();drop(id);return result; }
    /** Clears joins on state, epoch, or disconnect transition (13.19). */
    public synchronized void transition() { pending.clear(); bytes=0; }
    private void drop(long id){Pending p=pending.remove(id);if(p!=null)bytes-=p.size;}
    private static final class Pending {final int size;final long deadline;final long[] frontier;Pending(int s,long d,long[] f){size=s;deadline=d;frontier=f;}}
}
