// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection-attempt limits on top of {@link HmacRetryTokenHandler} (docs/protocol.md §9).
 *
 * <p>A token-less Initial spends one attempt. In every {@link TransportConfig.RetryMode} the
 * client gets a Retry (stateless and smaller than its Initial) instead of a connection when that
 * attempt is over a limit, when the open-connection limits are full, or while a quarter of the
 * open-connection limit is still in its handshake (a backlog, e.g. spoofed half-open handshakes).
 * Otherwise Retry follows the mode: {@code ALWAYS} always, {@code ADAPTIVE} once the attempt rate
 * reaches the Retry threshold, {@code NEVER} not at all. A valid token spends an attempt from a separate budget, so a flood of token-less Initials can't
 * starve clients that proved their address; if that is over a limit, or the open-connection limits
 * are full, the Initial is dropped without an answer. A refused client so falls back to TCP after
 * its head start.
 *
 * <p>A valid token can be replayed with a new destination connection id until it expires, and
 * each replay may start a connection. That is accepted: only the address the token was issued
 * to can complete such a handshake, and every replay spends that address's per-IP budgets.
 *
 * <p>The open-connection limits are checked here, before a connection exists, because a connection
 * closed after Netty has created it still sends its handshake flight first: the client would see a
 * completed handshake and then a close, which reaches the game instead of falling back. The lease
 * itself is taken when the connection is created, right after this check on the same thread.
 *
 * <p>Netty calls the codec's token handler on the event loop that owns the socket the Initial
 * arrived on. A listener with several SO_REUSEPORT sockets (docs/protocol.md §11) shares one
 * instance, so these calls run on several loops at once: {@link #writeToken} is synchronized
 * around the {@link #forceRetry} flag, which is only alive inside that call. Everything else the
 * handler touches is already shared safely ({@link ConnectionAdmission} is synchronized, the HMAC
 * handler is stateless, the counter is atomic). Admission failures fail open.
 */
final class AdmissionTokenHandler implements QuicTokenHandler {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private final ConnectionAdmission admission;
    private final ConnectionAdmission validated;
    private final TransportConfig.RetryMode mode;
    private final HmacRetryTokenHandler hmac;
    private final java.util.concurrent.atomic.AtomicLong retries;
    /**
     * True on a {@code /3} listener: valid-token attempts then share an exhausted window by prefix
     * (12.13). A {@code /2} listener keeps the released plain limit.
     */
    private final boolean fairValidated;
    private boolean forceRetry;

    /**
     * {@code admission} holds the token-less attempt budget and the open-connection leases;
     * {@code validated} only the attempt budget for valid tokens. {@code retries} counts the Retry
     * packets asked for.
     */
    AdmissionTokenHandler(ConnectionAdmission admission, ConnectionAdmission validated,
            TransportConfig.RetryMode mode, java.util.concurrent.atomic.AtomicLong retries) {
        this(admission, validated, mode, retries, false, null, null);
    }

    /**
     * {@code fairValidated}: the {@code /3} prefix-fair valid-token budget (12.13); false for {@code /2}, which
     * keeps the released plain limit (§11).
     */
    AdmissionTokenHandler(ConnectionAdmission admission, ConnectionAdmission validated,
            TransportConfig.RetryMode mode, java.util.concurrent.atomic.AtomicLong retries, boolean fairValidated) {
        this(admission, validated, mode, retries, fairValidated, null, null);
    }

    /**
     * Test constructor: an explicit HMAC key and clock make token issue and validation
     * deterministic. A null {@code key} or {@code clock} keeps the production delegate.
     */
    AdmissionTokenHandler(ConnectionAdmission admission, ConnectionAdmission validated,
            TransportConfig.RetryMode mode, java.util.concurrent.atomic.AtomicLong retries,
            boolean fairValidated, byte[] key, LongSupplier clock) {
        this.admission = admission;
        this.validated = validated;
        this.mode = mode;
        this.retries = retries;
        this.fairValidated = fairValidated;
        this.hmac = key == null || clock == null
                ? new HmacRetryTokenHandler(() -> forceRetry)
                : new HmacRetryTokenHandler(key, clock, () -> forceRetry);
    }

    @Override
    public synchronized boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        boolean retry = mode == TransportConfig.RetryMode.ALWAYS;
        try {
            if (address != null && address.getAddress() != null) {
                // Refusal-class conditions: a Retry in every mode.
                if (!admission.allowAttempt(address.getAddress()) || !openSlotFree(address)
                        || admission.handshakesBacklogged()) {
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
            if (!spendValidatedAttempt(address.getAddress()) || !openSlotFree(address)) {
                return -1;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "QUIC admission check failed; admitting the attempt", t);
        }
        return offset;
    }

    /**
     * Spends one valid-token attempt. On a {@code /3} listener the budget is prefix-fair when the window is
     * exhausted; on {@code /2} it is the released plain limit. Package-private so both modes are tested.
     */
    boolean spendValidatedAttempt(InetAddress address) {
        return fairValidated ? validated.allowFairAttempt(address) : validated.allowAttempt(address);
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
