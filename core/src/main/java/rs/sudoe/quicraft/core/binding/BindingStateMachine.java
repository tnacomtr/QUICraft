// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.security.MessageDigest;

/**
 * The binding state machine of docs/protocol.md §12.9 for one endpoint of one connection.
 *
 * <p>It accepts exactly OFFER, CLIENT_PROOF, SERVER_PROOF, CLIENT_SWITCH, SERVER_SWITCH, in that
 * order, one each, in the specified direction and configuration state: the client responds only to
 * a matching OFFER; the server sends SERVER_PROOF only after verifying CLIENT_PROOF; the client
 * sends CLIENT_SWITCH only after verifying SERVER_PROOF; the server sends SERVER_SWITCH only after
 * verifying CLIENT_SWITCH. Unknown versions or kinds, duplicates, reflection, mismatched metadata,
 * unsolicited payloads and invalid MACs terminate the attempt.</p>
 *
 * <p>It is transport-independent: it does no I/O, it never touches the network or a cipher, and it
 * never logs. The transport supplies the exporter value through {@link ExporterSource}, feeds
 * received payloads to {@link #receive(byte[])} and sends whatever {@link BindingResult} asks for.
 * The cipher cutover and quiescence rules of §12.10-12.13 are the transport's responsibility.</p>
 *
 * <p>Machines are built through {@link #client} and {@link #server}, which resolve the exporter
 * value first. An exporter that fails is therefore reported as the typed
 * {@link BindingFailure#EXPORTER_ERROR} of {@link BindingMachineResult} instead of thrown
 * (docs/protocol.md §12.3, §12.14). The machine derives from a private copy of the exporter value:
 * the {@link ExporterSource} keeps ownership of the array it returns, which is never modified.</p>
 *
 * <p>Secret hygiene: {@code S}, the intermediate PRK, {@code K}, the exporter value {@code E} and
 * the transcript are all wiped as soon as the per-kind tags have been derived, which happens while
 * the machine is built. Only the per-kind tags survive until the exchange settles or is aborted,
 * then they are wiped too.
 * This is best effort: the JVM may have copied the material (for example inside a
 * {@code SecretKeySpec} or a {@code Mac}) and Java has no way to erase those copies.</p>
 *
 * <p>Not thread-safe. Every call ({@link #start()}, {@link #receive(byte[])}, {@link #abort()})
 * must run on the one thread that owns the connection, normally its event loop.</p>
 */
public final class BindingStateMachine {
    /** online mode, docs/protocol.md §12.7. */
    public static final int MODE_ONLINE = 1;
    /** offline test mode, docs/protocol.md §12.19. */
    public static final int MODE_OFFLINE = 0;

    /** The only accepted exporter value length, docs/protocol.md §12.7. */
    private static final int EXPORTER_LENGTH = 32;

    public enum Role {
        CLIENT,
        SERVER
    }

    private final Role role;
    private final int mode;
    private final int protocolNumber;
    private final int shardCount;
    private final byte[] fingerprint;
    private final byte[] routing;

    private final byte[][] tags = new byte[5][];

    private BindingKind expected;
    private BindingKind lastReceived;
    private boolean started;
    private boolean settled;
    private boolean activated;
    private BindingFailure failure;

    private BindingStateMachine(Role role, byte[] exporterValue, byte[] minecraftSecret,
                                int mode, int protocolNumber, int shardCount, byte[] fingerprint, byte[] routing) {
        validateArguments(minecraftSecret, mode, protocolNumber, shardCount, fingerprint, routing);
        if (exporterValue == null || exporterValue.length != EXPORTER_LENGTH) {
            throw new IllegalArgumentException("exporter value must be exactly 32 raw bytes");
        }
        this.role = role;
        this.mode = mode;
        this.protocolNumber = protocolNumber;
        if (shardCount < 1 || shardCount > 5) throw new IllegalArgumentException("shard count must be 1..5");
        this.shardCount = shardCount;
        this.fingerprint = fingerprint.clone();
        this.routing = routing.clone();
        this.expected = role == Role.CLIENT ? BindingKind.OFFER : null;
        deriveTags(exporterValue, minecraftSecret);
    }

    /**
     * A client-side machine. The next inbound message must be OFFER.
     *
     * <p>Defaults the negotiated shard count to N=1 (12.8); a /3 caller must use the overload that takes N.
     * @return a machine, or {@link BindingFailure#EXPORTER_ERROR} when the exporter value cannot be
     *     obtained
     */
    public static BindingMachineResult client(ExporterSource exporter, byte[] minecraftSecret,
                                              int mode, int protocolNumber,
                                              byte[] fingerprint, byte[] routing) {
        return client(exporter, minecraftSecret, mode, protocolNumber, 1, fingerprint, routing);
    }

    /** Builds a client machine bound to negotiated N (12.7). */
    public static BindingMachineResult client(ExporterSource exporter, byte[] secret, int mode,
            int protocolNumber, int shardCount, byte[] fingerprint, byte[] routing) {
        return build(Role.CLIENT, exporter, secret, mode, protocolNumber, shardCount, fingerprint, routing);
    }

    /**
     * A server-side machine. The first action is {@link #start()}, which yields OFFER.
     *
     * <p>Defaults the negotiated shard count to N=1 (12.8); a /3 caller must use the overload that takes N.
     * @return a machine, or {@link BindingFailure#EXPORTER_ERROR} when the exporter value cannot be
     *     obtained
     */
    public static BindingMachineResult server(ExporterSource exporter, byte[] minecraftSecret,
                                              int mode, int protocolNumber,
                                              byte[] fingerprint, byte[] routing) {
        return server(exporter, minecraftSecret, mode, protocolNumber, 1, fingerprint, routing);
    }

    /** Builds a server machine bound to negotiated N (12.7). */
    public static BindingMachineResult server(ExporterSource exporter, byte[] secret, int mode,
            int protocolNumber, int shardCount, byte[] fingerprint, byte[] routing) {
        return build(Role.SERVER, exporter, secret, mode, protocolNumber, shardCount, fingerprint, routing);
    }

    private static BindingMachineResult build(Role role, ExporterSource exporter,
                                              byte[] minecraftSecret, int mode, int protocolNumber,
                                              int shardCount, byte[] fingerprint, byte[] routing) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter is required");
        }
        // Local misuse stays an exception; only the exporter itself can fail at run time.
        validateArguments(minecraftSecret, mode, protocolNumber, shardCount, fingerprint, routing);
        byte[] exporterValue = export(exporter);
        if (exporterValue == null) {
            return BindingMachineResult.failed(BindingFailure.EXPORTER_ERROR);
        }
        return BindingMachineResult.ok(new BindingStateMachine(role, exporterValue, minecraftSecret,
                mode, protocolNumber, shardCount, fingerprint, routing));
    }

    /**
     * Resolves the exporter value into a private copy this machine owns, or null for an exporter
     * error: a source that throws, returns null or returns another length (docs/protocol.md §12.3,
     * §12.14). Only the copy is ever wiped, so the source keeps ownership of its own array.
     *
     * <p>{@link Throwable} is caught, not just {@link RuntimeException}: the exporter is reached
     * through the patched native's JNI binding, so a missing or mismatched native library surfaces
     * as {@link UnsatisfiedLinkError} or another {@link LinkageError}. §12.8 requires exactly that:
     * catch exporter errors, native exceptions included, and close {@code /3} with {@code 0x10},
     * never letting an exception bypass fail-closed handling. An error is therefore an ordinary
     * {@link BindingFailure#EXPORTER_ERROR} here, and never escapes the machine factories.
     */
    private static byte[] export(ExporterSource exporter) {
        byte[] returned;
        try {
            returned = exporter.export();
        } catch (Throwable ignored) {
            return null;
        }
        // Check the length before copying, so a rejected value never leaves an unwiped copy.
        if (returned == null || returned.length != EXPORTER_LENGTH) {
            return null;
        }
        return returned.clone();
    }

    private static void validateArguments(byte[] minecraftSecret, int mode, int protocolNumber,
                                           int shardCount, byte[] fingerprint, byte[] routing) {
        if (mode != MODE_OFFLINE && mode != MODE_ONLINE) {
            throw new IllegalArgumentException("mode must be 0 (offline) or 1 (online)");
        }
        if (protocolNumber < 0) {
            // §12.8: protocol_number is an unsigned u32, so a negative value can only be a caller
            // that truncated or wrapped it; reject it before any tag is derived from it.
            throw new IllegalArgumentException("protocol number must be in unsigned 32-bit range, got "
                    + protocolNumber);
        }
        checkDigest(fingerprint, "fingerprint");
        checkDigest(routing, "routing");
        if (shardCount < 1 || shardCount > 5) {
            throw new IllegalArgumentException("shard count must be 1..5");
        }
        if (mode == MODE_ONLINE && (minecraftSecret == null || minecraftSecret.length != 16)) {
            throw new IllegalArgumentException("online mode needs Minecraft's 16-byte shared secret");
        }
    }

    /**
     * Precomputes the tags for the four authenticated kinds, then wipes every secret input,
     * including the machine's own copy of the exporter value. In offline mode the tags are all
     * zeros (docs/protocol.md §12.19) and no key is derived.
     */
    private void deriveTags(byte[] exporterValue, byte[] minecraftSecret) {
        byte[] transcript = BindingTranscript.compute(protocolNumber, mode, fingerprint, routing, shardCount);
        byte[] secretCopy = minecraftSecret == null ? null : minecraftSecret.clone();
        byte[] key = null;
        try {
            if (mode == MODE_ONLINE) {
                key = BindingKeys.derive(secretCopy);
            }
            for (BindingKind kind : BindingKind.values()) {
                if (kind == BindingKind.OFFER || mode == MODE_OFFLINE) {
                    tags[kind.code()] = BindingTags.zeros();
                } else {
                    tags[kind.code()] = BindingTags.tag(key, kind, transcript, exporterValue);
                }
            }
        } finally {
            HkdfSha256.wipe(key);
            HkdfSha256.wipe(secretCopy);
            HkdfSha256.wipe(exporterValue);
            HkdfSha256.wipe(transcript);
        }
    }

    public Role role() {
        return role;
    }

    public int mode() {
        return mode;
    }

    public boolean isActivated() {
        return activated;
    }

    public boolean isSettled() {
        return settled;
    }

    /** The kind the next inbound message must have, or null once settled. */
    public BindingKind expectedKind() {
        return settled ? null : expected;
    }

    /**
     * Server only: emits OFFER and arms the machine for CLIENT_PROOF. Throws for a client, which
     * never speaks first, and on a second call.
     */
    public BindingResult start() {
        if (role != Role.SERVER) {
            throw new UnsupportedOperationException("the client never sends OFFER");
        }
        if (started || settled) {
            throw new IllegalStateException("the binding exchange has already started");
        }
        started = true;
        expected = BindingKind.CLIENT_PROOF;
        return BindingResult.send(offer());
    }

    /** Feeds one received payload through decode, direction, order, metadata and MAC validation. */
    public BindingResult receive(byte[] payload) {
        if (settled) {
            return BindingResult.failed(failure != null ? failure : BindingFailure.ALREADY_SETTLED);
        }
        BindingMessage message;
        try {
            message = BindingMessage.decode(payload);
        } catch (BindingFormatException e) {
            return settleFailed(e.reason());
        }
        BindingKind kind = message.kind();

        // Direction: this endpoint only accepts messages sent by the peer.
        boolean peerDirection = role == Role.CLIENT ? kind.fromServer() : !kind.fromServer();
        if (!peerDirection) {
            return settleFailed(BindingFailure.WRONG_DIRECTION);
        }
        if (kind == lastReceived) {
            return settleFailed(BindingFailure.DUPLICATE);
        }
        if (kind != expected) {
            return settleFailed(BindingFailure.WRONG_ORDER);
        }

        if (message.mode() != mode) {
            return settleFailed(BindingFailure.WRONG_MODE);
        }
        if (message.protocolNumber() != protocolNumber) {
            return settleFailed(BindingFailure.WRONG_PROTOCOL);
        }
        if (message.shardCount() != shardCount) {
            return settleFailed(BindingFailure.WRONG_SHARD_COUNT);
        }
        if (!MessageDigest.isEqual(fingerprint, message.fingerprint())) {
            return settleFailed(BindingFailure.WRONG_FINGERPRINT);
        }
        if (!MessageDigest.isEqual(routing, message.routing())) {
            return settleFailed(BindingFailure.WRONG_ROUTING);
        }
        // Length is already exact; all 32 MAC bytes are compared in constant time.
        if (!MessageDigest.isEqual(tags[kind.code()], message.tag())) {
            return settleFailed(BindingFailure.BAD_TAG);
        }

        lastReceived = kind;
        if (role == Role.SERVER) {
            if (kind == BindingKind.CLIENT_PROOF) {
                expected = BindingKind.CLIENT_SWITCH;
                return BindingResult.send(sendable(BindingKind.SERVER_PROOF));
            }
            // CLIENT_SWITCH verified: emit the final SERVER_SWITCH and activate.
            BindingMessage finalMessage = sendable(BindingKind.SERVER_SWITCH);
            settleActivated();
            return BindingResult.active(finalMessage);
        }
        if (kind == BindingKind.OFFER) {
            expected = BindingKind.SERVER_PROOF;
            return BindingResult.send(sendable(BindingKind.CLIENT_PROOF));
        }
        if (kind == BindingKind.SERVER_PROOF) {
            expected = BindingKind.SERVER_SWITCH;
            return BindingResult.send(sendable(BindingKind.CLIENT_SWITCH));
        }
        // SERVER_SWITCH verified: the client has nothing left to send.
        settleActivated();
        return BindingResult.active(null);
    }

    /**
     * Aborts the exchange locally (docs/protocol.md §12.14: timeout, premature close or queue
     * overflow) and wipes every precomputed tag, so nothing can be built from them afterwards. The
     * machine becomes settled and failed: {@link #isActivated()} is false, {@link #expectedKind()}
     * is null, {@link #start()} throws and every later {@link #receive(byte[])} reports
     * {@link BindingFailure#ALREADY_SETTLED}. Idempotent, and a no-op once the machine has settled
     * for any other reason.
     */
    public void abort() {
        if (settled) {
            return;
        }
        settled = true;
        wipeTags();
    }

    private BindingMessage offer() {
        return BindingMessage.of(BindingKind.OFFER, mode, protocolNumber, shardCount, fingerprint, routing,
                tags[BindingKind.OFFER.code()]);
    }

    /** Builds a message carrying this endpoint's precomputed tag for {@code kind}. */
    private BindingMessage sendable(BindingKind kind) {
        return BindingMessage.of(kind, mode, protocolNumber, shardCount, fingerprint, routing, tags[kind.code()]);
    }

    private BindingResult settleFailed(BindingFailure reason) {
        failure = reason;
        settled = true;
        activated = false;
        wipeTags();
        return BindingResult.failed(reason);
    }

    private void settleActivated() {
        settled = true;
        activated = true;
        wipeTags();
    }

    private void wipeTags() {
        for (int i = 0; i < tags.length; i++) {
            HkdfSha256.wipe(tags[i]);
            tags[i] = null;
        }
    }

    private static void checkDigest(byte[] value, String name) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(name + " must be 32 raw bytes");
        }
    }

    @Override
    public String toString() {
        return "BindingStateMachine{role=" + role + ", mode=" + mode
                + ", expected=" + expected + ", settled=" + settled
                + (failure != null ? ", failure=" + failure : "") + '}';
    }
}
