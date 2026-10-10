// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads a first flight (handshake, then Login Start), as {@link FlightRecorder} captures it. */
final class FirstFlight {
    private FirstFlight() {}

    /**
     * The player name in the Login Start (its first field in every protocol version since 1.7),
     * or null if the bytes don't look like one.
     */
    static String playerName(byte[] flight) {
        try {
            ByteBuffer in = ByteBuffer.wrap(flight);
            int handshake = varInt(in);
            in.position(in.position() + handshake);
            int loginLength = varInt(in);
            int loginEnd = in.position() + loginLength;
            if (varInt(in) != 0) { // Login Start is packet 0 of the login state
                return null;
            }
            int nameLength = varInt(in);
            if (nameLength < 1 || nameLength > 64 || in.position() + nameLength > loginEnd) {
                return null;
            }
            byte[] name = new byte[nameLength];
            in.get(name);
            return new String(name, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int varInt(ByteBuffer in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.get() & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("VarInt too long");
    }
}
