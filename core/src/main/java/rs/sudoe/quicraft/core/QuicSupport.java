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
    private static volatile Boolean relaxedLossThreshold;

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

    /**
     * True if the loaded native can set quiche's relaxed loss threshold, which only QUICraft's
     * patched build of Netty's QUIC native can (natives/). With an upstream native, logs once at
     * INFO and returns false: QUIC still works, with quiche's default loss detection.
     */
    public static boolean hasRelaxedLossThreshold() {
        Boolean result = relaxedLossThreshold;
        if (result == null) {
            synchronized (QuicSupport.class) {
                result = relaxedLossThreshold;
                if (result == null) {
                    result = isAvailable() && probeRelaxedLossThreshold();
                    relaxedLossThreshold = result;
                }
            }
        }
        return result;
    }

    private static boolean probeRelaxedLossThreshold() {
        try {
            if (Quic.isRelaxedLossThresholdSupported()) {
                return true;
            }
            LOG.log(Level.INFO, "QUIC native without the relaxed loss threshold; packet reordering may"
                    + " cause spurious retransmissions");
        } catch (Throwable t) {
            LOG.log(Level.INFO, "QUIC native without the relaxed loss threshold: {0}", String.valueOf(t));
        }
        return false;
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
