// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * What the pre-connect selection decided for a {@code quicraft/3} connection, and the pin and
 * re-bind bookkeeping that follows from it (docs/protocol.md 12.3, 12.15, 12.16).
 *
 * <p>{@link ClientConnector.Outcome#v3Plan()} returns one of these when a {@code /3} QUIC stream
 * was selected. The adapter needs {@link #fingerprint()} F and {@link #routingDigest()} R to build
 * the binding session of 12.7, and it reports the end of the attempt back through
 * {@link #activated()} or {@link #failed(boolean, String)}, which is the only place 12.15's pin
 * write, 12.16's re-bind bookkeeping and the player-facing fallback message happen.</p>
 *
 * <p><b>Fail-safe during the join.</b> The user's decision: any {@code /3} failure while joining,
 * that is before activation completes, falls back to TCP at once and tells the player, so
 * {@link #failed(boolean, String)} answers "retry over a fresh TCP join now" for every failure kind
 * before activation: a binding failure, {@code 0x10}, an exporter error, a deadline, a quiescence
 * overflow, a handshake or ALPN failure, or any {@code Throwable} in this project's own code. Only a
 * failure after activation, during play, ends the attempt without a same-attempt retry. Either way
 * the activation-failure entry is recorded, so later joins go straight to TCP during the backoff
 * (12.14), and {@link #fallbackMessage()} carries the reason for the log and for chat.</p>
 *
 * <p>Both reporting calls run their file work on the connector's worker executor, never on the
 * caller's event loop: {@link PinStore#recordPin(String, int, String)} and
 * {@link RebindState#endAttempt(RebindState.Attempt, boolean)} write and fsync the whole state file,
 * which the pin and re-bind packages explicitly forbid on a Netty event loop. {@link #activated()}
 * therefore returns a future rather than a boolean, so a pin write that failed can close the
 * connection with {@code 0x10} (12.15) without blocking the event loop. {@link #failed(boolean,
 * String)} can answer synchronously because its answer is policy, not a write result.</p>
 *
 * <p>Every state call fails closed: a failure cache or pin write that throws is treated as "the
 * write did not happen", so the pin is never assumed written and the backoff never assumed clear.</p>
 */
public final class V3Plan {
    /** The cause used when the caller does not name a more specific one (user decision). */
    public static final String INTERNAL_ERROR = "internal error";
    /** The cause for a failed changed-pin re-bind (user decision; 12.16). */
    public static final String REBIND_FAILED = "server certificate changed and re-bind failed";
    /** The cause for a channel binding failure (user decision; 12.9). */
    public static final String BINDING_FAILED = "channel binding failed";
    /** The cause for a QUIC handshake that did not complete in time (user decision; §5). */
    public static final String HANDSHAKE_TIMED_OUT = "handshake timed out";

    private final TransportSelection.PinState pinState;
    private final RebindState.Attempt attempt;
    private final Fingerprint fingerprint;
    private final byte[] routingDigest;
    private final PinStore pins;
    private final FailureCache failures;
    private final RebindState rebinds;
    private final Executor worker;
    private final InetAddress address;
    private final int port;
    private final String host;
    private final int logicalPort;

    private boolean authenticatedLogin;
    private boolean activationReported;
    private boolean pinWriteFailed;
    private boolean failureReported;
    private boolean retryFreshTcp;
    private String reason;

    V3Plan(TransportSelection.PinState pinState, RebindState.Attempt attempt, Fingerprint fingerprint,
            byte[] routingDigest, PinStore pins, FailureCache failures,
            RebindState rebinds, Executor worker, InetAddress address, int port,
            String host, int logicalPort) {
        this.pinState = pinState;
        this.attempt = attempt;
        this.fingerprint = fingerprint;
        this.routingDigest = routingDigest == null ? null : routingDigest.clone();
        this.pins = pins;
        this.failures = failures;
        this.rebinds = rebinds;
        this.worker = worker;
        this.address = address;
        this.port = port;
        this.host = host;
        this.logicalPort = logicalPort;
    }

    /** The pin state the selection was made from (12.15, 12.16). */
    public TransportSelection.PinState pinState() {
        return pinState;
    }

    /**
     * The one automatic re-bind attempt this connection holds, or null when there is none: an
     * ordinary pin never takes one, and a changed pin only does when the backoff allowed it.
     */
    public RebindState.Attempt attempt() {
        return attempt;
    }

    /** F, the advertised certificate fingerprint this connection must bind to (12.7). */
    public Fingerprint fingerprint() {
        return fingerprint;
    }

    /**
     * F as its 32 raw bytes, the form {@link rs.sudoe.quicraft.core.binding.BindingStateMachine}
     * takes. {@link Fingerprint} keeps its digest private, so it is decoded from the canonical
     * {@code sha256:<64 hex>} text.
     */
    public byte[] fingerprintBytes() {
        String text = fingerprint.toString();
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(7 + 2 * i, 9 + 2 * i), 16);
        }
        return bytes;
    }

    /**
     * R, the local routing resource digest of 13.7, as 32 raw bytes. It equals the digest the
     * advertisement carried, which is what allowed {@code /3} to be selected at all (13.1).
     */
    public byte[] routingDigest() {
        return routingDigest == null ? null : routingDigest.clone();
    }

    /**
     * Tells this plan that the Minecraft login on this connection was authenticated (12.7's mode 1)
     * or was not (mode 0). Only an authenticated login may create or update an authenticated pin
     * (12.15, 12.19); the adapter learns this during login and must call this before activation. The
     * default is false, so a plan nobody told about an authenticated login never writes a pin.
     */
    public void authenticatedLogin(boolean authenticated) {
        this.authenticatedLogin = authenticated;
    }

    /**
     * Reports that the binding exchange completed: the pin is recorded, the activation-failure
     * state is cleared and the re-bind attempt ends with success (12.14, 12.15, 12.16).
     *
     * <p>The file work runs on the worker executor, so this never blocks the event loop. A plan that
     * already reported a failure does nothing here and answers false: a failed attempt cannot
     * activate afterwards.</p>
     *
     * @return a future that completes true when activation's bookkeeping succeeded, and false when
     *     the pin could not be written or the store refused it. A false result is 12.15's write
     *     failure: the caller closes the connection with {@code 0x10}, leaves the pin as it was and
     *     does not downgrade the live connection. In offline mode (or with no pin store) there is
     *     nothing to write and the future completes true.
     */
    public CompletableFuture<Boolean> activated() {
        if (activationReported || failureReported) {
            return CompletableFuture.completedFuture(Boolean.valueOf(!pinWriteFailed && !failureReported));
        }
        activationReported = true;
        return CompletableFuture.supplyAsync(new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                boolean recorded = writePin();
                pinWriteFailed = !recorded;
                if (recorded) {
                    // 12.14: only a later successful activation clears the activation-failure state.
                    clearActivationFailure();
                    endAttempt(true);
                } else {
                    // 12.15: a write failure after activation is itself an activation failure.
                    recordActivationFailure();
                    endAttempt(false);
                }
                return Boolean.valueOf(recorded);
            }
        }, worker);
    }

    /**
     * Reports a {@code /3} failure with {@link #INTERNAL_ERROR} as its cause.
     *
     * @see #failed(boolean, String)
     */
    public boolean failed(boolean afterActivation) {
        return failed(afterActivation, INTERNAL_ERROR);
    }

    /**
     * Reports that the {@code /3} attempt failed (12.14, 12.16, and the user's fail-safe decision):
     * the failure is recorded in §6's cache as an activation failure, so later joins go straight to
     * TCP during the backoff, the re-bind attempt ends with failure and its window advances, and the
     * cause is kept for {@link #fallbackMessage()}.
     *
     * <p>The file work runs on the worker executor; the answer below does not depend on it.</p>
     *
     * @param afterActivation false when the failure happened while joining, that is before
     *     activation completed; true when it happened after activation, during play
     * @param reason a short human-readable cause for the player, never a secret, an exporter value,
     *     a tag or a payload; null means {@link #INTERNAL_ERROR}
     * @return whether the caller must close the QUIC connection where applicable and start a fresh
     *     vanilla TCP join in this same attempt. True for every failure before activation completes,
     *     whatever kind it was; false after activation, where the disconnect shows the reason and the
     *     next join uses TCP. Reporting a second failure returns the first answer and changes nothing.
     */
    public boolean failed(final boolean afterActivation, String reason) {
        if (failureReported) {
            return retryFreshTcp;
        }
        failureReported = true;
        this.reason = reason == null ? INTERNAL_ERROR : reason;
        retryFreshTcp = !afterActivation;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                recordActivationFailure();
                endAttempt(false);
            }
        });
        return retryFreshTcp;
    }

    /**
     * The message to log and to show the player, or null while nothing failed:
     * {@code "QUIC multi-stream failed (" + reason + "), fell back to TCP"}. The connector sets the
     * same message on the outcome when the failure happened before the adapter could drive the
     * exchange, such as a handshake that timed out.
     */
    public String fallbackMessage() {
        return reason == null ? null
                : "QUIC multi-stream failed (" + reason + "), fell back to TCP";
    }

    /** The short cause behind {@link #fallbackMessage()}, or null while nothing failed. */
    public String fallbackReason() {
        return reason;
    }

    /** Whether this plan already reported a failure through {@link #failed(boolean, String)}. */
    public boolean isFailed() {
        return failureReported;
    }

    /** Whether this plan already reported a successful activation through {@link #activated()}. */
    public boolean isActivated() {
        return activationReported && !failureReported;
    }

    /** 12.15: only the post-SERVER_SWITCH online-mode path writes a pin. */
    private boolean writePin() {
        if (!authenticatedLogin || pins == null) {
            return true;
        }
        try {
            Faults.check(Faults.PIN_WRITE);
            PinStore.RecordResult result = pins.recordPin(host, logicalPort, fingerprint.toString());
            return result == PinStore.RecordResult.RECORDED || result == PinStore.RecordResult.UPDATED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearActivationFailure() {
        try {
            failures.clearActivationFailure(address, port, fingerprint);
        } catch (Throwable ignored) {
            // Fail closed: the entry stays, so the next attempt backs off rather than assuming it was cleared.
        }
    }

    private void recordActivationFailure() {
        try {
            failures.recordActivationFailure(address, port, fingerprint);
        } catch (Throwable ignored) {
            // Nothing better to do than leave the cache as it is.
        }
    }

    /**
     * Ends the re-bind attempt, when this connection took one. A success clears the server's state
     * (12.16's schedule), a failure advances its window; the pin is recorded before this call, so
     * the new pin is durable before the window is cleared.
     */
    private void endAttempt(boolean success) {
        if (rebinds == null || attempt == null) {
            return;
        }
        try {
            rebinds.endAttempt(attempt, success);
        } catch (Throwable ignored) {
            // The state disables itself on a failed write; the caller cannot do more here.
        }
    }
}
