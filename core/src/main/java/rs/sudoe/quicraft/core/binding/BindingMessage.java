// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.util.Arrays;

/**
 * The exact 103-byte binding payload of docs/protocol.md §12.8, on the {@code quicraft:binding}
 * configuration custom payload:
 *
 * <pre>
 * u8(version = 2) || u8(kind) || u8(mode) || u32(protocol_number) || F[32] || R[32] || tag[32]
 * </pre>
 *
 * There are no optional fields and no trailing bytes: anything other than exactly 103 bytes is
 * rejected. {@code u32} is unsigned big-endian; hashes, MACs and tags are raw bytes. Nothing here
 * logs or formats secret material.
 */
public final class BindingMessage {
    /** The only accepted version byte. */
    public static final int VERSION = 2;
    /** The exact payload length. */
    public static final int ENCODED_LENGTH = 103;

    private static final int OFFSET_KIND = 1;
    private static final int OFFSET_MODE = 2;
    private static final int OFFSET_PROTOCOL = 3;
    private static final int OFFSET_F = 7;
    private static final int OFFSET_R = 39;
    private static final int OFFSET_TAG = 71;

    private final BindingKind kind;
    private final int mode;
    private final int protocolNumber;
    private final byte[] fingerprint;
    private final byte[] routing;
    private final byte[] tag;

    private BindingMessage(BindingKind kind, int mode, int protocolNumber,
                           byte[] fingerprint, byte[] routing, byte[] tag) {
        this.kind = kind;
        this.mode = mode;
        this.protocolNumber = protocolNumber;
        this.fingerprint = fingerprint;
        this.routing = routing;
        this.tag = tag;
    }

    /** Builds a message from its fields, validating their shape. */
    public static BindingMessage of(BindingKind kind, int mode, int protocolNumber,
                                    byte[] fingerprint, byte[] routing, byte[] tag) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        checkMode(mode);
        checkProtocolNumber(protocolNumber);
        checkDigest(fingerprint, "fingerprint");
        checkDigest(routing, "routing");
        checkDigest(tag, "tag");
        return new BindingMessage(kind, mode, protocolNumber,
                fingerprint.clone(), routing.clone(), tag.clone());
    }

    /** Builds an OFFER, whose tag is always 32 zero bytes. */
    public static BindingMessage offer(int mode, int protocolNumber, byte[] fingerprint, byte[] routing) {
        return of(BindingKind.OFFER, mode, protocolNumber, fingerprint, routing, BindingTags.zeros());
    }

    /**
     * Decodes and structurally validates a payload: exact length, version 2, a known kind, a mode of
     * 0 or 1. Metadata and MAC semantics belong to {@link BindingStateMachine}.
     */
    public static BindingMessage decode(byte[] payload) throws BindingFormatException {
        if (payload == null || payload.length != ENCODED_LENGTH) {
            throw new BindingFormatException(BindingFailure.BAD_LENGTH);
        }
        if ((payload[0] & 0xFF) != VERSION) {
            throw new BindingFormatException(BindingFailure.BAD_VERSION);
        }
        BindingKind kind = BindingKind.fromCode(payload[OFFSET_KIND] & 0xFF);
        if (kind == null) {
            throw new BindingFormatException(BindingFailure.UNKNOWN_KIND);
        }
        int mode = payload[OFFSET_MODE] & 0xFF;
        if (mode > 1) {
            throw new BindingFormatException(BindingFailure.WRONG_MODE);
        }
        int protocolNumber = ((payload[OFFSET_PROTOCOL] & 0xFF) << 24)
                | ((payload[OFFSET_PROTOCOL + 1] & 0xFF) << 16)
                | ((payload[OFFSET_PROTOCOL + 2] & 0xFF) << 8)
                | (payload[OFFSET_PROTOCOL + 3] & 0xFF);
        return new BindingMessage(
                kind,
                mode,
                protocolNumber,
                Arrays.copyOfRange(payload, OFFSET_F, OFFSET_F + 32),
                Arrays.copyOfRange(payload, OFFSET_R, OFFSET_R + 32),
                Arrays.copyOfRange(payload, OFFSET_TAG, OFFSET_TAG + 32));
    }

    /** Encodes to the exact 103-byte payload. */
    public byte[] encode() {
        byte[] payload = new byte[ENCODED_LENGTH];
        payload[0] = (byte) VERSION;
        payload[OFFSET_KIND] = (byte) kind.code();
        payload[OFFSET_MODE] = (byte) mode;
        payload[OFFSET_PROTOCOL] = (byte) (protocolNumber >>> 24);
        payload[OFFSET_PROTOCOL + 1] = (byte) (protocolNumber >>> 16);
        payload[OFFSET_PROTOCOL + 2] = (byte) (protocolNumber >>> 8);
        payload[OFFSET_PROTOCOL + 3] = (byte) protocolNumber;
        System.arraycopy(fingerprint, 0, payload, OFFSET_F, 32);
        System.arraycopy(routing, 0, payload, OFFSET_R, 32);
        System.arraycopy(tag, 0, payload, OFFSET_TAG, 32);
        return payload;
    }

    public BindingKind kind() {
        return kind;
    }

    public int mode() {
        return mode;
    }

    public int protocolNumber() {
        return protocolNumber;
    }

    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    public byte[] routing() {
        return routing.clone();
    }

    public byte[] tag() {
        return tag.clone();
    }

    @Override
    public String toString() {
        return "BindingMessage{version=" + VERSION + ", kind=" + kind + ", mode=" + mode
                + ", protocolNumber=" + protocolNumber + '}';
    }

    private static void checkMode(int mode) {
        if (mode != 0 && mode != 1) {
            throw new IllegalArgumentException("mode must be 0 (offline) or 1 (online)");
        }
    }

    /**
     * §12.8: {@code protocol_number} is an unsigned u32 and MUST be rejected outside
     * {@code 0..4294967295} before encoding, never truncated or wrapped. A Java {@code int} cannot
     * hold a value above {@link Integer#MAX_VALUE}, so the only out-of-range value a caller can
     * pass is negative: reject it rather than writing four bytes that mean something else.
     */
    private static void checkProtocolNumber(int protocolNumber) {
        if (protocolNumber < 0) {
            throw new IllegalArgumentException("protocol number must be in unsigned 32-bit range, got "
                    + protocolNumber);
        }
    }

    private static void checkDigest(byte[] value, String name) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(name + " must be 32 raw bytes");
        }
    }
}