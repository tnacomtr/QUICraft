// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.connect.FallbackReason;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/** Per-connection client state: the transport in use and a pending fallback report. */
public final class Transports {
    private static final Map<Connection, QuicByteStream> QUIC = new WeakHashMap<>();
    private static final Map<Connection, FallbackReason> FALLBACKS = new WeakHashMap<>();
    private static final Map<Connection, Boolean> RETRY_OVER_TCP = new WeakHashMap<>();

    private Transports() {}

    static synchronized void quic(Connection connection, QuicByteStream stream) {
        QUIC.put(connection, stream);
    }

    /** Whether {@code connection} runs over QUIC (else TCP, or memory for singleplayer). */
    public static synchronized boolean isQuic(Connection connection) {
        return QUIC.containsKey(connection);
    }

    /** Whether {@code connection} sent its first flight as 0-RTT data. */
    public static synchronized boolean usedZeroRtt(Connection connection) {
        return QUIC.get(connection) instanceof rs.sudoe.quicraft.core.transport.ClientStream stream
                && stream.sentEarlyData();
    }

    static synchronized @Nullable QuicByteStream quic(Connection connection) {
        return QUIC.get(connection);
    }

    static synchronized void fellBack(Connection connection, FallbackReason reason) {
        FALLBACKS.put(connection, reason);
    }

    /** The QUIC connection closed after a first-flight mismatch: its disconnect starts a TCP retry. */
    static synchronized void retryOverTcp(Connection connection) {
        RETRY_OVER_TCP.put(connection, Boolean.TRUE);
    }

    static synchronized boolean takeRetryOverTcp(Connection connection) {
        return RETRY_OVER_TCP.remove(connection) != null;
    }

    /** The reason to report on this connection, once. */
    static synchronized @Nullable FallbackReason takeFallback(Connection connection) {
        return FALLBACKS.remove(connection);
    }
}
