// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The key-set fingerprint persisted as {@code keySetFingerprint} (docs/updater.md 12.5 and 12.8).
 *
 * <p>Keys are sorted by lowercase hex public key. For each key the 32 raw public-key bytes are
 * appended, followed by one role byte (bit 0 {@code release}, bit 1 {@code revocation}). The
 * fingerprint is the lowercase hex SHA-256 of that byte string.
 */
public final class KeySetFingerprint {

    private KeySetFingerprint() {
    }

    /**
     * Computes the fingerprint of {@code keys}.
     *
     * @throws UpdateRejectedException if a public key is not strict 32-byte lowercase hex, or
     *     two keys share the same public key
     */
    public static String of(List<UpdateDocuments.TrustedKey> keys) throws UpdateRejectedException {
        List<UpdateDocuments.TrustedKey> sorted = new ArrayList<>(keys);
        Set<String> seen = new HashSet<>();
        for (UpdateDocuments.TrustedKey key : sorted) {
            // Decoding validates the hex before the key contributes any bytes.
            Hex.decode(key.pub(), Hex.PUBLIC_KEY_BYTES);
            if (!seen.add(key.pub())) {
                throw new UpdateRejectedException("duplicate key");
            }
        }
        sorted.sort(Comparator.comparing(UpdateDocuments.TrustedKey::pub));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (UpdateDocuments.TrustedKey key : sorted) {
            byte[] raw = Hex.decode(key.pub(), Hex.PUBLIC_KEY_BYTES);
            bytes.write(raw, 0, raw.length);
            bytes.write(roleByte(key));
        }
        return Hex.sha256(bytes.toByteArray());
    }

    private static int roleByte(UpdateDocuments.TrustedKey key) {
        int mask = 0;
        for (KeyRole role : TrustedKeyRoles.of(key)) {
            mask |= role.bit();
        }
        return mask;
    }
}
