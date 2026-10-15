// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Binding message authentication tag, docs/protocol.md §12.8:
 *
 * <pre>
 * tag(kind) = HMAC-SHA256(K, "quicraft binding v2 message" || u8(kind) || T || E)
 * </pre>
 *
 * Distinct kinds give direction and phase separation: a tag computed for one kind never validates
 * for another, so reflecting a peer's own tag fails. OFFER carries 32 zero bytes instead and every
 * offline-mode tag is all zeros, which {@link #zeros()} expresses.
 */
public final class BindingTags {
    private static final byte[] LABEL = "quicraft binding v2 message".getBytes(StandardCharsets.US_ASCII);
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private BindingTags() {
    }

    /** Computes the 32-byte tag for {@code kind} under key {@code K}, transcript {@code T} and exporter {@code E}. */
    public static byte[] tag(byte[] key, BindingKind kind, byte[] transcript, byte[] exporter) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        checkLength(key, 32, "key");
        checkLength(transcript, 32, "transcript");
        checkLength(exporter, 32, "exporter");
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(LABEL);
            mac.update((byte) kind.code());
            mac.update(transcript);
            mac.update(exporter);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " is required by every Java platform", e);
        }
    }

    /** A fresh 32-byte all-zero tag (OFFER online, and every tag in offline mode). */
    public static byte[] zeros() {
        return new byte[32];
    }

    private static void checkLength(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " raw bytes");
        }
    }
}