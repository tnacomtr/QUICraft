// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code quicraft:fallback} plugin message (docs/protocol.md §6): VarInt version (1),
 * VarInt reason. The client sends it over TCP after falling back. The server treats it as
 * untrusted: it only logs a rate-limited hint and never changes behaviour.
 */
public final class FallbackReport {
    static final int VERSION = 1;
    /** Two VarInts of at most 5 bytes each. */
    static final int MAX_LENGTH = 10;

    private FallbackReport() {}

    public static byte[] encode(FallbackReason reason) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(2);
        writeVarInt(out, VERSION);
        writeVarInt(out, reason.code());
        return out.toByteArray();
    }

    /** Empty for anything but a well-formed version 1 report. Never throws. */
    public static Optional<FallbackReason> decode(byte[] payload) {
        if (payload == null || payload.length > MAX_LENGTH) {
            return Optional.empty();
        }
        int[] pos = {0};
        Integer version = readVarInt(payload, pos);
        Integer code = version == null ? null : readVarInt(payload, pos);
        if (version == null || version != VERSION || code == null || pos[0] != payload.length) {
            return Optional.empty();
        }
        return Optional.of(FallbackReason.fromCode(code));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static Integer readVarInt(byte[] in, int[] pos) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (pos[0] >= in.length) {
                return null;
            }
            byte b = in[pos[0]++];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return null;
    }

    /**
     * Server side: logs at most one hint per reason per 10 minutes, whatever clients send.
     * Thread-safe.
     */
    public static final class Log {
        static final long INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
        private static final Logger LOG = Logger.getLogger("QUICraft");

        private final int quicPort;
        private final LongSupplier clock;
        private final Map<FallbackReason, Long> lastLogged = new EnumMap<>(FallbackReason.class);

        public Log(int quicPort) {
            this(quicPort, System::currentTimeMillis);
        }

        Log(int quicPort, LongSupplier clock) {
            this.quicPort = quicPort;
            this.clock = clock;
        }

        /** Returns true if a line was logged. */
        public synchronized boolean report(byte[] payload) {
            Optional<FallbackReason> reason = decode(payload);
            if (!reason.isPresent()) {
                return false;
            }
            long now = clock.getAsLong();
            Long last = lastLogged.get(reason.get());
            if (last != null && now - last < INTERVAL_MILLIS) {
                return false;
            }
            lastLogged.put(reason.get(), now);
            LOG.log(Level.INFO, "Some clients could not use QUIC and fell back to TCP ({0}). If this keeps "
                    + "happening, check that UDP port {1} is reachable from outside.",
                    new Object[] {reason.get().name().toLowerCase(java.util.Locale.ROOT), Integer.toString(quicPort)});
            return true;
        }
    }
}
