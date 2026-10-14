// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Arrays;
import java.util.Objects;

/** One document and its detached signature, as fetched from one host. Both arrays are copied. */
public final class DocumentPair {
    private final byte[] documentBytes;
    private final byte[] signatureBytes;

    /**
     * @param documentBytes the document bytes; copied
     * @param signatureBytes the signature bytes; copied
     */
    public DocumentPair(byte[] documentBytes, byte[] signatureBytes) {
        this.documentBytes = Objects.requireNonNull(documentBytes, "documentBytes").clone();
        this.signatureBytes = Objects.requireNonNull(signatureBytes, "signatureBytes").clone();
    }

    /** @return a copy of the document bytes */
    public byte[] documentBytes() {
        return documentBytes.clone();
    }

    /** @return a copy of the signature bytes */
    public byte[] signatureBytes() {
        return signatureBytes.clone();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DocumentPair other)) {
            return false;
        }
        return Arrays.equals(documentBytes, other.documentBytes)
                && Arrays.equals(signatureBytes, other.signatureBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(documentBytes) + Arrays.hashCode(signatureBytes);
    }

    @Override
    public String toString() {
        return "DocumentPair[documentBytes=" + documentBytes.length
                + ", signatureBytes=" + signatureBytes.length + "]";
    }
}
