// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Arrays;
import java.util.Objects;

/**
 * The exact manifest and signature bytes an acceptance decision was based on. Retained so the
 * transaction can re-authenticate them locally under a fresh snapshot. Both arrays are copied.
 */
public final class ManifestEvidence {
    private final byte[] exactManifestBytes;
    private final byte[] exactSignatureBytes;

    /**
     * @param exactManifestBytes the manifest bytes; copied
     * @param exactSignatureBytes the signature bytes; copied
     */
    public ManifestEvidence(byte[] exactManifestBytes, byte[] exactSignatureBytes) {
        this.exactManifestBytes = Objects.requireNonNull(exactManifestBytes, "manifest").clone();
        this.exactSignatureBytes = Objects.requireNonNull(exactSignatureBytes, "signature").clone();
    }

    /** @return a copy of the manifest bytes */
    public byte[] exactManifestBytes() {
        return exactManifestBytes.clone();
    }

    /** @return a copy of the signature bytes */
    public byte[] exactSignatureBytes() {
        return exactSignatureBytes.clone();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ManifestEvidence other)) {
            return false;
        }
        return Arrays.equals(exactManifestBytes, other.exactManifestBytes)
                && Arrays.equals(exactSignatureBytes, other.exactSignatureBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(exactManifestBytes) + Arrays.hashCode(exactSignatureBytes);
    }

    @Override
    public String toString() {
        return "ManifestEvidence[manifestBytes=" + exactManifestBytes.length
                + ", signatureBytes=" + exactSignatureBytes.length + "]";
    }
}
