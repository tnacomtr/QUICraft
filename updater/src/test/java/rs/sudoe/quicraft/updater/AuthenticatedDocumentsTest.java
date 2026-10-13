// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthenticatedDocumentsTest {

    private static final byte[] RELEASE = SignedFixtures.bytes(SignedFixtures.releaseJson());
    private static final byte[] REVOCATIONS =
            SignedFixtures.bytes(SignedFixtures.revocationsJson());

    private static TrustSnapshot snapshot() throws Exception {
        return SignedFixtures.standardSnapshot(Set.of());
    }

    private static byte[] releaseSignedBy(String kid, String seed) throws Exception {
        return SignedFixtures.envelopeFor(RELEASE,
                SignedFixtures.signedEntry(kid, seed, RELEASE));
    }

    @Test
    void validReleaseIsVerifiedWithSignerEvidence() throws Exception {
        byte[] sig = SignedFixtures.envelopeFor(RELEASE,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, RELEASE),
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, RELEASE));
        DocumentVerification<UpdateDocuments.ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(RELEASE, sig, snapshot());
        assertEquals(VerificationOutcome.VERIFIED, result.outcome());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> verified = result.verified();
        assertEquals(42L, verified.document().seq());
        assertArrayEquals(RELEASE, verified.exactBytes());
        assertEquals(2, verified.signers().signers().size());
        assertTrue(verified.signers().containsPub(TestKeys.PUB_2));
    }

    @Test
    void tornPairIsTorn() throws Exception {
        byte[] sig = SignedFixtures.envelope(Hex.sha256(SignedFixtures.bytes("{}")),
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, RELEASE));
        DocumentVerification<UpdateDocuments.ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(RELEASE, sig, snapshot());
        assertEquals(VerificationOutcome.TORN, result.outcome());
        assertNull(result.verified());
    }

    @Test
    void wrongKeyMaterialIsBadSignature() throws Exception {
        // Signed by TEST 2's seed but labelled with TEST 1's kid: the signature fails.
        DocumentVerification<UpdateDocuments.ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(RELEASE,
                        releaseSignedBy(TestKeys.KID_1, TestKeys.SEED_2), snapshot());
        assertEquals(VerificationOutcome.BAD_SIGNATURE, result.outcome());
        assertNull(result.verified());
    }

    @Test
    void unknownSignerOnlyIsBadSignature() throws Exception {
        DocumentVerification<UpdateDocuments.ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(RELEASE,
                        releaseSignedBy(SignedFixtures.KID_UNKNOWN, TestKeys.SEED_1), snapshot());
        assertEquals(VerificationOutcome.BAD_SIGNATURE, result.outcome());
    }

    @Test
    void malformedEnvelopeIsMalformed() throws Exception {
        byte[] notJson = SignedFixtures.bytes("not json");
        byte[] missingField = SignedFixtures.bytes(
                "{\"type\":\"quicraft.sig\",\"v\":1,\"signatures\":[]}");
        assertEquals(VerificationOutcome.MALFORMED,
                AuthenticatedDocuments.verifyRelease(RELEASE, notJson, snapshot()).outcome());
        assertEquals(VerificationOutcome.MALFORMED,
                AuthenticatedDocuments.verifyRelease(RELEASE, missingField, snapshot()).outcome());
    }

    @Test
    void malformedDocumentWithValidSignatureIsMalformed() throws Exception {
        byte[] bad = SignedFixtures.bytes("{\"type\":\"quicraft.release\"}");
        byte[] sig = SignedFixtures.envelopeFor(bad,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, bad));
        DocumentVerification<UpdateDocuments.ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(bad, sig, snapshot());
        assertEquals(VerificationOutcome.MALFORMED, result.outcome());
        assertNull(result.verified());
    }

    @Test
    void nullInputsAreMalformedAndDoNotThrow() throws Exception {
        TrustSnapshot snapshot = snapshot();
        assertEquals(VerificationOutcome.MALFORMED,
                AuthenticatedDocuments.verifyRelease(null, null, snapshot).outcome());
        assertEquals(VerificationOutcome.MALFORMED,
                AuthenticatedDocuments.verifyRevocations(REVOCATIONS, null, snapshot).outcome());
        assertEquals(VerificationOutcome.MALFORMED,
                AuthenticatedDocuments.verifyRelease(RELEASE, releaseSignedBy(
                        TestKeys.KID_1, TestKeys.SEED_1), null).outcome());
    }

    @Test
    void randomBytesNeverVerifyAndNeverThrow() throws Exception {
        Random random = new Random(1);
        TrustSnapshot snapshot = snapshot();
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[random.nextInt(300)];
            random.nextBytes(junk);
            assertNotNull(AuthenticatedDocuments.verifyRelease(junk, junk, snapshot));
            assertEquals(VerificationOutcome.MALFORMED,
                    AuthenticatedDocuments.verifyRevocations(junk, junk, snapshot).outcome());
        }
    }

    @Test
    void revocationsNeedRevocationRoleSigner() throws Exception {
        byte[] releaseOnly = SignedFixtures.envelopeFor(REVOCATIONS,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, REVOCATIONS));
        assertEquals(VerificationOutcome.BAD_SIGNATURE,
                AuthenticatedDocuments.verifyRevocations(REVOCATIONS, releaseOnly, snapshot())
                        .outcome());

        byte[] backup = SignedFixtures.envelopeFor(REVOCATIONS,
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, REVOCATIONS));
        DocumentVerification<UpdateDocuments.Revocations> result =
                AuthenticatedDocuments.verifyRevocations(REVOCATIONS, backup, snapshot());
        assertEquals(VerificationOutcome.VERIFIED, result.outcome());
        assertEquals(3L, result.verified().document().seq());
    }

    @Test
    void releaseNeedsReleaseRoleSigner() throws Exception {
        TrustSnapshot revocationOnly = SignedFixtures.revocationOnlySnapshot();
        byte[] sig = SignedFixtures.envelopeFor(RELEASE,
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, RELEASE));
        assertEquals(VerificationOutcome.BAD_SIGNATURE,
                AuthenticatedDocuments.verifyRelease(RELEASE, sig, revocationOnly).outcome());
    }

    @Test
    void revokedSignerDoesNotVerifyEvenWithTheRightSeed() throws Exception {
        TrustSnapshot revoked = SignedFixtures.standardSnapshot(Set.of(TestKeys.PUB_1));
        assertEquals(VerificationOutcome.BAD_SIGNATURE, AuthenticatedDocuments.verifyRelease(
                RELEASE, releaseSignedBy(TestKeys.KID_1, TestKeys.SEED_1), revoked).outcome());
    }

    @Test
    void returnedBytesAreACopyInBothDirections() throws Exception {
        byte[] input = RELEASE.clone();
        byte[] sig = SignedFixtures.envelopeFor(input,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, input));
        VerifiedDocument<UpdateDocuments.ReleaseManifest> verified =
                AuthenticatedDocuments.verifyRelease(input, sig, snapshot()).verified();
        input[0] = 0;
        byte[] first = verified.exactBytes();
        assertArrayEquals(RELEASE, first);
        first[0] = 0;
        assertArrayEquals(RELEASE, verified.exactBytes());
    }

    @Test
    void verifiedRecordRejectsOutcomeMismatch() throws Exception {
        VerifiedDocument<UpdateDocuments.ReleaseManifest> verified =
                AuthenticatedDocuments.verifyRelease(RELEASE, releaseSignedBy(
                        TestKeys.KID_1, TestKeys.SEED_1), snapshot()).verified();
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerification<>(VerificationOutcome.VERIFIED, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerification<>(VerificationOutcome.BAD_SIGNATURE, verified));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentVerification<>(VerificationOutcome.TORN, verified));
    }
}
