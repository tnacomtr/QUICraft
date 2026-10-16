// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The frame of a GAME record (docs/protocol.md 13.4) against the arithmetic of the game's length
 * prepender and compression handlers. Nothing here starts a game.
 */
class FrameCodecTest {
    private static final FrameCodec.Reserve ALLOW = (retained, total) -> { };

    private static byte[] packet(int id, int payloadLength) {
        byte[] bytes = new byte[1 + payloadLength];
        bytes[0] = (byte) id;
        for (int i = 1; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    @Test
    void withoutCompressionAFrameIsTheLengthAndThePacket() throws Exception {
        byte[] packet = packet(7, 5);
        byte[] frame = FrameCodec.encode(packet, -1);

        assertEquals(1 + packet.length, frame.length);
        assertEquals(packet.length, frame[0]);
        assertArrayEquals(packet, FrameCodec.decode(frame, -1, true, ALLOW));
    }

    @Test
    void belowTheThresholdACompressedFrameStoresThePacketBehindAZero() throws Exception {
        byte[] packet = packet(3, 10);
        byte[] frame = FrameCodec.encode(packet, 256);

        // length, 0 (stored), then the packet itself: what the game's CompressionEncoder writes.
        assertEquals(packet.length + 1, frame[0]);
        assertEquals(0, frame[1]);
        assertArrayEquals(packet, FrameCodec.decode(frame, 256, true, ALLOW));
    }

    @Test
    void aboveTheThresholdThePacketIsDeflatedAndTheDeclaredSizeIsTheOriginal() throws Exception {
        byte[] packet = new byte[2000];
        Arrays.fill(packet, (byte) 9);
        packet[0] = 0x11;
        byte[] frame = FrameCodec.encode(packet, 256);

        assertTrue(frame.length < packet.length / 4, "highly repetitive data deflates");
        int[] position = {0};
        FrameCodec.readVarInt(frame, position, 3);
        assertEquals(packet.length, FrameCodec.readVarInt(frame, position, 5), "declared uncompressed size");
        assertArrayEquals(packet, FrameCodec.decode(frame, 256, true, ALLOW));
    }

    @Test
    void theReservationIsChargedBeforeAnythingIsInflated() throws Exception {
        byte[] packet = new byte[5000];
        packet[0] = 1;
        byte[] frame = FrameCodec.encode(packet, 256);
        AtomicLong retained = new AtomicLong();
        AtomicInteger total = new AtomicInteger();

        FrameCodec.decode(frame, 256, true, (add, all) -> {
            retained.addAndGet(add);
            total.set(all);
        });
        assertEquals(5000, retained.get());
        assertEquals(5000, total.get());

        // An over-budget reservation throws before the output exists (13.12).
        assertThrows(IllegalStateException.class, () -> FrameCodec.decode(frame, 256, true, (add, all) -> {
            throw new IllegalStateException("over budget");
        }));
    }

    @Test
    void aDeclaredSizeOverTheProtocolMaximumIsRefusedBeforeReserving() throws Exception {
        byte[] body = concat(FrameCodec.varInt(FrameCodec.MAX_UNCOMPRESSED + 1), new byte[4]);
        byte[] frame = concat(FrameCodec.varInt(body.length), body);
        boolean[] reserved = new boolean[1];

        assertThrows(IOException.class, () -> FrameCodec.decode(frame, 256, false, (add, all) -> reserved[0] = true));
        assertFalse(reserved[0], "no reservation, no allocation");
    }

    @Test
    void theGamesThresholdValidationApplies() throws Exception {
        byte[] packet = new byte[300];
        packet[0] = 2;
        // Compressed although it is below the threshold the peer advertises: invalid when validating.
        byte[] frame = FrameCodec.encode(packet, 100);

        assertThrows(IOException.class, () -> FrameCodec.decode(frame, 1000, true, ALLOW));
        assertArrayEquals(packet, FrameCodec.decode(frame, 1000, false, ALLOW));
    }

    @Test
    void aFrameWhoseLengthDoesNotMatchItsBytesIsRefused() throws Exception {
        byte[] frame = FrameCodec.encode(packet(1, 8), -1);
        byte[] longer = Arrays.copyOf(frame, frame.length + 1);
        byte[] shorter = Arrays.copyOf(frame, frame.length - 1);

        assertThrows(IOException.class, () -> FrameCodec.decode(longer, -1, true, ALLOW));
        assertThrows(IOException.class, () -> FrameCodec.decode(shorter, -1, true, ALLOW));
        assertThrows(IOException.class, () -> FrameCodec.decode(new byte[] {0}, -1, true, ALLOW),
                "a zero-length frame is invalid");
    }

    @Test
    void aCorruptOrMislabelledDeflateStreamIsRefused() throws Exception {
        byte[] packet = new byte[1000];
        packet[0] = 4;
        byte[] frame = FrameCodec.encode(packet, 256);
        byte[] corrupt = frame.clone();
        corrupt[corrupt.length - 3] ^= 0x55;
        corrupt[corrupt.length / 2] ^= 0x55;

        assertThrows(IOException.class, () -> FrameCodec.decode(corrupt, 256, true, ALLOW));

        // The declared size is smaller than what the stream inflates to.
        int[] position = {0};
        FrameCodec.readVarInt(frame, position, 3);
        int declaredAt = position[0];
        byte[] lying = frame.clone();
        lying[declaredAt] = (byte) 0x81; // declares 897 (varint 0x81 0x07) instead of 1000
        lying[declaredAt + 1] = 0x07;
        assertThrows(IOException.class, () -> FrameCodec.decode(lying, 256, true, ALLOW));
    }

    @Test
    void aFrameLengthOverThreeBytesAndTruncatedVarIntsAreRefused() {
        assertThrows(IOException.class, () -> FrameCodec.decode(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, 1}, -1, true, ALLOW));
        assertThrows(IOException.class, () -> FrameCodec.decode(new byte[] {(byte) 0x80}, -1, true, ALLOW));
    }

    @Test
    void thePacketIdIsTheLeadingVarInt() throws Exception {
        assertEquals(0x7f, FrameCodec.packetId(new byte[] {0x7f, 1, 2}));
        assertEquals(300, FrameCodec.packetId(new byte[] {(byte) 0xAC, 0x02, 9}));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
