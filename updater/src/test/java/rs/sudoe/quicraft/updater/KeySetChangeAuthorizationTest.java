// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeySetChangeAuthorizationTest {

    private static final byte[] RELEASE = SignedFixtures.bytes(SignedFixtures.releaseJson());
    private static final String CHANGED_FINGERPRINT = "cd".repeat(32);

    private static VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest(
            TrustSnapshot snapshot, String... entries) throws Exception {
        byte[] sig = SignedFixtures.envelopeFor(RELEASE, entries);
        return AuthenticatedDocuments.verifyRelease(RELEASE, sig, snapshot).verified();
    }

    private static String releaseEntry() throws Exception {
        return SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, RELEASE);
    }

    private static String backupEntry() throws Exception {
        return SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, RELEASE);
    }

    /** Persisted anchors whose backup key is TEST 2. */
    private static StateRecords.PersistedTrust persisted(String fingerprint) {
        return new StateRecords.PersistedTrust(1, fingerprint, TestKeys.PUB_2);
    }

    @Test
    void unchangedFingerprintNeedsOnlyReleaseAuthorization() throws Exception {
        TrustSnapshot current = SignedFixtures.standardSnapshot(Set.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(current, releaseEntry());
        assertTrue(KeySetChangeAuthorization.check(manifest, current.keySetFingerprint(),
                persisted(current.keySetFingerprint()), current));
    }

    @Test
    void changedFingerprintWithReleaseOnlySignerIsRejected() throws Exception {
        TrustSnapshot current = SignedFixtures.standardSnapshot(Set.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(current, releaseEntry());
        assertFalse(KeySetChangeAuthorization.check(manifest, CHANGED_FINGERPRINT,
                persisted(current.keySetFingerprint()), current));
    }

    @Test
    void changedFingerprintWithReleaseAndCurrentBackupSignersIsAccepted() throws Exception {
        TrustSnapshot current = SignedFixtures.standardSnapshot(Set.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(current, releaseEntry(), backupEntry());
        assertTrue(KeySetChangeAuthorization.check(manifest, CHANGED_FINGERPRINT,
                persisted(current.keySetFingerprint()), current));
    }

    @Test
    void oneBackupKeyHoldingBothRolesSatisfiesBothChecks() throws Exception {
        TrustSnapshot current = SignedFixtures.standardSnapshot(Set.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(current, backupEntry());
        assertTrue(manifest.signers().hasRelease());
        assertTrue(manifest.signers().hasRevocation());
        assertTrue(KeySetChangeAuthorization.check(manifest, CHANGED_FINGERPRINT,
                persisted(current.keySetFingerprint()), current));
    }

    @Test
    void backupKeyRevokedInCurrentSnapshotIsRejected() throws Exception {
        TrustSnapshot before = SignedFixtures.standardSnapshot(Set.of());
        TrustSnapshot revoked = SignedFixtures.standardSnapshot(Set.of(TestKeys.PUB_2));
        // Backup signature fails under the revoked snapshot; only the release key remains.
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(revoked, releaseEntry(), backupEntry());
        assertFalse(manifest.signers().containsPub(TestKeys.PUB_2));
        assertFalse(KeySetChangeAuthorization.check(manifest, CHANGED_FINGERPRINT,
                persisted(before.keySetFingerprint()), revoked));

        // Even if a backup signer were listed, a revoked backup pub must not authorize.
        VerifiedDocument<UpdateDocuments.ReleaseManifest> forged =
                new VerifiedDocument<>(manifest.document(), RELEASE,
                        new VerifiedSigners(List.of(new VerifiedSigner(TestKeys.KID_2,
                                TestKeys.PUB_2, Set.of(KeyRole.RELEASE, KeyRole.REVOCATION)))),
                        revoked);
        assertFalse(KeySetChangeAuthorization.check(forged, CHANGED_FINGERPRINT,
                persisted(before.keySetFingerprint()), revoked));
    }

    @Test
    void manifestVerifiedUnderDifferentSnapshotIsRejected() throws Exception {
        TrustSnapshot verifiedUnder = SignedFixtures.standardSnapshot(Set.of());
        TrustSnapshot later = SignedFixtures.standardSnapshot(Set.of(TestKeys.PUB_1));
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(verifiedUnder, releaseEntry(), backupEntry());
        assertFalse(KeySetChangeAuthorization.check(manifest, CHANGED_FINGERPRINT,
                persisted(verifiedUnder.keySetFingerprint()), later));
        assertFalse(KeySetChangeAuthorization.check(manifest, verifiedUnder.keySetFingerprint(),
                persisted(verifiedUnder.keySetFingerprint()), later));
    }

    @Test
    void nullArgumentsFailClosed() throws Exception {
        TrustSnapshot current = SignedFixtures.standardSnapshot(Set.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest =
                manifest(current, releaseEntry());
        assertFalse(KeySetChangeAuthorization.check(null, current.keySetFingerprint(),
                persisted(current.keySetFingerprint()), current));
        assertFalse(KeySetChangeAuthorization.check(manifest, null,
                persisted(current.keySetFingerprint()), current));
        assertFalse(KeySetChangeAuthorization.check(manifest, current.keySetFingerprint(),
                null, current));
        assertFalse(KeySetChangeAuthorization.check(manifest, current.keySetFingerprint(),
                persisted(current.keySetFingerprint()), null));
    }
}
