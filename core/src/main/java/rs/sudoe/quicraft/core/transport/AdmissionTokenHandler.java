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
 * <p>A token-less Initial spends one attempt. If that is over a limit, or the open-connection
 * limits are full, the client gets a Retry (stateless and smaller than its Initial) instead of a
 * connection; otherwise Retry follows the {@link TransportConfig.RetryMode}. A valid token spends
 * another attempt, and if that is over a limit, or the open-connection limits are full, the Initial
 * is dropped without an answer. A refused client so falls back to TCP after its head start.
 *
 * <p>The open-connection limits are checked here, before a connection exists, because a connection
 * closed after Netty has created it still sends its handshake flight first: the client would see a
 * completed handshake and then a close, which reaches the game instead of falling back. The lease
 * itself is taken when the connection is created, right after this check on the same thread.
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
    private final java.util.concurrent.atomic.AtomicLong retries;
    private boolean forceRetry;

    /** {@code retries} counts the Retry packets asked for. */
    AdmissionTokenHandler(ConnectionAdmission admission, TransportConfig.RetryMode mode,
            java.util.concurrent.atomic.AtomicLong retries) {
        this.admission = admission;
        this.mode = mode;
        this.retries = retries;
        this.hmac = new HmacRetryTokenHandler(() -> forceRetry);
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        boolean retry = mode == TransportConfig.RetryMode.ALWAYS;
        try {
            if (address != null && address.getAddress() != null) {
                if (!admission.allowAttempt(address.getAddress()) || !openSlotFree(address)) {
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
            boolean written = hmac.writeToken(out, dcid, address);
            if (written) {
                retries.incrementAndGet();
            }
            return written;
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
            if (!admission.allowAttempt(address.getAddress()) || !openSlotFree(address)) {
                return -1;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "QUIC admission check failed; admitting the attempt", t);
        }
        return offset;
    }

    /** Whether a lease for {@code address} would be granted now; takes none. */
    private boolean openSlotFree(InetSocketAddress address) {
        ConnectionAdmission.Lease probe = admission.tryAcquire(address.getAddress());
        if (probe == null) {
            return false;
        }
        probe.close();
        return true;
    }

    @Override
    public int maxTokenLength() {
        return hmac.maxTokenLength();
    }
}
