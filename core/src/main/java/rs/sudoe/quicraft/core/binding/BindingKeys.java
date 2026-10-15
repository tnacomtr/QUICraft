// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.nio.charset.StandardCharsets;

/**
 * Binding key derivation, docs/protocol.md §12.6:
 *
 * <pre>
 * PRK = HKDF-Extract(salt = SHA256("quicraft binding v2 salt"), IKM = S)
 * K   = HKDF-Expand(PRK, info = "quicraft binding v2 key", L = 32)
 * </pre>
 *
 * {@code S} is Minecraft's 16-byte shared secret. The UUID, Mojang hash and any TLS secret are
 * explicitly not acceptable substitutes. The derived key and all intermediate values are wiped by
 * this class where Java allows it; the caller owns the erasure of its own {@code S}.
 */
public final class BindingKeys {
    private static final byte[] SALT_LABEL = "quicraft binding v2 salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_INFO = "quicraft binding v2 key".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_LENGTH = 32;

    private BindingKeys() {
    }

    /** Derives the 32-byte binding key {@code K} from Minecraft's shared secret {@code S}. */
    public static byte[] derive(byte[] minecraftSecret) {
        if (minecraftSecret == null) {
            throw new IllegalArgumentException("minecraft secret is required in online mode");
        }
        byte[] salt = HkdfSha256.sha256(SALT_LABEL);
        try {
            return HkdfSha256.derive(salt, minecraftSecret, KEY_INFO, KEY_LENGTH);
        } finally {
            HkdfSha256.wipe(salt);
        }
    }
}