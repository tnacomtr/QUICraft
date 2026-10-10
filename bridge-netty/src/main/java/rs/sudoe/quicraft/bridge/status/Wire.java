// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge.status;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

/**
 * The few pieces of the Minecraft wire format the status handlers need, unchanged since 1.7:
 * VarInts, length-prefixed UTF-8 strings, and packet id 0 for the handshake, the status request
 * and the status response. Readers return null (and leave the reader index anywhere) on
 * malformed or truncated input; callers reset it.
 */
final class Wire {
    /** A status response holds at most 32767 UTF-16 code units, so at most 3 bytes each. */
    static final int MAX_STATUS_BYTES = 32767 * 3;

    private Wire() {}

    static Integer readVarInt(ByteBuf in) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                return null;
            }
            byte b = in.readByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return null;
    }

    static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    static String readString(ByteBuf in, int maxBytes) {
        Integer length = readVarInt(in);
        if (length == null || length < 0 || length > maxBytes || length > in.readableBytes()) {
            return null;
        }
        String s = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return s;
    }

    static void writeString(ByteBuf out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    /** The intent field of a handshake packet body (id included), or null if it isn't one. */
    static Integer handshakeIntent(ByteBuf frame) {
        Integer id = readVarInt(frame);
        if (id == null || id != 0 || readVarInt(frame) == null || readString(frame, 255 * 3) == null
                || frame.readableBytes() < 2) {
            return null;
        }
        frame.skipBytes(2);
        return readVarInt(frame);
    }
}
