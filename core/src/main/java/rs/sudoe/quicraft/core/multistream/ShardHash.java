// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Fixed epoch-aware CHUNK shard assignment (docs/protocol.md 13.18). */
public final class ShardHash {
    private ShardHash() {}
    /** Return shard in [0,N); implementation must use the exact unsigned hash in 13.18. */
    public static int shardOf(int chunkX, int chunkZ, long epoch, int n) {
        LogicalStream.validateShardCount(n);
        if (epoch < 0 || epoch > 0x7fffffffL) throw new IllegalArgumentException("epoch");
        int h = chunkX * 0x9e3779b1;
        int z = chunkZ * 0x85ebca77;
        h ^= (z << 16) | (z >>> 16);
        h ^= (int) epoch * 0xc2b2ae3d;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (int) (Integer.toUnsignedLong(h) % n);
    }
}
