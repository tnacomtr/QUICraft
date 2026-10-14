// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Arrays;
import java.util.Objects;

/**
 * A parsed document together with the exact bytes it was verified over, every verified signer and
 * the trust snapshot used for the decision.
 *
 * <p>The exact bytes are copied on the way in and on the way out, so callers cannot change what was
 * verified. Only AuthenticatedDocuments and SignatureVerifier create instances.
 */
public final class VerifiedDocument<T> {

    private final T document;
    private final byte[] exactBytes;
    private final VerifiedSigners signers;
    private final TrustSnapshot snapshot;

    VerifiedDocument(T document, byte[] exactBytes, VerifiedSigners signers, TrustSnapshot snapshot) {
        this.document = Objects.requireNonNull(document, "document");
        Objects.requireNonNull(exactBytes, "exactBytes");
        this.signers = Objects.requireNonNull(signers, "signers");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.exactBytes = exactBytes.clone();
    }

    /** The parsed document. */
    public T document() {
        return document;
    }

    /** Returns a copy of the exact verified bytes. */
    public byte[] exactBytes() {
        return exactBytes.clone();
    }

    /** Every signer that verified over the exact bytes. */
    public VerifiedSigners signers() {
        return signers;
    }

    /** The trust snapshot used for the decision. */
    public TrustSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VerifiedDocument<?> other)) {
            return false;
        }
        return Objects.equals(document, other.document)
                && Arrays.equals(exactBytes, other.exactBytes)
                && Objects.equals(signers, other.signers)
                && Objects.equals(snapshot, other.snapshot);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(document, signers, snapshot);
        return 31 * h + Arrays.hashCode(exactBytes);
    }

    /** Does not print the document bytes; prints their length only. */
    @Override
    public String toString() {
        return "VerifiedDocument[document=" + document
                + ", exactBytes=<" + exactBytes.length + " bytes>"
                + ", signers=" + signers
                + ", snapshot=" + snapshot + "]";
    }
}
