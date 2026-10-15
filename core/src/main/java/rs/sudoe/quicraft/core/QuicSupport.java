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
    private static volatile Boolean keyingMaterialExport;
    private static volatile Boolean maxFlowControlWindows;

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

    /**
     * True if the loaded native can export TLS keying material, which only QUICraft's patched
     * build of Netty's QUIC native can (natives/patches/netty/0004, docs/protocol.md §12.2). With an
     * upstream native, logs once at INFO and returns false: QUIC still works, but nothing that
     * needs the exporter (channel binding, §12.3) may be selected.
     */
    public static boolean hasKeyingMaterialExport() {
        Boolean result = keyingMaterialExport;
        if (result == null) {
            synchronized (QuicSupport.class) {
                result = keyingMaterialExport;
                if (result == null) {
                    result = isAvailable() && probeKeyingMaterialExport();
                    keyingMaterialExport = result;
                }
            }
        }
        return result;
    }

    private static boolean probeKeyingMaterialExport() {
        try {
            if (Quic.isKeyingMaterialExportSupported()) {
                return true;
            }
            LOG.log(Level.INFO, "QUIC native without the TLS keying material exporter;"
                    + " channel binding is unavailable");
        } catch (Throwable t) {
            LOG.log(Level.INFO, "QUIC native without the TLS keying material exporter: {0}", String.valueOf(t));
        }
        return false;
    }

    /**
     * True if the loaded native can cap quiche's flow-control auto-tuning, which only QUICraft's
     * patched build of Netty's QUIC native can (natives/patches/netty/0005, docs/protocol.md
     * §13.13). With an upstream native, logs once at INFO and returns false: single-stream QUIC
     * still works, but multi-stream (/3) must not be offered.
     */
    public static boolean hasMaxFlowControlWindows() {
        Boolean result = maxFlowControlWindows;
        if (result == null) {
            synchronized (QuicSupport.class) {
                result = maxFlowControlWindows;
                if (result == null) {
                    result = isAvailable() && probeMaxFlowControlWindows();
                    maxFlowControlWindows = result;
                }
            }
        }
        return result;
    }

    private static boolean probeMaxFlowControlWindows() {
        try {
            if (Quic.isMaxFlowControlWindowsSupported()) {
                return true;
            }
            LOG.log(Level.INFO, "QUIC native without maximum flow-control windows; multi-stream is unavailable");
        } catch (Throwable t) {
            LOG.log(Level.INFO, "QUIC native without maximum flow-control windows: {0}", String.valueOf(t));
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
