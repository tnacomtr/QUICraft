// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.LinkedHashMap;
import java.util.Map;

/** Associates BATCH_JOIN ids with all chunk stream sequences (13.19). */
public final class BatchJoinTracker {
    private final Map<Long,long[]> batches=new LinkedHashMap<Long,long[]>();
    /** Records the complete per-stream frontier for a batch before its join (13.19). */
    public synchronized void begin(long id,int streamCount){if(id<=0||streamCount<1||batches.containsKey(id))throw new IllegalArgumentException("batch");batches.put(id,new long[streamCount]);}
    /** Records a chunk sequence belonging to the batch (13.19). */
    public synchronized void chunk(long id,int stream,long sequence){long[] a=batches.get(id);if(a==null||stream<0||stream>=a.length||sequence<=0)throw new IllegalArgumentException("batch chunk");a[stream]=Math.max(a[stream],sequence);}
    /** Produces explicit dependencies on every covered chunk, then consumes the batch (13.19). */
    public synchronized Record.Dependency[] finish(long id){long[] a=batches.remove(id);if(a==null)throw new IllegalArgumentException("unknown batch");Record.Dependency[] d=new Record.Dependency[a.length];for(int i=0;i<a.length;i++){if(a[i]==0)throw new IllegalStateException("incomplete batch");d[i]=new Record.Dependency(i+2,a[i]);}return d;}
}
