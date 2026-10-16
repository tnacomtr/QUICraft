// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.Arrays;
import rs.sudoe.quicraft.core.CloseReason;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
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
 * <p><b>Close classification.</b> {@link #closed(Phase, CloseEvent, String)} is the classified form
 * of {@link #failed(Phase, String)}: it sorts a {@code /3} close into a {@link CloseClass} with
 * {@link #classify(CloseEvent, boolean, boolean, boolean)} (12.14, 13.12) and applies only that
 * class's failure-cache and limiter effects. Generic resource closes write no failure-cache entry
 * and start 12.14's fixed 60-second window instead; a pre-login shed writes nothing and only counts
 * toward its own three-consecutive-shed bound; a dead path counts like an idle timeout; repeated
 * authenticated unseen-prerequisite closes are the only resource closes that write the cache. The
 * per-logical-server resource state lives in {@link RebindState}, with 12.16's limiter.
 * {@link #failed(Phase, String)} stays the non-resource case.</p>
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

    /**
     * 13.12: a peer {@code 0x12} received after the client's own ack-eliciting data sat unACKed for
     * at least this long, on the client's own monotonic clock, is a transport failure.
     */
    public static final long PEER_RESOURCE_DEAD_PATH_MILLIS = 10_000L;

    /**
     * What ended a {@code /3} connection, as the client saw it (docs/protocol.md 12.14, 13.12). The
     * client state that decides the class (Login Success, SERVER_SWITCH verified, {@code /3} active)
     * is kept by the plan itself ({@link #loginSucceeded()}, {@link #serverSwitchVerified()}); this
     * holds only the close and the path measurements of the moment. Immutable.
     */
    public static final class CloseEvent {
        /** Where the close came from. */
        public enum Kind {
            /** A {@code CONNECTION_CLOSE} received from the server. */
            PEER_CLOSE,
            /** The client's own QUIC idle timeout (§5 step 5: a transport failure). */
            IDLE_TIMEOUT,
            /**
             * A client-detected resource abort (outbound, pending-ACK or binding-queue wait, a pool
             * refusal or a deadline the client closes with {@code 0x12}).
             */
            LOCAL_RESOURCE_ABORT,
            /** The client's own blocked-head (unseen-prerequisite) timer expired (13.12). */
            BLOCKED_HEAD_EXPIRY,
            /** Any other client-side failure: {@code 0x10}, {@code 0x11}, an exception (non-resource). */
            LOCAL_FAILURE
        }

        private final Kind kind;
        private final CloseReason peerReason;
        private final long unackedMillis;
        private final long silentMillis;
        private final long deadlineMillis;
        private final boolean stallOrWithheld;

        private CloseEvent(Kind kind, CloseReason peerReason, long unackedMillis, long silentMillis,
                long deadlineMillis, boolean stallOrWithheld) {
            this.kind = kind;
            this.peerReason = peerReason;
            this.unackedMillis = unackedMillis;
            this.silentMillis = silentMillis;
            this.deadlineMillis = deadlineMillis;
            this.stallOrWithheld = stallOrWithheld;
        }

        /** A {@code CONNECTION_CLOSE} the server sent; {@code reason} carries its frame type, code and phrase. */
        public static CloseEvent peerClose(CloseReason reason) {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
            return new CloseEvent(Kind.PEER_CLOSE, reason, 0, 0, 0, false);
        }

        /** The client's own idle timeout. */
        public static CloseEvent idleTimeout() {
            return new CloseEvent(Kind.IDLE_TIMEOUT, null, 0, 0, 0, false);
        }

        /**
         * A resource abort the client detected and closes with {@code 0x12}.
         *
         * @param deadlineMillis the abort's whole deadline (13.12's zero-progress test spans it)
         */
        public static CloseEvent localResourceAbort(long deadlineMillis) {
            return new CloseEvent(Kind.LOCAL_RESOURCE_ABORT, null, 0, 0, deadlineMillis, false);
        }

        /**
         * The client's own blocked-head timer expired (13.12, "Deadline precedence"); the client
         * closes {@code 0x12} with the {@code unseen-prerequisite} phrase.
         *
         * @param stallOrWithheld true when the expired wait consumed recorded tick-stall time or any
         *     part of the blocked record's prerequisite chain was withheld locally (node pool, read
         *     grant or decode refusal); such an expiry is a generic resource close and never counts
         */
        public static CloseEvent blockedHeadExpiry(boolean stallOrWithheld) {
            return new CloseEvent(Kind.BLOCKED_HEAD_EXPIRY, null, 0, 0, 0, stallOrWithheld);
        }

        /** Any other client-side failure, classified non-resource. */
        public static CloseEvent localFailure() {
            return new CloseEvent(Kind.LOCAL_FAILURE, null, 0, 0, 0, false);
        }

        /**
         * The same event with the path measurements of the moment, on the client's own monotonic
         * clock (13.12 zero-progress rule).
         *
         * @param unackedMillis how long the client's own ack-eliciting data (PING/PTO probes
         *     included) had been continuously outstanding without an ACK; 0 when none was
         * @param silentMillis how long no authenticated packet of any kind had arrived from the peer
         */
        public CloseEvent path(long unackedMillis, long silentMillis) {
            return new CloseEvent(kind, peerReason, Math.max(0, unackedMillis), Math.max(0, silentMillis),
                    deadlineMillis, stallOrWithheld);
        }

        public Kind kind() {
            return kind;
        }

        /** The received close, or null for a client-side event. */
        public CloseReason peerReason() {
            return peerReason;
        }

        /**
         * 13.12's dead path: ack-eliciting data was outstanding for the WHOLE deadline and no
         * authenticated packet arrived in that time. Only a client-detected abort with a deadline
         * can be one; a blocked-head expiry never is.
         */
        boolean deadPath() {
            return deadlineMillis > 0 && unackedMillis >= deadlineMillis && silentMillis >= deadlineMillis;
        }

        /** An application {@code CONNECTION_CLOSE} with the local/resource code {@code 0x12}. */
        boolean peerResource() {
            return peerReason != null && peerReason.isApplication()
                    && peerReason.errorCode() == Protocol.CLOSE_RESOURCE;
        }
    }

    /**
     * How a {@code /3} close is treated by the client (docs/protocol.md 12.14, 13.12, Answered
     * question 5), each with its own failure-cache and limiter effect.
     */
    public enum CloseClass {
        /**
         * Not a resource close (or a pre-verification report past its allowance): 12.14's normal
         * handling, the activation-failure entry on §6's exponential schedule and the re-bind attempt
         * ends with failure.
         */
        NON_RESOURCE,
        /**
         * 13.12's zero-progress transport failure: a client-detected abort on a dead path, or the
         * client's own idle timeout. Counts like an idle timeout: §6's entry advances on its
         * exponential schedule, and during binding the limiter's non-resource schedule applies.
         */
        DEAD_PATH,
        /**
         * 13.12's asymmetric case: a peer {@code 0x12} received after the client's own data sat
         * unACKed for {@link #PEER_RESOURCE_DEAD_PATH_MILLIS}. §6's entry is written at the SHORTEST
         * step (never shortening a longer window, never raising the exponent); the limiter still
         * uses its non-resource schedule. Takes precedence over a pre-login shed.
         */
        DEAD_PATH_SHORTEST,
        /**
         * A peer {@code 0x12} before Login Success (12.13): retry TCP for this join only, no
         * failure-cache or re-bind entry, not counted toward the pre-verification allowance, and
         * no UDP-unreachable hint. Three consecutive within ten minutes start the fixed window.
         */
        PRE_LOGIN_SHED,
        /** A generic client-detected resource abort: no cache entry, the fixed 60-second window. */
        LOCAL_RESOURCE,
        /**
         * A peer {@code 0x12} after Login Success but before SERVER_SWITCH verified: the fixed window
         * while within 12.14's allowance of three per rolling ten minutes, else {@link #NON_RESOURCE}.
         */
        PEER_RESOURCE_UNVERIFIED,
        /** An authenticated peer {@code 0x12} after SERVER_SWITCH: no cache entry, the fixed window. */
        PEER_RESOURCE_VERIFIED,
        /**
         * A counted unseen-prerequisite event (13.12): a resource close like
         * {@link #PEER_RESOURCE_VERIFIED}, and two within ten minutes per (address, fingerprint)
         * write a shortest-step failure-cache entry.
         */
        UNSEEN_PREREQUISITE
    }

    /**
     * Classifies a {@code /3} close (docs/protocol.md 12.14, 13.12). A pure function of the event and
     * the client state at that moment. Order: the 13.12 zero-progress rules first (they take
     * precedence over everything, the pre-login shed included), then the pre-login shed, then the
     * pre-verification allowance, then the counted unseen-prerequisite conditions. A close that does
     * not match all of a class's conditions keeps its normal classification: a transport close
     * ({@code 0x1c}) or any code but {@code 0x12} is non-resource, and a {@code 0x12} with another
     * phrase, or before {@code /3} is active, is a generic resource close.
     *
     * @param loginSucceeded whether Login Success was received on this connection
     * @param serverSwitchVerified whether SERVER_SWITCH verified on this connection
     * @param active whether {@code /3} is active (after SERVER_SWITCH verified, not failed)
     */
    public static CloseClass classify(CloseEvent event, boolean loginSucceeded, boolean serverSwitchVerified,
            boolean active) {
        switch (event.kind()) {
            case IDLE_TIMEOUT:
                return CloseClass.DEAD_PATH;
            case PEER_CLOSE:
                if (!event.peerResource()) {
                    return CloseClass.NON_RESOURCE;
                }
                if (event.unackedMillis >= PEER_RESOURCE_DEAD_PATH_MILLIS) {
                    return CloseClass.DEAD_PATH_SHORTEST;
                }
                if (!loginSucceeded) {
                    return CloseClass.PRE_LOGIN_SHED;
                }
                if (!serverSwitchVerified) {
                    return CloseClass.PEER_RESOURCE_UNVERIFIED;
                }
                if (active && Arrays.equals(event.peerReason().reason(), CloseReason.UNSEEN_PREREQUISITE)) {
                    return CloseClass.UNSEEN_PREREQUISITE;
                }
                return CloseClass.PEER_RESOURCE_VERIFIED;
            case LOCAL_RESOURCE_ABORT:
                return event.deadPath() ? CloseClass.DEAD_PATH : CloseClass.LOCAL_RESOURCE;
            case BLOCKED_HEAD_EXPIRY:
                // An inbound wait, not one of 13.12's outbound/pending-ACK/binding-queue aborts, so
                // the zero-progress rule does not apply; a fully dead path ends in the idle timeout.
                return serverSwitchVerified && active && !event.stallOrWithheld
                        ? CloseClass.UNSEEN_PREREQUISITE : CloseClass.LOCAL_RESOURCE;
            case LOCAL_FAILURE:
            default:
                return CloseClass.NON_RESOURCE;
        }
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
    /** The player's setting, which decides whether a failure may retry over TCP at all (§4). */
    private final ConnectDecision.Mode mode;
    private final PinStore pins;
    private final FailureCache failures;
    private final RebindState rebinds;
    private final Executor worker;
    private final InetAddress address;
    private final int port;
    private final String host;
    private final int logicalPort;

    /**
     * True when this activation must not be treated as passed: the binding did not complete, the
     * pin could not be written, or the worker refused the work (12.15).
     */
    private volatile boolean activationFailed;
    private volatile boolean failureReported;
    private volatile boolean failureStateWritten;
    private volatile String reason;
    /** The class of the first reported failure, or null while nothing failed. */
    private volatile CloseClass closeClass;
    /** Set by the integration at Login Success (12.13): a later {@code 0x12} is no pre-login shed. */
    private volatile boolean loginSucceeded;
    /** Set when SERVER_SWITCH verified (12.12): a later peer {@code 0x12} is authenticated. */
    private volatile boolean serverSwitchVerified;
    /** The first {@link #activated(BindingSession)} future, so every caller sees the same answer. */
    private CompletableFuture<Boolean> activation;
    /** What {@link #isActivated()} answers before the first {@link #activated(BindingSession)}. */
    private static final CompletableFuture<Boolean> NOT_ACTIVATED =
            CompletableFuture.completedFuture(Boolean.FALSE);

    V3Plan(ConnectDecision.Mode mode, TransportSelection.PinState pinState, RebindState.Attempt attempt,
            Fingerprint fingerprint, byte[] routingDigest, PinStore pins, FailureCache failures,
            RebindState rebinds, Executor worker, InetAddress address, int port,
            String host, int logicalPort) {
        this.mode = mode == null ? ConnectDecision.Mode.AUTO : mode;
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
        if (bindingPassed) {
            // A passed binding saw SERVER_SWITCH verify, and that only happens after Login Success.
            loginSucceeded = true;
            serverSwitchVerified = true;
        }
        synchronized (this) {
            if (activation != null) {
                return activation;
            }
            if (failureReported) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
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
                // write failure: record the failure state through the one path that does, and answer
                // false (the caller closes 0x10) instead of throwing here.
                activationFailed = true;
                writeFailureStateOnce();
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
            activationFailed = true;
            return Boolean.FALSE;
        }
        boolean recorded = !online || writePin();
        activationFailed = !recorded;
        if (!recorded) {
            // 12.15: a write failure after activation is itself an activation failure, recorded once
            // so the adapter's later failed(...) cannot count it twice.
            writeFailureStateOnce();
            return Boolean.FALSE;
        }
        // 12.14: only a later successful activation clears the activation-failure state, and it
        // ends any run of consecutive pre-login sheds.
        clearActivationFailure();
        resetSheds();
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
     *     false for a {@link Phase#PLAY} failure, false for every later call, so one connection can
     *     never start two TCP joins, and false in {@code quic-only}, where the player asked for QUIC
     *     without a TCP fallback (§4) and the failure only closes the connection
     */
    public boolean failed(Phase phase, String reason) {
        return report(phase, reason, CloseClass.NON_RESOURCE);
    }

    /**
     * Marks that Login Success was received on this connection (12.13), so a later peer {@code 0x12}
     * is no longer a pre-login shed. {@link #activated(BindingSession)} with a passed binding implies it.
     */
    public void loginSucceeded() {
        loginSucceeded = true;
    }

    /**
     * Marks that SERVER_SWITCH verified on this connection (12.12), so a later peer {@code 0x12} is
     * an authenticated resource report and {@code /3} counts as active for 13.12's
     * unseen-prerequisite rule. Implies {@link #loginSucceeded()}. {@link #activated(BindingSession)}
     * with a passed binding implies it.
     */
    public void serverSwitchVerified() {
        loginSucceeded = true;
        serverSwitchVerified = true;
    }

    /**
     * Reports that the {@code /3} connection closed, classified by
     * {@link #classify(CloseEvent, boolean, boolean, boolean)} from {@code event} and this plan's
     * Login Success and SERVER_SWITCH marks (docs/protocol.md 12.14, 13.12). The retry answer is
     * exactly {@link #failed(Phase, String)}'s: a {@link Phase#JOINING} close retries over a fresh
     * TCP join once (a pre-login shed included: TCP for that join only), a {@link Phase#PLAY} close
     * does not. Only the first report of a connection classifies and writes state; the effects run on
     * the worker:
     *
     * <ul>
     *   <li>{@link CloseClass#NON_RESOURCE}: the activation-failure entry and a failed re-bind attempt;</li>
     *   <li>{@link CloseClass#DEAD_PATH}: §6's entry on its exponential schedule (like an idle
     *       timeout) and a failed re-bind attempt;</li>
     *   <li>{@link CloseClass#DEAD_PATH_SHORTEST}: §6's entry at the shortest step and a failed re-bind
     *       attempt (the limiter keeps its non-resource schedule);</li>
     *   <li>{@link CloseClass#PRE_LOGIN_SHED}: no cache or re-bind entry; three consecutive within ten
     *       minutes start the fixed window;</li>
     *   <li>{@link CloseClass#LOCAL_RESOURCE}, {@link CloseClass#PEER_RESOURCE_VERIFIED}: no cache or
     *       re-bind entry, the fixed 60-second window;</li>
     *   <li>{@link CloseClass#PEER_RESOURCE_UNVERIFIED}: the fixed window within 12.14's allowance
     *       of three per rolling ten minutes, otherwise handled like {@code 0x10};</li>
     *   <li>{@link CloseClass#UNSEEN_PREREQUISITE}: as a verified resource close, plus 13.12's
     *       counter, whose second event within ten minutes writes a shortest-step entry.</li>
     * </ul>
     *
     * @param reason the short human-readable cause for the player; null means {@link #INTERNAL_ERROR}
     * @return whether the caller must start a fresh vanilla TCP join in this same attempt
     */
    public boolean closed(Phase phase, CloseEvent event, String reason) {
        CloseClass kind;
        try {
            kind = event == null ? CloseClass.NON_RESOURCE
                    : classify(event, loginSucceeded, serverSwitchVerified,
                            serverSwitchVerified && !activationFailed);
        } catch (Throwable t) {
            kind = CloseClass.NON_RESOURCE;
        }
        return report(phase, reason, kind);
    }

    /**
     * The class of the first reported failure, or null while nothing failed. A pre-verification
     * report is {@link CloseClass#PEER_RESOURCE_UNVERIFIED} here whichever way its allowance went.
     */
    public CloseClass closeClass() {
        return closeClass;
    }

    /**
     * Whether a fallback after this plan's failure may feed the server's UDP-unreachable hint
     * (§6's {@code quicraft:fallback} report). False for a pre-login shed (12.13): the server shed
     * the connection, so its UDP port was reachable.
     */
    public boolean udpHintAllowed() {
        return closeClass != CloseClass.PRE_LOGIN_SHED;
    }

    private boolean report(Phase phase, String reason, CloseClass kind) {
        boolean retry;
        synchronized (this) {
            // The retry decision belongs to the first report only, so one connection can never start
            // two TCP joins: a later report returns false whatever phase it names. §4: quic-only has
            // no TCP fallback at all, so even a join failure only closes the connection; the failure
            // is still recorded and the attempt released.
            retry = !failureReported && phase == Phase.JOINING
                    && mode != ConnectDecision.Mode.QUIC_ONLY;
            if (!failureReported) {
                failureReported = true;
                this.reason = reason == null ? INTERNAL_ERROR : reason;
                this.closeClass = kind;
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
        final CloseClass kind;
        synchronized (this) {
            if (failureStateWritten) {
                return;
            }
            failureStateWritten = true;
            // A pin-write failure reaches here before any report: it is non-resource (12.15).
            kind = closeClass == null ? CloseClass.NON_RESOURCE : closeClass;
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyFailureState(kind);
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

    /** The worker half of a failure: each class's failure-cache and limiter effect (12.14, 13.12). */
    private void applyFailureState(CloseClass kind) {
        switch (kind) {
            case PRE_LOGIN_SHED:
                releaseAttempt();
                if (rebinds != null) {
                    rebinds.recordPreLoginShed(host, logicalPort);
                }
                return;
            case LOCAL_RESOURCE:
            case PEER_RESOURCE_VERIFIED:
                resourceWindow();
                return;
            case UNSEEN_PREREQUISITE:
                resourceWindow();
                failures.recordUnseenPrerequisite(address, port, fingerprint);
                return;
            case PEER_RESOURCE_UNVERIFIED:
                resetSheds();
                // Fail closed: a report the state cannot record is treated like 0x10.
                if (rebinds != null && rebinds.recordPeerResourceReport(host, logicalPort)) {
                    releaseAttempt();
                    return;
                }
                recordActivationFailure();
                endAttempt(false);
                return;
            case DEAD_PATH:
                resetSheds();
                failures.recordFailure(address, port, fingerprint);
                endAttempt(false);
                return;
            case DEAD_PATH_SHORTEST:
                resetSheds();
                failures.recordShortestFailure(address, port, fingerprint);
                endAttempt(false);
                return;
            case NON_RESOURCE:
            default:
                resetSheds();
                recordActivationFailure();
                endAttempt(false);
        }
    }

    /** 12.14's fixed window for a resource close; the re-bind attempt ends without a failure. */
    private void resourceWindow() {
        resetSheds();
        releaseAttempt();
        if (rebinds != null) {
            rebinds.startResourceWindow(host, logicalPort);
        }
    }

    /** Ends a run of consecutive pre-login sheds (12.14): this outcome was not a shed. */
    private void resetSheds() {
        if (rebinds == null || host == null) {
            return;
        }
        try {
            rebinds.resetPreLoginSheds(host, logicalPort);
        } catch (Throwable ignored) {
            // The state disables itself on a failed write.
        }
    }

    /** Ends the re-bind attempt without advancing its window: a resource close (12.14). */
    private void releaseAttempt() {
        if (rebinds == null || attempt == null) {
            return;
        }
        try {
            rebinds.releaseAttempt(attempt);
        } catch (Throwable ignored) {
            // The state disables itself on a failed write.
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

    /**
     * Whether this plan reported a successful activation through {@link #activated(BindingSession)}:
     * true only once that call's future has completed true, so a caller that has not been answered
     * yet, or was answered false, is never told the activation passed.
     */
    public boolean isActivated() {
        return Boolean.TRUE.equals(activationFuture().getNow(Boolean.FALSE)) && !failureReported;
    }

    private CompletableFuture<Boolean> activationFuture() {
        synchronized (this) {
            return activation == null ? NOT_ACTIVATED : activation;
        }
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
