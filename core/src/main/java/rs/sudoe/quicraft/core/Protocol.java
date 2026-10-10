// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

/** Wire constants from docs/protocol.md v1. */
public final class Protocol {
    /** Protocol version, the advertisement's {@code v}. */
    public static final int VERSION = 1;
    public static final String ALPN = "quicraft/1";
    /** Top-level member name in the status JSON. */
    public static final String ADVERTISEMENT_KEY = "quicraft:quic";
    /** Plugin channel for the client's fallback report. */
    public static final String FALLBACK_CHANNEL = "quicraft:fallback";

    /** Application error codes for CONNECTION_CLOSE. */
    public static final int CLOSE_NORMAL = 0x0;
    public static final int CLOSE_PROTOCOL_VIOLATION = 0x1;
    public static final int CLOSE_INTERNAL_ERROR = 0x2;
    /** A connection passed to the game on 0-RTT data didn't complete its handshake in time. */
    public static final int CLOSE_EARLY_UNCONFIRMED = 0x3;

    private Protocol() {}
}
