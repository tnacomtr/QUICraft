// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

import io.netty.handler.codec.quic.Quic;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether QUIC can run on this machine: the quiche native library must load. On a platform
 * without one (Windows ARM64, FreeBSD, possibly musl) this is false and the endpoint runs
 * TCP-only. Never throws.
 */
public final class QuicSupport {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    private static volatile Boolean available;

    private QuicSupport() {}

    /** True if the native QUIC library loaded. Logs once at INFO when it didn't. */
    public static boolean isAvailable() {
        Boolean result = available;
        if (result == null) {
            synchronized (QuicSupport.class) {
                result = available;
                if (result == null) {
                    result = probe();
                    available = result;
                }
            }
        }
        return result;
    }

    /** Why QUIC is unavailable, or {@code null} if it is available. */
    public static Throwable unavailabilityCause() {
        try {
            return Quic.unavailabilityCause();
        } catch (Throwable t) {
            return t;
        }
    }

    private static boolean probe() {
        try {
            if (Quic.isAvailable()) {
                return true;
            }
            LOG.log(Level.INFO, "QUIC unavailable on this platform, using TCP only: {0}",
                    String.valueOf(Quic.unavailabilityCause()));
        } catch (Throwable t) {
            LOG.log(Level.INFO, "QUIC unavailable on this platform, using TCP only: {0}", String.valueOf(t));
        }
        return false;
    }
}
