// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Fixed epoch-aware CHUNK shard assignment (docs/protocol.md 13.18). */
public final class ShardHash {
    private ShardHash() {}
    /** Return shard in [0,N); implementation must use the exact unsigned hash in 13.18. */
    public static int shardOf(int chunkX, int chunkZ, long epoch, int n) {
        throw new UnsupportedOperationException("13.18 hash implementation pending");
    }
}
