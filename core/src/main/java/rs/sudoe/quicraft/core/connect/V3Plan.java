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
 * {@link #activated()} or {@link #failed(boolean)}, which is the only place 12.15's pin write and
 * 12.16's re-bind bookkeeping happen.</p>
 *
 * <p>Both reporting calls run their file work on the connector's worker executor, never on the
 * caller's event loop: {@link PinStore#recordPin(String, int, String)} and
 * {@link RebindState#endAttempt(RebindState.Attempt, boolean)} write and fsync the whole state file,
 * which the pin and re-bind packages explicitly forbid on a Netty event loop. {@link #activated()}
 * therefore returns a future rather than a boolean, so a pin write that failed can close the
 * connection with {@code 0x10} (12.15) without blocking the event loop. {@link #failed(boolean)}
 * can answer synchronously because its answer is policy, not a write result: only a changed-pin
 * re-bind may start a fresh TCP join (12.16).</p>
 *
 * <p>Every state call fails closed: a failure cache or pin write that throws is treated as "the
 * write did not happen", so the pin is never assumed written and the backoff never assumed clear.</p>
 */
public final class V3Plan {
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
    private boolean settled;
    private boolean pinWriteFailed;

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
     * <p>The file work runs on the worker executor, so this never blocks the event loop.</p>
     *
     * @return a future that completes true when activation's bookkeeping succeeded, and false when
     *     the pin could not be written or the store refused it. A false result is 12.15's write
     *     failure: the caller closes the connection with {@code 0x10}, leaves the pin as it was and
     *     does not downgrade the live connection. In offline mode (or with no pin store) there is
     *     nothing to write and the future completes true.
     */
    public CompletableFuture<Boolean> activated() {
        if (settled) {
            return CompletableFuture.completedFuture(Boolean.valueOf(!pinWriteFailed));
        }
        settled = true;
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
     * Reports that the {@code /3} attempt failed (12.14, 12.16): the failure is recorded in §6's
     * cache, the re-bind attempt ends with failure and its backoff window advances.
     *
     * <p>The file work runs on the worker executor; the answer below does not depend on it.</p>
     *
     * @param afterConnection true when the failure happened after a {@code /3} QUIC connection was
     *     created, which makes it an activation failure that overrides a previous handshake success
     *     (12.14); false for a failure before any connection, which is an ordinary §6 entry
     * @return whether the caller may start a fresh vanilla TCP join for this attempt, which only the
     *     changed-pin re-bind of 12.16 allows; an ordinary activation failure never replays the
     *     login over TCP, it just ends this attempt
     */
    public boolean failed(final boolean afterConnection) {
        if (!settled) {
            settled = true;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    if (afterConnection) {
                        recordActivationFailure();
                    } else {
                        recordFailure();
                    }
                    endAttempt(false);
                }
            });
        }
        return pinState == TransportSelection.PinState.CHANGED;
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

    private void recordFailure() {
        try {
            failures.recordFailure(address, port, fingerprint);
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
