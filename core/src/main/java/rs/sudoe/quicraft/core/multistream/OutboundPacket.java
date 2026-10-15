// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** One packet of an outbound bundle: its framed wire body and how big it was uncompressed. */
public final class OutboundPacket {
    private final byte[] frame;
    private final int uncompressedBytes;
    private final boolean changesStateCompressionOrActivation;

    /**
     * @param frame one Minecraft VarInt-length frame, as it goes into a GAME record
     * @param uncompressedBytes the packet's uncompressed size, counted against the 8 MiB bundle cap
     * @param changesStateCompressionOrActivation true for a packet that changes protocol state,
     *        compression or activation; invalid inside a bundle (13.15)
     */
    public OutboundPacket(byte[] frame, int uncompressedBytes, boolean changesStateCompressionOrActivation) {
        if (frame == null) {
            throw new IllegalArgumentException("frame");
        }
        this.frame = frame;
        this.uncompressedBytes = uncompressedBytes;
        this.changesStateCompressionOrActivation = changesStateCompressionOrActivation;
    }

    /** An ordinary packet whose uncompressed size is taken as the frame length. */
    public OutboundPacket(byte[] frame) {
        this(frame, frame == null ? 0 : frame.length, false);
    }

    public byte[] frame() {
        return frame;
    }

    public int uncompressedBytes() {
        return uncompressedBytes;
    }

    public boolean changesStateCompressionOrActivation() {
        return changesStateCompressionOrActivation;
    }
}
