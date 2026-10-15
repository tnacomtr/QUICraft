// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core;

/** Wire constants from docs/protocol.md v1. */
public final class Protocol {
    /** Protocol version, the advertisement's {@code v}. */
    public static final int VERSION = 1;
    /**
     * The QUIC ALPN, naming the stream format (docs/protocol.md §1, §8). {@code quicraft/1} was
     * the unreleased format without stream preambles; builds that disagree fall back to TCP.
     */
    public static final String ALPN = "quicraft/2";
    /**
     * The multi-stream ALPN proposed by docs/protocol.md 12 and 13. Not shipped by any default code
     * path yet: a listener advertises it only with the disabled-by-default multi-stream flag set
     * (12.1, 13.1), and a client offers it only from {@code TransportSelection} (13.1). An
     * unrecognized ALPN still means TCP for an existing client (1, 13.1).
     */
    public static final String ALPN_V3 = "quicraft/3";
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
    /**
     * The client gave up on this connection's 0-RTT data (no early preamble) and reconnects; the
     * server drops anything it started from it.
     */
    public static final int CLOSE_EARLY_ABANDONED = 0x4;

    private Protocol() {}
}
