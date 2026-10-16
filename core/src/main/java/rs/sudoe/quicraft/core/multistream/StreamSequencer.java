// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;

/** Builds /3 sender headers at the single logical send ordering point (13.9, 13.18). */
public final class StreamSequencer {
    public enum Fence { ORDINARY_CONTROL, CONTROL_FENCE, GLOBAL, VIEW, REALTIME, CHUNK }
    public static final int MAX_BUNDLE_MEMBERS = 126;
    private final int n;
    private final long[] assigned;
    private final long[] epochs;
    private long global, view, control;

    /** Creates a direction-local sequencer for negotiated N (13.2, 13.18). */
    public StreamSequencer(int n) {
        LogicalStream.validateShardCount(n); this.n=n; assigned=new long[n+2]; epochs=new long[n+2];
    }
    /** Assigns a payload and sparse safe predecessors according to its fence class (13.9). */
    public Record assign(int stream, Fence fence, byte[] payload, boolean serverboundInteraction) {
        if (stream<0 || stream>=assigned.length || fence==null || payload==null) throw new IllegalArgumentException("record");
        long previous=assigned[stream];
        if(previous==Long.MAX_VALUE) throw new IllegalStateException("sequence overflow");
        long[] deps=new long[assigned.length]; deps[stream]=previous;
        if (global > 0) deps[0] = Math.max(deps[0], global);
        switch(fence) {
            case GLOBAL: case VIEW:
                for(int i=0;i<assigned.length;i++) deps[i]=Math.max(deps[i],assigned[i]);
                break;
            case CONTROL_FENCE:
                deps[0]=Math.max(deps[0],assigned[0]);
                deps[1]=Math.max(deps[1],assigned[1]);
                break;
            case REALTIME: deps[0]=Math.max(deps[0],Math.max(global,control)); break;
            case CHUNK: if(view>0) deps[0]=Math.max(deps[0],view); break;
            default: if(serverboundInteraction && stream==0) deps[1]=assigned[1];
        }
        for(int i=0;i<deps.length;i++) if(deps[i]>assigned[i]) throw new IllegalArgumentException("forward dependency");
        long seq=previous+1; assigned[stream]=seq;
        if(fence==Fence.GLOBAL) global=seq;
        if(fence==Fence.VIEW) { view=seq; control=seq; }
        if(fence==Fence.CONTROL_FENCE) control=seq;
        List<Record.Dependency> sparse=new ArrayList<Record.Dependency>();
        for(int i=0;i<deps.length;i++) if(i!=stream && deps[i]>0) sparse.add(new Record.Dependency(i,deps[i]));
        return new Record(stream,seq,epochs[stream],sparse,0,payload);
    }
    /** Starts a new epoch for the specified stream after an epoch transition (13.18). */
    public void nextEpoch(int stream) { if(stream<0||stream>=epochs.length) throw new IllegalArgumentException("stream"); if(epochs[stream]==Long.MAX_VALUE)throw new IllegalStateException("epoch overflow"); epochs[stream]++; }
    /** Returns an immutable-by-copy sequence frontier (13.9). */
    public long[] assigned() { return assigned.clone(); }
}
