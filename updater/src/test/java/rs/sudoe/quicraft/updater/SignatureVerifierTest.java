// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final byte[] DOC = SignedFixtures.bytes("{\"doc\":true}");

    private static UpdateDocuments.SignatureEnvelope parse(byte[] envelope) throws Exception {
        return DocumentParser.parseSignatures(envelope);
    }

    private static List<String> kids(EnvelopeVerification verification) {
        return verification.signers().signers().stream().map(VerifiedSigner::kid).toList();
    }

    @Test
    void mismatchedPayloadHashIsTornBeforeAnySignatureIsTried() throws Exception {
        byte[] other = SignedFixtures.bytes("{\"doc\":false}");
        byte[] envelope = SignedFixtures.envelopeFor(other,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, DOC));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.TORN, result.status());
        assertTrue(result.signers().signers().isEmpty());
    }

    @Test
    void invalidSignatureAloneIsNoValidSigner() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.entry(TestKeys.KID_1, SignedFixtures.BAD_SIG));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.NO_VALID_SIGNER, result.status());
        assertTrue(result.signers().signers().isEmpty());
    }

    @Test
    void unknownKidAloneIsNoValidSigner() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.signedEntry(SignedFixtures.KID_UNKNOWN, TestKeys.SEED_1, DOC));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.NO_VALID_SIGNER, result.status());
    }

    @Test
    void unknownKidBesideValidEntryKeepsOnlyTheValidOne() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.signedEntry(SignedFixtures.KID_UNKNOWN, TestKeys.SEED_1, DOC),
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, DOC));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.VERIFIED, result.status());
        assertEquals(List.of(TestKeys.KID_2), kids(result));
    }

    @Test
    void invalidSignatureBesideValidEntryKeepsOnlyTheValidOne() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.entry(TestKeys.KID_1, SignedFixtures.BAD_SIG),
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, DOC));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.VERIFIED, result.status());
        assertEquals(List.of(TestKeys.KID_2), kids(result));
    }

    @Test
    void bothValidSignaturesAreBothRetainedWithTheirRoles() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, DOC),
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, DOC));
        EnvelopeVerification result = SignatureVerifier.check(
                DOC, parse(envelope), SignedFixtures.standardSnapshot(Set.of()));
        assertEquals(EnvelopeStatus.VERIFIED, result.status());
        assertEquals(List.of(TestKeys.KID_1, TestKeys.KID_2), kids(result));
        VerifiedSigner backup = result.signers().signers().get(1);
        assertEquals(TestKeys.PUB_2, backup.pub());
        assertEquals(Set.of(KeyRole.RELEASE, KeyRole.REVOCATION), backup.roles());
    }

    @Test
    void revokedKeySignatureIsIgnoredAndValidOtherKeyIsKept() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, DOC),
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, DOC));
        EnvelopeVerification result = SignatureVerifier.check(DOC, parse(envelope),
                SignedFixtures.standardSnapshot(Set.of(TestKeys.PUB_1)));
        assertEquals(EnvelopeStatus.VERIFIED, result.status());
        assertEquals(List.of(TestKeys.KID_2), kids(result));
    }

    @Test
    void onlyRevokedSignatureIsNoValidSigner() throws Exception {
        byte[] envelope = SignedFixtures.envelopeFor(DOC,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, DOC));
        EnvelopeVerification result = SignatureVerifier.check(DOC, parse(envelope),
                SignedFixtures.standardSnapshot(Set.of(TestKeys.PUB_1)));
        assertEquals(EnvelopeStatus.NO_VALID_SIGNER, result.status());
    }
}
