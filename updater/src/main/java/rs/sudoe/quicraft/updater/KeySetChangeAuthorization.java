// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Decides whether a staged key-set change is authorized by a verified release manifest.
 *
 * <p>When the staged key-set fingerprint equals the persisted one, release authorization is enough.
 * Otherwise the manifest must also be signed by the persisted, currently usable revocation (backup)
 * key, which must hold the {@code revocation} role. A key holding both roles satisfies both checks.
 * Co-signing never waives the prohibition on updater-driven replacement of the revocation key.
 * No manifest flag can waive this rule (docs/updater.md 394-402, key-set change rule).
 *
 * <p>Fails closed: any null argument, or a manifest verified under a different trust snapshot,
 * yields {@code false}.
 */
public final class KeySetChangeAuthorization {

    private KeySetChangeAuthorization() {
    }

    /**
     * @param manifest the manifest that was authenticated under {@code current}
     * @param stagedKeySetFingerprint the key-set fingerprint computed from the staged jar's trust file
     * @param persisted the trust anchors persisted by the updater
     * @param current the trust snapshot used now, including current revocations
     * @return true if the staged key-set change (or the lack of one) is authorized
     */
    public static boolean check(
            VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest,
            String stagedKeySetFingerprint,
            StateRecords.PersistedTrust persisted,
            TrustSnapshot current) {
        if (manifest == null || stagedKeySetFingerprint == null || persisted == null
                || current == null) {
            return false;
        }
        if (!current.equals(manifest.snapshot())) {
            return false;
        }
        if (stagedKeySetFingerprint.equals(persisted.keySetFingerprint())) {
            return true;
        }
        String backupPub = persisted.revocationPub();
        if (!current.usable(backupPub)) {
            return false;
        }
        for (VerifiedSigner signer : manifest.signers().signers()) {
            if (signer.pub().equals(backupPub)
                    && signer.roles().contains(KeyRole.REVOCATION)
                    && current.usable(signer.pub())) {
                return true;
            }
        }
        return false;
    }
}
