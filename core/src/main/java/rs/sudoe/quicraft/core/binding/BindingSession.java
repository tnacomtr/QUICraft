// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingStateMachine.Role;

/**
 * The binding session of one {@code /3} connection: it drives {@link BindingStateMachine} through
 * the exact message order, deadlines and switch boundaries of docs/protocol.md 12.4 to 12.14 and
 * tells the adapter what to do through {@link BindingDriver.Callbacks}.
 *
 * <p>It owns no I/O, no cipher and no framing: the adapter does all three. What it owns is the
 * sequence, so a wrong order, a wrong metadata value, a wrong MAC, a missing OFFER, a missed
 * deadline or a quiescence overflow always ends the same way: {@code 0x10}
 * ({@link Protocol#CLOSE_BINDING_FAILURE}) and no attempt to continue half-switched (12.14).</p>
 *
 * <p>The exchange, with the spec section that fixes each step:</p>
 *
 * <ol>
 *   <li>The adapter drains its earlier outbound writes and calls
 *       {@link #onConfigurationEntered(long)} (12.4, 12.10). The session resolves the exporter
 *       value and starts the 10 second activation deadline (12.7, 12.14).</li>
 *   <li>Server: OFFER goes out (12.9) and ordinary outbound packets are queued from here.
 *       Client: nothing goes out until a matching OFFER arrives.</li>
 *   <li>Server: CLIENT_PROOF verified, SERVER_PROOF sent. Client: OFFER verified, CLIENT_PROOF
 *       sent, and ordinary outbound packets are queued from here (12.10).</li>
 *   <li>Client: SERVER_PROOF verified, CLIENT_SWITCH sent, then
 *       {@link BindingDriver.Callbacks#activateOutbound()} (12.11). Server: CLIENT_PROOF was
 *       answered already; it now waits for CLIENT_SWITCH.</li>
 *   <li>Server: CLIENT_SWITCH verified, SERVER_SWITCH handed to {@code send}, then
 *       {@link BindingDriver.Callbacks#activateInbound()} and
 *       {@link BindingDriver.Callbacks#activateOutbound()} (12.11, 12.12). Client: SERVER_SWITCH
 *       verified, then {@link BindingDriver.Callbacks#activateInbound()} (12.12).</li>
 *   <li>The adapter calls {@link #releaseQueued()} and encodes those packets anew as §13 records
 *       (12.13).</li>
 * </ol>
 *
 * <p>Deadlines: 10 seconds total from entering configuration to activation, and, on the client,
 * the {@code offerWaitNanos} the constructor was given for a missing OFFER (12.14; a changed-pin
 * re-bind passes 12.16's bounded wait, twice the measured handshake RTT clamped to 500 ms..3 s).
 * Both are checked by {@link #tick(long)}. A session built with a scheduler ticks itself, so its
 * deadlines fire even when the adapter never calls {@code tick}; without one the adapter must tick.</p>
 *
 * <p>Not thread-safe: every method runs on the connection's event loop, which is also what 12.10
 * requires for the cutover. Nothing here logs, and no failure reason carries a secret, an exporter
 * value, a tag or payload bytes.</p>
 */
public final class BindingSession implements BindingDriver {
    /** 12.14: ten seconds total from entering configuration to activation. */
    public static final long ACTIVATION_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** 12.10: at most 256 queued ordinary packets per endpoint. */
    public static final int MAX_QUEUED_PACKETS = 256;
    /** 12.10: at most 8 MiB of queued ordinary packets per endpoint. */
    public static final long MAX_QUEUED_BYTES = 8L << 20;
    /**
     * How often a session that was given a scheduler checks its own deadlines (12.14). Fine enough
     * for a ten second deadline and a re-bind's sub-second offer wait, and cheap enough to leave
     * running for the exchange's lifetime.
     */
    static final long SELF_TICK_MILLIS = 100;

    /** The only accepted digest length for F and R, docs/protocol.md 12.7. */
    private static final int DIGEST_LENGTH = 32;

    private final Role role;
    private final ExporterSource exporter;
    private final Callbacks callbacks;
    /** Wiped and dropped once the machine has its own copy, and on every way this session ends. */
    private byte[] sharedSecret;
    private final int mode;
    private final int protocol;
    /** N, the shard count the preamble exchange selected (13.2, 12.7). */
    private final int shardCount;
    private final byte[] fingerprint;
    private final byte[] routing;
    private final LongSupplier clock;
    private final long offerWaitNanos;
    /** Optional: a scheduler the session ticks itself with, so deadlines do not need the adapter. */
    private final ScheduledExecutorService scheduler;
    /** Optional: whether the caller is on the connection's event loop, for the self-tick check. */
    private final BooleanSupplier inEventLoop;

    private final List<Object> queued = new ArrayList<Object>();
    private long queuedBytes;

    private BindingStateMachine machine;
    private long enteredAtNanos;
    private boolean entered;
    private boolean offerSeen;
    private boolean quiescent;
    private boolean settled;
    private boolean activated;
    private ScheduledFuture<?> ticker;

    /**
     * @param role which endpoint this session is
     * @param exporter the TLS exporter value of 12.7, resolved when the exchange starts
     * @param sharedSecret Minecraft's 16-byte {@code S} in online mode; ignored in offline mode.
     *     The session keeps a private copy (12.6), so the caller may wipe its own array as soon as
     *     this returns
     * @param mode {@link BindingStateMachine#MODE_ONLINE} or
     *     {@link BindingStateMachine#MODE_OFFLINE} (12.7, 12.19)
     * @param protocol the Minecraft protocol number both peers must agree on (12.8)
     * @param advertisedFingerprint F, the certificate fingerprint in the advertisement (12.7)
     * @param routingDigest R, the routing resource digest of 13.7
     * @param clock the monotonic nanosecond clock this session's timestamps come from
     * @param offerWaitNanos the client's bounded wait for OFFER (12.16); 0 or less means no
     *     separate OFFER wait, so only the 10 second deadline applies
     * @param callbacks what the adapter does for this session
     * @param scheduler an optional scheduler the session ticks itself with, so 12.14's deadlines
     *     fire even when the adapter never calls {@link #tick(long)}; null means the adapter must
     *     tick. <b>It must be the connection's own event loop</b>, the one thread that runs every
     *     other method here (12.10), normally the {@code Platform.scheduler()} of
     *     {@link rs.sudoe.quicraft.core.connect.ClientConnector.Platform}. The session cancels its
     *     own task when it settles
     * @param inEventLoop an optional check of whether the current thread is that event loop, used by
     *     the self-tick before it touches the session; null means the caller asserts the requirement
     *     by passing the right scheduler. A self-tick that finds itself off the event loop does
     *     nothing, because touching the session from another thread would be worse than a late
     *     deadline
     * @throws IllegalArgumentException for local misuse, which is a wiring bug and not a wire event
     */
    public BindingSession(Role role, ExporterSource exporter, byte[] sharedSecret, int mode, int protocol,
            byte[] advertisedFingerprint, byte[] routingDigest, LongSupplier clock, long offerWaitNanos,
            Callbacks callbacks, ScheduledExecutorService scheduler, BooleanSupplier inEventLoop) {
        this(role, exporter, sharedSecret, mode, protocol, 1, advertisedFingerprint, routingDigest, clock,
                offerWaitNanos, callbacks, scheduler, inEventLoop);
    }

    /**
     * As the constructor above, bound to the shard count N that the preamble exchange selected
     * (13.2). Use this form on {@code /3}: the binding must carry the same N the preambles agreed on,
     * so a peer that binds a different N fails the exchange (12.7).
     *
     * @param shardCount N, 1..5, from {@link rs.sudoe.quicraft.core.multistream.SessionContext#shardCount()}
     * @throws IllegalArgumentException for local misuse, including an N outside 1..5
     */
    public BindingSession(Role role, ExporterSource exporter, byte[] sharedSecret, int mode, int protocol,
            int shardCount, byte[] advertisedFingerprint, byte[] routingDigest, LongSupplier clock,
            long offerWaitNanos, Callbacks callbacks, ScheduledExecutorService scheduler,
            BooleanSupplier inEventLoop) {
        if (shardCount < 1 || shardCount > 5) {
            throw new IllegalArgumentException("shard count must be 1..5, got " + shardCount);
        }
        this.shardCount = shardCount;
        this.role = Objects.requireNonNull(role, "role");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (mode != BindingStateMachine.MODE_OFFLINE && mode != BindingStateMachine.MODE_ONLINE) {
            throw new IllegalArgumentException("mode must be 0 (offline) or 1 (online)");
        }
        if (protocol < 0) {
            // §12.8: protocol_number is an unsigned u32; a negative one is a caller that wrapped it.
            throw new IllegalArgumentException("protocol number must be in unsigned 32-bit range, got "
                    + protocol);
        }
        checkDigest(advertisedFingerprint, "advertisedFingerprint");
        checkDigest(routingDigest, "routingDigest");
        if (mode == BindingStateMachine.MODE_ONLINE
                && (sharedSecret == null || sharedSecret.length != 16)) {
            throw new IllegalArgumentException("online mode needs Minecraft's 16-byte shared secret");
        }
        if (offerWaitNanos < 0) {
            throw new IllegalArgumentException("offerWaitNanos must not be negative");
        }
        this.sharedSecret = sharedSecret == null ? null : sharedSecret.clone();
        this.mode = mode;
        this.protocol = protocol;
        this.fingerprint = advertisedFingerprint.clone();
        this.routing = routingDigest.clone();
        this.offerWaitNanos = offerWaitNanos;
        this.scheduler = scheduler;
        this.inEventLoop = inEventLoop;
    }

    /** Whether the exchange finished with activation (12.9). */
    public boolean isActivated() {
        return activated;
    }

    /**
     * The binding mode this session ran in: {@link BindingStateMachine#MODE_ONLINE} when the login
     * was authenticated (12.7) or {@link BindingStateMachine#MODE_OFFLINE} for the explicit offline
     * test profile (12.19). Only an online session may write an authenticated pin (12.15).
     */
    public int mode() {
        return mode;
    }

    /** Whether the session is over, activated or failed; every later call is a no-op. */
    public boolean isSettled() {
        return settled;
    }

    /**
     * Whether the session is currently pausing ordinary outbound packets, i.e.
     * {@link #queueOrdinary(Object, int)} would queue them (12.10).
     */
    public boolean isQuiescent() {
        return quiescent;
    }

    @Override
    public void onConfigurationEntered(long nowNanos) {
        if (settled || entered) {
            return;
        }
        entered = true;
        enteredAtNanos = nowNanos;
        startTicker();
        try {
            BindingMachineResult built = role == Role.SERVER
                    ? BindingStateMachine.server(faultCheckedExporter(), sharedSecret, mode, protocol,
                            shardCount, fingerprint, routing)
                    : BindingStateMachine.client(faultCheckedExporter(), sharedSecret, mode, protocol,
                            shardCount, fingerprint, routing);
            if (built.isFailed()) {
                // 12.3, 12.14: an exporter error is fatal, never permission to continue.
                fail("binding: " + built.failure().name());
                return;
            }
            machine = built.machine();
            if (role == Role.SERVER) {
                // 12.10: the adapter drained its earlier writes before this call, and ordinary
                // outbound packets are queued from here until activation.
                quiescent = true;
                send(machine.start().message());
            }
        } catch (Throwable t) {
            fail("binding start", t);
        } finally {
            // 12.6: the machine derived its key from a private copy, so this session's copy of S is
            // dropped as soon as the exchange has started, whatever happened.
            wipeSecret();
        }
    }

    @Override
    public void onPayload(byte[] payload) {
        if (settled) {
            return;
        }
        try {
            if (machine == null) {
                // 12.9: an unsolicited payload before the exchange began terminates /3.
                fail("binding: unsolicited payload");
                return;
            }
            Faults.check(Faults.BINDING_STEP);
            BindingResult result = machine.receive(payload);
            if (result.isFailed()) {
                fail("binding: " + result.failure().name());
                return;
            }
            if (result.isSend()) {
                step(result.message());
                return;
            }
            // ACTIVE. The server still has to write SERVER_SWITCH (12.12); the client has nothing
            // left to send.
            if (result.message() != null) {
                step(result.message());
            }
            activate();
        } catch (Throwable t) {
            fail("binding step", t);
        }
    }

    @Override
    public QueueResult queueOrdinary(Object packet, int bytes) {
        if (packet == null) {
            throw new IllegalArgumentException("packet is required");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        if (settled || !quiescent) {
            return QueueResult.SEND_NOW;
        }
        if (queued.size() + 1 > MAX_QUEUED_PACKETS || queuedBytes + bytes > MAX_QUEUED_BYTES) {
            // 12.10: overflow disconnects; the packets are dropped with the connection.
            fail("binding: quiescence queue overflow");
            return QueueResult.DROPPED_FAILED;
        }
        queued.add(packet);
        queuedBytes += bytes;
        return QueueResult.QUEUED;
    }

    @Override
    public List<Object> releaseQueued() {
        List<Object> released = new ArrayList<Object>(queued);
        queued.clear();
        queuedBytes = 0;
        return released;
    }

    @Override
    public void tick(long nowNanos) {
        if (settled || !entered) {
            return;
        }
        try {
            // The session's own clock is the authority, so a caller that ticks with an earlier
            // reading than the clock's cannot extend the deadline.
            long now = Math.max(nowNanos, clock.getAsLong());
            long elapsed = now - enteredAtNanos;
            if (elapsed >= ACTIVATION_DEADLINE_NANOS) {
                fail("binding: activation deadline");
                return;
            }
            if (role == Role.CLIENT && !offerSeen && offerWaitNanos > 0 && elapsed >= offerWaitNanos) {
                fail("binding: no OFFER within the offer wait");
            }
        } catch (Throwable t) {
            fail("binding tick", t);
        }
    }

    /**
     * Starts the session's own deadline checks when it was given a scheduler, so 12.14's timers fire
     * even if the adapter never ticks. A scheduler that refuses the task leaves the adapter's own
     * {@link #tick(long)} calls in charge.
     */
    private void startTicker() {
        if (scheduler == null) {
            return;
        }
        try {
            ticker = scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (inEventLoop != null && !inEventLoop.getAsBoolean()) {
                            // The scheduler is not the connection's event loop, so touching the
                            // session from here would break 12.10 worse than a late deadline does.
                            // The adapter's own tick() calls still work.
                            return;
                        }
                        tick(clock.getAsLong());
                    } catch (Throwable ignored) {
                        // tick() never throws; a scheduler thread must not die on it either.
                    }
                }
            }, SELF_TICK_MILLIS, SELF_TICK_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // No self-ticking; the adapter's tick() calls still work.
        }
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    /**
     * Drops this session's own copy of Minecraft's shared secret (12.6). Best effort, like every
     * wipe here: the JVM may have copied the material, but nothing of this session's may survive the
     * exchange.
     */
    private void wipeSecret() {
        HkdfSha256.wipe(sharedSecret);
        sharedSecret = null;
    }

    /**
     * Ends this session without reporting anything, for a connection that is already gone: the
     * adapter closed the channel, so there is nobody left to close it with {@code 0x10} (12.14). It
     * stops the self-tick, drops the quiescence queue, wipes the machine's tags and this session's
     * copy of {@code S}, and leaves the session settled, so every later call is a no-op. Idempotent,
     * and never throws.
     *
     * <p>Nothing is reported from here, so the caller hands the quiescence queue back (through
     * {@link #releaseQueued()}) before closing if it still holds reservations; the platform's
     * teardown does exactly that, and only then closes the session.</p>
     */
    public void close() {
        if (!settled) {
            settled = true;
            activated = false;
            quiescent = false;
            queued.clear();
            queuedBytes = 0;
            try {
                if (machine != null) {
                    machine.abort();
                }
            } catch (Throwable ignored) {
                // Wiping the machine's tags is best effort.
            }
        }
        stopTicker();
        wipeSecret();
    }

    /** Sends one message and applies the cutover the boundary it crosses requires. */
    private void step(BindingMessage message) {
        BindingKind kind = message.kind();
        if (kind == BindingKind.CLIENT_PROOF) {
            // 12.10: the client drained its earlier writes before this CLIENT_PROOF (it is the
            // response to OFFER), and ordinary outbound packets are queued from here on.
            quiescent = true;
            offerSeen = true;
            send(message);
            return;
        }
        if (kind == BindingKind.CLIENT_SWITCH) {
            // 12.11: CLIENT_SWITCH is the last AES/CFB8-encrypted packet in this direction.
            send(message);
            Faults.check(Faults.LANE_ACTIVATE);
            callbacks.activateOutbound();
            return;
        }
        // SERVER_PROOF and SERVER_SWITCH: SERVER_SWITCH's cutover is applied in activate(), after
        // this call, so the whole encrypted frame is queued first (12.12).
        send(message);
    }

    /**
     * Activation completed: the peer's final switch packet verified. The cutover of 12.11 and 12.12
     * is reported in the order those sections require, and the session settles.
     */
    private void activate() {
        Faults.check(Faults.LANE_ACTIVATE);
        if (role == Role.SERVER) {
            // 12.11: the server decrypted through the final byte of CLIENT_SWITCH and validated it,
            // so its inbound cipher goes off now. 12.12: SERVER_SWITCH was queued in step(), so its
            // outbound cipher goes off after it. 13.3: both take effect on the event loop in the
            // same task as the SERVER_SWITCH flush.
            callbacks.activateInbound();
            callbacks.activateOutbound();
        } else {
            // 12.12: the client decrypted exactly through SERVER_SWITCH and starts the §13 parser on
            // the very next byte.
            callbacks.activateInbound();
        }
        quiescent = false;
        activated = true;
        settled = true;
        stopTicker();
        wipeSecret();
    }

    /**
     * Ends the session with {@code 0x10} (12.14): the adapter closes the whole connection, the
     * machine's precomputed tags are wiped, the quiescence queue is dropped and every later call
     * becomes a no-op. Idempotent, so the first failure wins.
     *
     * <p>The adapter is told before this session drops the queue, because the objects in it are the
     * adapter's: each one carries the bytes it holds, and only the adapter can give those
     * reservations back (the platform's teardown releases them from this very callback). Clearing
     * first would strand every reservation still queued when the failure happened.</p>
     */
    private void fail(String reason) {
        if (settled) {
            return;
        }
        settled = true;
        activated = false;
        quiescent = false;
        stopTicker();
        wipeSecret();
        try {
            if (machine != null) {
                machine.abort();
            }
        } catch (Throwable ignored) {
            // Wiping the machine's tags is best effort: the connection is closing either way.
        }
        try {
            callbacks.fail(Protocol.CLOSE_BINDING_FAILURE, reason);
        } catch (Throwable ignored) {
            // A callback that throws must not leave the session unsettled or escape into the event
            // loop: the session is already settled, and 12.14's answer is the close it just asked for.
        }
        queued.clear();
        queuedBytes = 0;
    }

    /**
     * The same failure, naming only the exception's class: a message could carry payload or secret
     * material, which 12.14 forbids in logs and reasons.
     */
    private void fail(String reason, Throwable cause) {
        fail(reason + ": " + cause.getClass().getName());
    }

    private void send(BindingMessage message) {
        callbacks.send(message.encode());
    }

    /**
     * The exporter source with the {@link Faults#BINDING_EXPORTER} stage in it, so a fault lands
     * exactly where a missing native does: inside the export call, which
     * {@link BindingStateMachine} already turns into {@link BindingFailure#EXPORTER_ERROR}.
     */
    private ExporterSource faultCheckedExporter() {
        return new ExporterSource() {
            @Override
            public byte[] export() {
                Faults.check(Faults.BINDING_EXPORTER);
                return exporter.export();
            }
        };
    }

    private static void checkDigest(byte[] value, String name) {
        if (value == null || value.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException(name + " must be " + DIGEST_LENGTH + " raw bytes");
        }
    }

    @Override
    public String toString() {
        return "BindingSession{role=" + role + ", mode=" + mode + ", protocol=" + protocol
                + ", entered=" + entered + ", settled=" + settled + ", activated=" + activated + '}';
    }
}
