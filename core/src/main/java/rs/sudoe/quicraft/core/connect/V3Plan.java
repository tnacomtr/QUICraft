// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.pin.PinExpiry;
import rs.sudoe.quicraft.core.pin.PinKey;
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
 * {@link #activated(BindingSession)} or {@link #failed(Phase, String)}, which is the only place
 * 12.15's pin write, 12.16's re-bind bookkeeping and the player-facing fallback message happen.</p>
 *
 * <p><b>Fail-safe during the join.</b> The user's decision: any {@code /3} failure while joining,
 * that is before activation completes, falls back to TCP at once and tells the player, so
 * {@link #failed(Phase, String)} answers "retry over a fresh TCP join now" for the first failure of
 * the {@link Phase#JOINING} phase, whatever kind it was: a binding failure, {@code 0x10}, an
 * exporter error, a deadline, a quiescence overflow, a handshake or ALPN failure, or any
 * {@code Throwable} in this project's own code. A failure of the {@link Phase#PLAY} phase, after
 * activation, ends the attempt without a same-attempt retry, and a later call never retries again,
 * so the adapter cannot start two TCP joins for one connection. Either way the activation-failure
 * entry is recorded, so later joins go straight to TCP during the backoff (12.14), and
 * {@link #fallbackMessage()} carries the reason for the log and for chat.</p>
 *
 * <p><b>A pin needs a passing binding.</b> {@link #activated(BindingSession)} writes an
 * authenticated pin only when the session reports activation and its mode is the online,
 * authenticated one (12.7, 12.15). Offline mode never writes a pin and never completes a changed-pin
 * re-bind, because a pin it cannot authenticate must not replace one it can (12.16, 12.19).</p>
 *
 * <p>Both reporting calls run their file work on the connector's worker executor, never on the
 * caller's event loop: {@link PinStore#recordPin(String, int, String)} and
 * {@link RebindState#endAttempt(RebindState.Attempt, boolean)} write and fsync the whole state file,
 * which the pin and re-bind packages explicitly forbid on a Netty event loop. {@link
 * #activated(BindingSession)} therefore returns a future rather than a boolean, so a pin write that
 * failed can close the connection with {@code 0x10} (12.15) without blocking the event loop.
 * {@link #failed(Phase, String)} can answer synchronously because its answer is policy, not a write
 * result.</p>
 *
 * <p><b>Thread-safe.</b> The adapter may report from the connection's event loop while the worker
 * writes state, so every field here is volatile or guarded by this object's monitor, and the first
 * {@link #activated(BindingSession)} future is the one every caller gets. The worker must run its
 * tasks serially, which {@link ClientConnector.V3Support} guarantees by wrapping whatever executor
 * it is given; the plan's own state writes are ordered by that.</p>
 *
 * <p>Every state call fails closed: a failure cache or pin write that throws is treated as "the
 * write did not happen", so the pin is never assumed written and the backoff never assumed clear.
 * No method throws for anything the adapter or the worker does.</p>
 */
public final class V3Plan {
    /**
     * Which side of activation a failure happened on (the user's fail-safe decision). A pin-write
     * failure after the cipher went off is {@link #JOINING}: the connection is not usable, so the
     * player gets a fresh TCP join in the same attempt.
     */
    public enum Phase {
        /** The failure happened while joining, before activation completed: retry over fresh TCP. */
        JOINING,
        /** The failure happened after activation, during play: disconnect, and the next join is TCP. */
        PLAY
    }

    /** The cause used when the caller does not name a more specific one (user decision). */
    public static final String INTERNAL_ERROR = "internal error";
    /** The cause for a failed changed-pin re-bind (user decision; 12.16). */
    public static final String REBIND_FAILED = "server certificate changed and re-bind failed";
    /** The cause for a channel binding failure (user decision; 12.9). */
    public static final String BINDING_FAILED = "channel binding failed";
    /** The cause for a QUIC handshake that did not complete in time (user decision; §5). */
    public static final String HANDSHAKE_TIMED_OUT = "handshake timed out";
    /** The cause for a pin that could not be written after activation (12.15). */
    public static final String PIN_WRITE_FAILED = "the server certificate could not be pinned";

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

    private volatile boolean activationReported;
    private volatile boolean pinWriteFailed;
    private volatile boolean failureReported;
    private volatile boolean failureStateWritten;
    private volatile String reason;
    /** The first {@link #activated(BindingSession)} future, so every caller sees the same answer. */
    private CompletableFuture<Boolean> activation;

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
     * Reports that the binding exchange completed, and that its session really activated. The pin is
     * recorded, the activation-failure state is cleared and the re-bind attempt ends with success
     * (12.14, 12.15, 12.16).
     *
     * <p>The file work runs on the worker executor, so this never blocks the event loop. The first
     * call's future is the one every later call returns, so a second report never writes the pin
     * twice. A plan that already reported a failure answers false: a failed attempt cannot activate
     * afterwards.</p>
     *
     * @param session the binding session of this connection, which decides whether a pin may be
     *     written: only a session that reports {@link BindingSession#isActivated()} and
     *     {@link BindingSession#mode()} online may (12.7, 12.15). An offline session never writes a
     *     pin, and never completes a changed-pin re-bind either (12.16, 12.19)
     * @return a future that completes true when activation's bookkeeping succeeded, and false when
     *     the binding did not pass or the pin could not be written. A false result means the caller
     *     must not treat this as a passed activation: it closes the connection with {@code 0x10}
     *     (12.15), leaves the pin as it was and does not downgrade the live connection.
     */
    public CompletableFuture<Boolean> activated(BindingSession session) {
        final boolean bindingPassed = session != null && session.isActivated();
        final boolean online = bindingPassed && session.mode() == BindingStateMachine.MODE_ONLINE;
        synchronized (this) {
            if (activation != null) {
                return activation;
            }
            if (failureReported) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            activationReported = true;
            CompletableFuture<Boolean> submitted;
            try {
                submitted = CompletableFuture.supplyAsync(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return activate(bindingPassed, online);
                    }
                }, worker);
            } catch (Throwable t) {
                // A worker that refuses the task means the pin was not written, which is 12.15's
                // write failure: answer false (the caller closes 0x10) instead of throwing here.
                pinWriteFailed = true;
                submitted = CompletableFuture.completedFuture(Boolean.FALSE);
            }
            activation = submitted;
            return submitted;
        }
    }

    /** The worker half of {@link #activated(BindingSession)}. */
    private Boolean activate(boolean bindingPassed, boolean online) {
        if (!bindingPassed) {
            // No proven activation: no pin, no state change. The adapter reports a binding that did
            // not complete through failed(Phase, String) instead.
            pinWriteFailed = true;
            return Boolean.FALSE;
        }
        boolean recorded = !online || writePin();
        pinWriteFailed = !recorded;
        if (!recorded) {
            // 12.15: a write failure after activation is itself an activation failure, recorded once
            // so the adapter's later failed(...) cannot count it twice.
            writeFailureStateOnce();
            return Boolean.FALSE;
        }
        // 12.14: only a later successful activation clears the activation-failure state.
        clearActivationFailure();
        if (online) {
            endAttempt(true);
        } else if (attempt != null) {
            // 12.16, 12.19: an offline binding cannot replace a changed pin, so its re-bind attempt
            // is over but its backoff stays, and a new one is refused for the window's length.
            endAttempt(false);
        }
        return Boolean.TRUE;
    }

    /**
     * Reports a {@code /3} failure with {@link #INTERNAL_ERROR} as its cause.
     *
     * @see #failed(Phase, String)
     */
    public boolean failed(Phase phase) {
        return failed(phase, INTERNAL_ERROR);
    }

    /**
     * Reports that the {@code /3} attempt failed (12.14, 12.16, and the user's fail-safe decision):
     * the failure is recorded in §6's cache as an activation failure, so later joins go straight to
     * TCP during the backoff, the re-bind attempt ends with failure and its window advances, and the
     * cause is kept for {@link #fallbackMessage()}.
     *
     * <p>The file work runs on the worker executor; the answer below does not depend on it, and a
     * worker that refuses the task cannot hide the answer.</p>
     *
     * @param phase {@link Phase#JOINING} for a failure before activation completed, which is the
     *     fail-safe case; {@link Phase#PLAY} for a failure after activation, during play. A pin-write
     *     failure reported after the cipher went off is {@code JOINING}
     * @param reason a short human-readable cause for the player, never a secret, an exporter value,
     *     a tag or a payload; null means {@link #INTERNAL_ERROR}
     * @return whether the caller must close the QUIC connection where applicable and start a fresh
     *     vanilla TCP join in this same attempt: true for the first {@link Phase#JOINING} failure,
     *     false for a {@link Phase#PLAY} failure and false for every later call, so one connection can
     *     never start two TCP joins
     */
    public boolean failed(Phase phase, String reason) {
        boolean retry;
        synchronized (this) {
            // The retry decision belongs to the first report only, so one connection can never start
            // two TCP joins: a later report returns false whatever phase it names.
            retry = !failureReported && phase == Phase.JOINING;
            if (!failureReported) {
                failureReported = true;
                this.reason = reason == null ? INTERNAL_ERROR : reason;
            }
        }
        writeFailureStateOnce();
        return retry;
    }

    /**
     * Records §6's activation failure and ends the re-bind attempt, at most once per connection
     * (12.14, 12.15): an activation whose pin write failed already wrote this state, and the
     * adapter's later {@link #failed(Phase, String)} must not count that failure twice, which would
     * double the backoff window.
     */
    private void writeFailureStateOnce() {
        synchronized (this) {
            if (failureStateWritten) {
                return;
            }
            failureStateWritten = true;
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        recordActivationFailure();
                        endAttempt(false);
                    } catch (Throwable ignored) {
                        // Best effort: the state disables itself on a failed write.
                    }
                }
            });
        } catch (Throwable ignored) {
            // A worker that refuses the task must not hide the retry decision: the caller still has
            // to close the QUIC connection and fall back to TCP.
        }
    }

    /**
     * The message to log and to show the player, or null while nothing failed:
     * {@code "QUIC multi-stream failed (" + reason + "), fell back to TCP"}. The connector sets the
     * same message on the outcome when the failure happened before the adapter could drive the
     * exchange, such as a handshake that timed out.
     */
    public String fallbackMessage() {
        return reason == null ? null : fallbackMessage(reason);
    }

    /** The one place the player-facing fallback message is built (user decision). */
    public static String fallbackMessage(String reason) {
        return "QUIC multi-stream failed (" + (reason == null ? INTERNAL_ERROR : reason)
                + "), fell back to TCP";
    }

    /** The short cause behind {@link #fallbackMessage()}, or null while nothing failed. */
    public String fallbackReason() {
        return reason;
    }

    /** Whether this plan already reported a failure through {@link #failed(Phase, String)}. */
    public boolean isFailed() {
        return failureReported;
    }

    /** Whether this plan already reported a successful activation through {@link #activated(BindingSession)}. */
    public boolean isActivated() {
        return activationReported && !failureReported && !pinWriteFailed;
    }

    /** 12.15: only the post-SERVER_SWITCH online-mode path writes a pin. */
    private boolean writePin() {
        if (pins == null) {
            return true;
        }
        try {
            Faults.check(Faults.PIN_WRITE);
            Set<PinKey> activeKeys = activeKeys();
            if (rebinds != null) {
                // 12.15: an expired pin's re-bind state is forgotten before the pin itself is
                // replaced, exactly as the connect path does before its own lookups.
                PinExpiry.purge(pins, rebinds, activeKeys);
            }
            PinStore.RecordResult result = pins.recordPin(host, logicalPort, fingerprint.toString(),
                    activeKeys);
            return result == PinStore.RecordResult.RECORDED || result == PinStore.RecordResult.UPDATED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The logical servers an attempt is running for, as 12.15's expiry calls take them. */
    private Set<PinKey> activeKeys() {
        return rebinds == null ? Collections.<PinKey>emptySet() : rebinds.activeKeys();
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
