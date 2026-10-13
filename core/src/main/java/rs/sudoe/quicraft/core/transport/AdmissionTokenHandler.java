// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection-attempt limits on top of {@link HmacRetryTokenHandler} (docs/protocol.md §9).
 *
 * <p>A token-less Initial spends one attempt. If that is over a limit, the client gets a Retry
 * (stateless and smaller than its Initial) instead of a connection; otherwise Retry follows the
 * {@link TransportConfig.RetryMode}. A valid token spends another attempt, and if that is over a
 * limit the Initial is dropped without an answer. A refused client so falls back to TCP after its
 * head start.
 *
 * <p>Netty calls the codec's token handler on the listener's single event loop (a one-thread
 * group, or the platform loop that owns the hosted channel), so {@link #forceRetry} is a plain
 * field: written in {@link #writeToken} just before the delegate reads it through its policy.
 * Admission failures fail open.
 */
final class AdmissionTokenHandler implements QuicTokenHandler {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private final ConnectionAdmission admission;
    private final TransportConfig.RetryMode mode;
    private final HmacRetryTokenHandler hmac;
    private boolean forceRetry;

    AdmissionTokenHandler(ConnectionAdmission admission, TransportConfig.RetryMode mode) {
        this.admission = admission;
        this.mode = mode;
        this.hmac = new HmacRetryTokenHandler(() -> forceRetry);
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        boolean retry = mode == TransportConfig.RetryMode.ALWAYS;
        try {
            if (address != null && address.getAddress() != null) {
                if (!admission.allowAttempt(address.getAddress())) {
                    retry = true;
                } else if (mode == TransportConfig.RetryMode.ADAPTIVE && admission.requiresRetry()) {
                    retry = true;
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "QUIC admission check failed; admitting the attempt", t);
        }
        forceRetry = retry;
        try {
            return hmac.writeToken(out, dcid, address);
        } finally {
            forceRetry = false;
        }
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        int offset = hmac.validateToken(token, address);
        if (offset < 0) {
            return offset;
        }
        try {
            if (!admission.allowAttempt(address.getAddress())) {
                return -1;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "QUIC admission check failed; admitting the attempt", t);
        }
        return offset;
    }

    @Override
    public int maxTokenLength() {
        return hmac.maxTokenLength();
    }
}
