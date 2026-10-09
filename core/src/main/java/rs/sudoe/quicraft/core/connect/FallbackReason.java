// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.net.PortUnreachableException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;

/** Why QUIC wasn't used; wire codes from docs/protocol.md §6. */
public enum FallbackReason {
    TIMEOUT_OR_LOST_RACE(1),
    HANDSHAKE_FAILURE(2),
    FINGERPRINT_MISMATCH(3),
    UDP_UNREACHABLE(4),
    OTHER(5);

    private final int code;

    FallbackReason(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Unknown codes map to {@link #OTHER}; the server never trusts the report anyway. */
    public static FallbackReason fromCode(int code) {
        for (FallbackReason r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return OTHER;
    }

    /** Classifies a failed QUIC attempt. */
    public static FallbackReason classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof FingerprintMismatchException) {
                return FINGERPRINT_MISMATCH;
            }
            if (t instanceof PortUnreachableException) {
                return UDP_UNREACHABLE;
            }
            if (t instanceof TimeoutException || t.getClass().getSimpleName().equals("ConnectTimeoutException")) {
                return TIMEOUT_OR_LOST_RACE;
            }
            if (t instanceof SSLException) {
                return HANDSHAKE_FAILURE;
            }
        }
        return OTHER;
    }
}
