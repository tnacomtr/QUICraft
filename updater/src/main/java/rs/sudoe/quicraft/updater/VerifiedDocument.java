// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * A parsed document together with the exact bytes it was verified over, every verified signer and
 * the trust snapshot used for the decision.
 *
 * <p>The exact bytes are copied on the way in and on the way out, so callers cannot change what was
 * verified.
 */
public record VerifiedDocument<T>(
        T document, byte[] exactBytes, VerifiedSigners signers, TrustSnapshot snapshot) {

    public VerifiedDocument {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(exactBytes, "exactBytes");
        Objects.requireNonNull(signers, "signers");
        Objects.requireNonNull(snapshot, "snapshot");
        exactBytes = exactBytes.clone();
    }

    /** Returns a copy of the exact verified bytes. */
    @Override
    public byte[] exactBytes() {
        return exactBytes.clone();
    }
}
