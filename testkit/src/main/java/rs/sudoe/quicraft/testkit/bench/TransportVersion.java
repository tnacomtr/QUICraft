// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import rs.sudoe.quicraft.core.Protocol;

/**
 * The transport a benchmark run used, independent of the Minecraft protocol version.
 *
 * <p>The enum name is the stable JSON value. {@code TCP} and {@code QUIC_2} exist today;
 * {@code QUIC_3} is reserved for the negotiated multi-stream driver that lands with U3. A run
 * labelled {@code QUIC_2} is never accepted as {@code QUIC_3} evidence
 * ({@link NegotiatedTransportCheck}).
 */
public enum TransportVersion {
    TCP(""),
    QUIC_2(Protocol.ALPN),
    QUIC_3(Protocol.ALPN_V3);

    private final String alpn;

    TransportVersion(String alpn) {
        this.alpn = alpn;
    }

    /** The ALPN this transport uses; empty for TCP. */
    public String alpn() {
        return alpn;
    }
}
