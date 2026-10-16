// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/** Byte-level reviewed extractor over uncompressed packet-id-plus-body bytes (13.7-13.8). */
public interface Extractor {
    /** Extract chunk coordinates; malformed or unsupported input must fail, not guess. */
    Position extract(byte[] packetIdAndBody);
    /** Signed chunk coordinates. */
    final class Position {
        public final int chunkX, chunkZ;
        public Position(int chunkX, int chunkZ) { this.chunkX=chunkX; this.chunkZ=chunkZ; }
    }
}
