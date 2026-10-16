// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.lang.ref.WeakReference;
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
    /** Why a quic-only /3 join failed, for the disconnect screen; read after the pipeline is gone. */
    private static final Map<Connection, String> V3_FAILURES = new WeakHashMap<>();
    /** Weak values: the controller holds its connection, which would keep a strong entry alive for ever. */
    private static final Map<Connection, WeakReference<V3Client>> V3 = new WeakHashMap<>();

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

    static synchronized void v3Failure(Connection connection, String text) {
        V3_FAILURES.put(connection, text);
    }

    static synchronized @Nullable String v3Failure(Connection connection) {
        return V3_FAILURES.get(connection);
    }

    static synchronized void v3(Connection connection, V3Client client) {
        V3.put(connection, new WeakReference<>(client));
    }

    /** The multi-stream controller of {@code connection}, or null when it is not a {@code /3} join. */
    static synchronized @Nullable V3Client v3(Connection connection) {
        WeakReference<V3Client> reference = V3.get(connection);
        return reference == null ? null : reference.get();
    }

    /** Where a {@code /3} connection is, or null on TCP and on {@code /2}. */
    static V3Client.@Nullable State v3State(Connection connection) {
        V3Client client = v3(connection);
        return client == null ? null : client.state();
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
