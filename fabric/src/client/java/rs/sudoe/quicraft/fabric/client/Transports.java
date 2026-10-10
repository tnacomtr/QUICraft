// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.connect.FallbackReason;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/** Per-connection client state: the transport in use and a pending fallback report. */
final class Transports {
    private static final Map<Connection, QuicByteStream> QUIC = new WeakHashMap<>();
    private static final Map<Connection, FallbackReason> FALLBACKS = new WeakHashMap<>();

    private Transports() {}

    static synchronized void quic(Connection connection, QuicByteStream stream) {
        QUIC.put(connection, stream);
    }

    static synchronized @Nullable QuicByteStream quic(Connection connection) {
        return QUIC.get(connection);
    }

    static synchronized void fellBack(Connection connection, FallbackReason reason) {
        FALLBACKS.put(connection, reason);
    }

    /** The reason to report on this connection, once. */
    static synchronized @Nullable FallbackReason takeFallback(Connection connection) {
        return FALLBACKS.remove(connection);
    }
}
