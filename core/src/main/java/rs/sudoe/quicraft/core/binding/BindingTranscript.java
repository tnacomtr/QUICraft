// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The binding transcript, docs/protocol.md §12.7:
 *
 * <pre>
 * T = SHA256("quicraft/3 binding v2" || u32(protocol_number) || u8(mode) || F || R)
 * </pre>
 *
 * {@code mode} is 1 for online and 0 for offline; {@code F} is the SHA-256 of the leaf certificate
 * DER (or the ticket-associated certificate on resumption) and {@code R} is the routing digest of
 * §13.7. Both are 32 raw bytes, never hex.
 */
public final class BindingTranscript {
    private static final byte[] LABEL = "quicraft/3 binding v2".getBytes(StandardCharsets.US_ASCII);

    private BindingTranscript() {
    }

    /** Computes {@code T} for the given inputs. {@code mode} is 1 for online and 0 for offline. */
    public static byte[] compute(int protocolNumber, int mode, byte[] fingerprint, byte[] routing) {
        checkMode(mode);
        checkDigest(fingerprint, "fingerprint");
        checkDigest(routing, "routing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LABEL);
            digest.update((byte) (protocolNumber >>> 24));
            digest.update((byte) (protocolNumber >>> 16));
            digest.update((byte) (protocolNumber >>> 8));
            digest.update((byte) protocolNumber);
            digest.update((byte) mode);
            digest.update(fingerprint);
            digest.update(routing);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static void checkDigest(byte[] value, String name) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(name + " must be 32 raw bytes");
        }
    }

    private static void checkMode(int mode) {
        if (mode != 0 && mode != 1) {
            throw new IllegalArgumentException("mode must be 0 (offline) or 1 (online)");
        }
    }
}