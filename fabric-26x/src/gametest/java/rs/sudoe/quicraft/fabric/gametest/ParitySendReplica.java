// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import rs.sudoe.quicraft.core.multistream.HeldChunkSet;
import rs.sudoe.quicraft.core.multistream.ShardHash;
import rs.sudoe.quicraft.core.multistream.StreamSequencer;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;

/**
 * A copy of the two private SessionSendDriver helpers the parity planner needs, because the planner
 * must place records exactly as the production sender does: {@code effectiveFence} and
 * {@code streamFor} (SessionSendDriver:1665-1700). Keep it byte-for-byte equivalent; the planner's
 * guard rejects any fence this class does not know, so a new fence cannot slip through silently.
 * To be replaced by the real helpers once core exposes them.
 */
final class ParitySendReplica {
    /** The first chunk-shard stream index (SessionSendDriver.FIRST_CHUNK). */
    static final int FIRST_CHUNK = 2;

    private ParitySendReplica() {}

    /** SessionSendDriver.effectiveFence. */
    static Fence effectiveFence(HeldChunkSet held, Fence fence, int chunkX, int chunkZ) {
        if (fence == Fence.CHUNK_INSTALL) {
            return held.install(chunkX, chunkZ) ? Fence.CHUNK_ACK : Fence.CHUNK;
        }
        if (fence == Fence.CHUNK_FORGET) {
            held.forget(chunkX, chunkZ);
            return Fence.CHUNK_ACK;
        }
        return fence;
    }

    /** SessionSendDriver.streamFor; throws for a fence it does not know (the guard). */
    static int streamFor(StreamSequencer sequencer, int shards, Fence fence, int chunkX, int chunkZ) {
        switch (fence) {
            case REALTIME:
                return 1;
            case ACK_TRAILING:
                return sequencer.ackIndex();
            case CHUNK_ACK:
            case CHUNK:
                return FIRST_CHUNK + ShardHash.shardOf(chunkX, chunkZ, sequencer.epoch(), shards);
            case ORDINARY_CONTROL:
            case CONTROL_FENCE:
            case GLOBAL:
            case VIEW:
                return 0;
            default:
                throw new AssertionError("send replica does not know fence " + fence
                        + "; compare SessionSendDriver.streamFor before trusting any parity result");
        }
    }
}
