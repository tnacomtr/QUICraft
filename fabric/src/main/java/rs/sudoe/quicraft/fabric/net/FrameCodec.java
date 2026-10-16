// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minecraft's per-packet frame as docs/protocol.md 13.4 carries it in a GAME record, without any
 * game class: {@code VarInt(length) || body}, where the body is the packet (id and payload) or,
 * once compression is on, {@code VarInt(uncompressed length, 0 when stored) || data}. It is the
 * arithmetic of the game's own length prepender, compression encoder and compression decoder, so a
 * frame built here is byte-for-byte what those handlers put on a TCP stream before encryption.
 *
 * <p>Limits follow the game's: a frame length VarInt of at most three bytes, 8 MiB of uncompressed
 * packet. The decoder asks its {@link Reserve} before allocating the output (13.12).</p>
 */
public final class FrameCodec {
    /** Largest uncompressed packet the game accepts and sends (8 MiB). */
    public static final int MAX_UNCOMPRESSED = 8388608;
    /** Largest frame length the game's three-byte length prefix can carry. */
    public static final int MAX_FRAME = 0x1FFFFF;

    /** Charges decoded output against the receive budget before it is allocated (13.12). */
    @FunctionalInterface
    public interface Reserve {
        void reserve(long retainedBytes, int uncompressedBytes);
    }

    private FrameCodec() {}

    /**
     * Builds the frame of one packet.
     *
     * @param packet the packet's id VarInt followed by its payload, as the game's codec writes it
     * @param threshold the compression threshold; negative when compression is off
     */
    public static byte[] encode(byte[] packet, int threshold) throws IOException {
        byte[] body;
        if (threshold < 0) {
            body = packet;
        } else if (packet.length < threshold) {
            body = concat(varInt(0), packet);
        } else {
            if (packet.length > MAX_UNCOMPRESSED) {
                throw new IOException("Packet too big (is " + packet.length + ", should be less than "
                        + MAX_UNCOMPRESSED + ")");
            }
            body = concat(varInt(packet.length), deflate(packet));
        }
        if (body.length > MAX_FRAME) {
            throw new IOException("Packet too large: size " + body.length + " is over " + MAX_FRAME);
        }
        return concat(varInt(body.length), body);
    }

    /**
     * Unwraps one frame to the packet bytes (id and payload).
     *
     * @param frame exactly one frame: the length VarInt and that many body bytes, nothing after
     * @param threshold the compression threshold; negative when compression is off
     * @param validate the game's {@code validateDecompressed}: refuse a compressed packet below
     *     the threshold
     */
    public static byte[] decode(byte[] frame, int threshold, boolean validate, Reserve reserve)
            throws IOException {
        int[] position = {0};
        long length = readVarInt(frame, position, 3);
        if (length != frame.length - position[0]) {
            throw new IOException("frame length " + length + " does not match the " + (frame.length - position[0])
                    + " body bytes");
        }
        if (length == 0) {
            throw new IOException("Frame length cannot be zero");
        }
        if (threshold < 0) {
            int size = (int) length;
            reserve.reserve(size, size);
            return slice(frame, position[0], size);
        }
        long declared = readVarInt(frame, position, 5);
        int rest = frame.length - position[0];
        if (declared == 0) {
            reserve.reserve(rest, rest);
            return slice(frame, position[0], rest);
        }
        if (declared > MAX_UNCOMPRESSED) {
            throw new IOException("Badly compressed packet - size of " + declared
                    + " is larger than protocol maximum of " + MAX_UNCOMPRESSED);
        }
        if (validate && declared < threshold) {
            throw new IOException("Badly compressed packet - size of " + declared
                    + " is below server threshold of " + threshold);
        }
        int size = (int) declared;
        // 13.12: charged before the output exists, so an over-budget packet allocates nothing.
        reserve.reserve(size, size);
        return inflate(frame, position[0], size);
    }

    /** Reads the packet id VarInt at the start of {@code packet}, as the game's codec does. */
    public static int packetId(byte[] packet) throws IOException {
        return (int) readVarInt(packet, new int[] {0}, 5);
    }

    private static byte[] inflate(byte[] frame, int offset, int size) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(frame, offset, frame.length - offset);
            byte[] out = new byte[size];
            int done = 0;
            while (done < size && !inflater.finished()) {
                int n = inflater.inflate(out, done, size - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                done += n;
            }
            if (done != size) {
                throw new IOException("Badly compressed packet - actual length of uncompressed payload " + done
                        + " is does not match declared size " + size);
            }
            if (!inflater.finished() && inflater.inflate(new byte[1]) != 0) {
                throw new IOException("Badly compressed packet - more data than the declared size " + size);
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("Badly compressed packet", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, input.length / 2));
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Reads a VarInt of at most {@code maxBytes} bytes; throws on a longer or truncated one. */
    static long readVarInt(byte[] data, int[] position, int maxBytes) throws IOException {
        long value = 0;
        for (int i = 0; i < maxBytes; i++) {
            if (position[0] >= data.length) {
                throw new IOException("truncated VarInt");
            }
            byte b = data[position[0]++];
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value & 0xFFFFFFFFL;
            }
        }
        throw new IOException("VarInt too big");
    }

    static byte[] varInt(int value) {
        byte[] buffer = new byte[5];
        int n = 0;
        int v = value;
        while ((v & -128) != 0) {
            buffer[n++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        buffer[n++] = (byte) v;
        return slice(buffer, 0, n);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
