// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

import java.nio.charset.StandardCharsets;

/** Close metadata distinguishing application and transport close codes (docs/protocol.md 13.12). */
public final class CloseReason {
    public static final byte[] UNSEEN_PREREQUISITE = "unseen-prerequisite".getBytes(StandardCharsets.US_ASCII);
    private final boolean application;
    private final long errorCode;
    private final byte[] reason;
    public CloseReason(boolean application, long errorCode, byte[] reason) {
        if (reason == null) throw new NullPointerException("reason");
        this.application=application; this.errorCode=errorCode; this.reason=reason.clone();
    }
    public boolean isApplication() { return application; }
    public long errorCode() { return errorCode; }
    public byte[] reason() { return reason.clone(); }
}
