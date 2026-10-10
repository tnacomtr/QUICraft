// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Captures the game's first flight from its first writes: the first two VarInt-framed packets
 * (handshake and Login Start, docs/protocol.md §8), byte for byte.
 */
final class FlightRecorder {
    static final int MAX_BYTES = 2048;
    static final int MORE = 0;
    static final int DONE = 1;
    static final int ABANDONED = -1;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int frames;
    private int remainingInFrame = -1;
    private int lengthValue;
    private int lengthShift;
    private boolean abandoned;

    /** Feeds written bytes; {@code data} itself is not consumed. */
    int add(ByteBuffer data) {
        if (abandoned) {
            return ABANDONED;
        }
        ByteBuffer in = data.duplicate();
        while (in.hasRemaining() && frames < 2) {
            if (remainingInFrame < 0) {
                int b = in.get() & 0xff;
                bytes.write(b);
                lengthValue |= (b & 0x7f) << lengthShift;
                lengthShift += 7;
                if ((b & 0x80) == 0) {
                    remainingInFrame = lengthValue;
                    lengthValue = 0;
                    lengthShift = 0;
                    if (remainingInFrame == 0) {
                        frames++;
                        remainingInFrame = -1;
                    }
                } else if (lengthShift >= 21) {
                    abandoned = true;
                    return ABANDONED;
                }
            } else {
                int n = Math.min(remainingInFrame, in.remaining());
                byte[] chunk = new byte[n];
                in.get(chunk);
                bytes.write(chunk, 0, n);
                remainingInFrame -= n;
                if (remainingInFrame == 0) {
                    frames++;
                    remainingInFrame = -1;
                }
            }
            if (bytes.size() > MAX_BYTES) {
                abandoned = true;
                return ABANDONED;
            }
        }
        return frames == 2 ? DONE : MORE;
    }

    byte[] flight() {
        return bytes.toByteArray();
    }
}
