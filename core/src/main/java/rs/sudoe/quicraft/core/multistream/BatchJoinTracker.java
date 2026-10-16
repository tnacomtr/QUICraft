// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.LinkedHashMap;
import java.util.Map;

/** Associates BATCH_JOIN ids with all chunk stream sequences (13.19). */
public final class BatchJoinTracker {
    private final Map<Long,java.util.Map<Integer,Long>> batches=new LinkedHashMap<Long,java.util.Map<Integer,Long>>();
    /** Records the complete per-stream frontier for a batch before its join (13.19). */
    public synchronized void begin(long id,int streamCount){if(id<=0||streamCount<1||batches.containsKey(id))throw new IllegalArgumentException("batch");batches.put(id,new LinkedHashMap<Integer,Long>());}
    /** Records a chunk sequence belonging to the batch (13.19). */
    public synchronized void chunk(long id,int stream,long sequence){java.util.Map<Integer,Long> a=batches.get(id);if(a==null||stream<0||sequence<=0)throw new IllegalArgumentException("batch chunk");Long old=a.get(stream);a.put(stream,Math.max(old==null?0:old,sequence));}
    /** Produces explicit dependencies on every covered chunk, then consumes the batch (13.19). */
    public synchronized Record.Dependency[] finish(long id){java.util.Map<Integer,Long> a=batches.remove(id);if(a==null)throw new IllegalArgumentException("unknown batch");java.util.List<Record.Dependency> d=new java.util.ArrayList<Record.Dependency>();for(java.util.Map.Entry<Integer,Long> e:a.entrySet())d.add(new Record.Dependency(e.getKey()+2,e.getValue()));return d.toArray(new Record.Dependency[d.size()]);}
}
