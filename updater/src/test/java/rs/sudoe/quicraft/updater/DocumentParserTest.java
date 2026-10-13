// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Black-box tests for the strict, typed updater document parsers.
 *
 * <p>The parsers prove structure, types, encodings, bounds and uniqueness only. Ordering, lifetime,
 * freshness and authenticity are separate policy layers and are deliberately not asserted here.
 */
class DocumentParserTest {
    /** Marker that must never appear in a rejection message. */
    private static final String MARKER = "MARKER42SECRET";

    // RFC 8032 test-vector public keys and their independently computed kids. No private key is
    // used and no key is generated.
    private static final String PUB_A =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String KID_A = "21fe31dfa154a261";
    private static final String PUB_B =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String KID_B = "39f713d0a644253f";
    private static final String PUB_C =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String KID_C = "dac073e0123bdea5";

    /** Published SHA-256 vector for "abc". */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final String TS1 = "2026-11-01T12:00:00Z";
    private static final String TS2 = "2026-12-01T12:00:00Z";

    private static final String SHA512_A = "cd".repeat(64);
    private static final String SHA512_B = "ab".repeat(64);
    private static final String SHA512_C = "ef".repeat(64);
    private static final String SIG = "01".repeat(64);
    private static final String SIG_KID = "0123456789abcdef";
    private static final String H7 = hex128(7);
    private static final String H9 = hex128(9);

    private static final int TRUST_CAP = 8192;
    private static final int SIGNATURES_CAP = 4096;
    private static final int RELEASE_CAP = 65536;
    private static final int REVOCATIONS_CAP = 65536;

    // ---- fixtures ----------------------------------------------------------------------------

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Independent kid: first 8 bytes of SHA-256 over the 32 raw public-key bytes. */
    private static String independentKid(String pubHex) throws Exception {
        byte[] pub = new byte[32];
        for (int i = 0; i < 32; i++) {
            pub[i] = (byte) Integer.parseInt(pubHex.substring(2 * i, 2 * i + 2), 16);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pub);
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            sb.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(digest[i] & 0xf, 16));
        }
        return sb.toString();
    }

    private static String hex128(long n) {
        return String.format("%0128x", n);
    }

    private static String pubHex(long n) {
        return String.format("%064x", n);
    }

    /**
     * The n-th (1-based) canonical, prime-order Ed25519 point encoding, scanning y = 2, 3, ... with
     * the sign bit clear. Deterministic; the point's private key is never needed.
     */
    private static String validPointHex(long n) {
        long found = 0;
        for (long y = 2; ; y++) {
            byte[] enc = new byte[32];
            for (int i = 0; i < 8; i++) {
                enc[i] = (byte) (y >>> (8 * i));
            }
            if (Ed25519Points.isCanonicalPrimeOrderCandidate(enc) && ++found == n) {
                return Hex.encode(enc);
            }
        }
    }

    private static String generatedKeyJson(long n, String roles) throws Exception {
        String pub = validPointHex(n);
        return keyJson(independentKid(pub), pub, roles);
    }

    private static String trustJson(
            String epoch, String built, String revFloor, String manFloor, String keys) {
        return "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":" + epoch
                + ",\"built\":\"" + built + "\""
                + ",\"revocationsSeqFloor\":" + revFloor
                + ",\"manifestSeqFloor\":" + manFloor
                + ",\"keys\":[" + keys + "]}";
    }

    private static String keyJson(String kid, String pub, String roles) {
        return "{\"kid\":\"" + kid + "\",\"pub\":\"" + pub + "\",\"roles\":" + roles + "}";
    }

    private static String trustWithKeys(String... keys) {
        return trustJson("1", TS1, "0", "0", String.join(",", keys));
    }

    private static String validTrust() {
        return trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\"]"),
                keyJson(KID_B, PUB_B, "[\"release\",\"revocation\"]"));
    }

    private static String signaturesJson(String payload, String entries) {
        return "{\"type\":\"quicraft.sig\",\"v\":1,\"payloadSha256\":\"" + payload
                + "\",\"signatures\":[" + entries + "]}";
    }

    private static String sigEntry(String kid, String sig) {
        return "{\"kid\":\"" + kid + "\",\"sig\":\"" + sig + "\"}";
    }

    private static String validSignatures() {
        return signaturesJson(SHA256_ABC, sigEntry(SIG_KID, SIG));
    }

    private static String releaseDoc(
            String seq, String issued, String expires, String minRev, String releases,
            String withdrawn) {
        return "{\"type\":\"quicraft.release\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\""
                + ",\"expires\":\"" + expires + "\""
                + ",\"minRevocationsSeq\":" + minRev
                + ",\"releases\":[" + releases + "]"
                + ",\"withdrawn\":[" + withdrawn + "]}";
    }

    private static String releaseEntry(
            String platform, String version, String targets, String file, String size,
            String sha512, String tag) {
        return "{\"platform\":\"" + platform + "\",\"version\":\"" + version
                + "\",\"targets\":[" + targets + "],\"file\":\"" + file
                + "\",\"size\":" + size + ",\"sha512\":\"" + sha512 + "\",\"tag\":\"" + tag + "\"}";
    }

    private static String withdrawal(String platform, String version, String sha512) {
        return "{\"platform\":\"" + platform + "\",\"version\":\"" + version
                + "\",\"sha512\":\"" + sha512 + "\"}";
    }

    private static String releaseWithSingleEntry(
            String platform, String version, String targets, String file, String size,
            String sha512, String tag) {
        return releaseDoc("1", TS1, TS2, "0",
                releaseEntry(platform, version, targets, file, size, sha512, tag), "");
    }

    private static String defaultFile() {
        return "a.jar";
    }

    private static String validRelease() {
        return releaseDoc("42", TS1, TS2, "3",
                releaseEntry("fabric-26x", "0.4.0", "\"26.1\",\"26.1.1\"",
                        "quicraft-fabric-26x-0.4.0.jar", "4194304", SHA512_A, "v0.4.0"),
                withdrawal("fabric-26x", "0.3.9", H9));
    }

    private static String revocationsDoc(
            String seq, String issued, String expires, String minManifest, String keys,
            String blocked) {
        return "{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\""
                + ",\"expires\":\"" + expires + "\""
                + ",\"minManifestSeq\":" + minManifest
                + ",\"revokedKeys\":[" + keys + "]"
                + ",\"blockedArtifacts\":[" + blocked + "]}";
    }

    private static String revokedKey(String kid, String pub) {
        return "{\"kid\":\"" + kid + "\",\"pub\":\"" + pub + "\"}";
    }

    private static String blockedEntry(String sha512) {
        return "{\"sha512\":\"" + sha512 + "\"}";
    }

    private static String validRevocations() {
        return revocationsDoc("3", TS1, "2027-11-01T12:00:00Z", "0",
                revokedKey(KID_C, PUB_C), blockedEntry(H7));
    }

    // ---- small helpers -----------------------------------------------------------------------

    private static String replaceOnce(String json, String from, String to) {
        assertTrue(json.contains(from), "fixture is missing token: " + from);
        assertFalse(from.equals(to), "replacement must change the document");
        return json.replace(from, to);
    }

    private static String addRootField(String json, String field) {
        return json.substring(0, json.length() - 1) + "," + field + "}";
    }

    private static byte[] padTo(byte[] base, int target) {
        assertTrue(target >= base.length, "padding target smaller than document");
        byte[] out = new byte[target];
        System.arraycopy(base, 0, out, 0, base.length);
        Arrays.fill(out, base.length, target, (byte) ' ');
        return out;
    }

    private static UpdateRejectedException assertNoEcho(Executable executable) {
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, executable);
        assertNotNull(e.getMessage(), "rejection message must not be null");
        assertFalse(e.getMessage().contains(MARKER),
                "error echoed the supplied input: " + e.getMessage());
        return e;
    }

    private static UpdateDocuments.Trust parseTrust(String json) throws Exception {
        return DocumentParser.parseTrust(utf8(json));
    }

    private static UpdateDocuments.SignatureEnvelope parseSignatures(String json) throws Exception {
        return DocumentParser.parseSignatures(utf8(json));
    }

    private static UpdateDocuments.ReleaseManifest parseRelease(String json) throws Exception {
        return DocumentParser.parseRelease(utf8(json));
    }

    private static UpdateDocuments.Revocations parseRevocations(String json) throws Exception {
        return DocumentParser.parseRevocations(utf8(json));
    }

    private static UpdateRejectedException rejectsTrust(String json) {
        return assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseTrust(utf8(json)));
    }

    private static UpdateRejectedException rejectsTrustBytes(byte[] bytes) {
        return assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseTrust(bytes));
    }

    private static UpdateRejectedException rejectsSignatures(String json) {
        return assertThrows(
                UpdateRejectedException.class, () -> DocumentParser.parseSignatures(utf8(json)));
    }

    private static UpdateRejectedException rejectsSignaturesBytes(byte[] bytes) {
        return assertThrows(
                UpdateRejectedException.class, () -> DocumentParser.parseSignatures(bytes));
    }

    private static UpdateRejectedException rejectsRelease(String json) {
        return assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseRelease(utf8(json)));
    }

    private static UpdateRejectedException rejectsReleaseBytes(byte[] bytes) {
        return assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseRelease(bytes));
    }

    private static UpdateRejectedException rejectsRevocations(String json) {
        return assertThrows(
                UpdateRejectedException.class, () -> DocumentParser.parseRevocations(utf8(json)));
    }

    private static UpdateRejectedException rejectsRevocationsBytes(byte[] bytes) {
        return assertThrows(
                UpdateRejectedException.class, () -> DocumentParser.parseRevocations(bytes));
    }

    // ---- accepted documents with exact field results -----------------------------------------

    @Test
    void parsesValidTrustDocumentWithExactFieldResults() throws Exception {
        UpdateDocuments.Trust trust = parseTrust(validTrust());
        assertEquals(1, trust.epoch());
        assertEquals(Instant.parse(TS1), trust.built());
        assertEquals(0L, trust.revocationsSeqFloor());
        assertEquals(0L, trust.manifestSeqFloor());
        assertEquals(2, trust.keys().size());
        assertEquals(KID_A, trust.keys().get(0).kid());
        assertEquals(PUB_A, trust.keys().get(0).pub());
        assertEquals(List.of("release"), trust.keys().get(0).roles());
        assertEquals(KID_B, trust.keys().get(1).kid());
        assertEquals(PUB_B, trust.keys().get(1).pub());
        assertEquals(List.of("release", "revocation"), trust.keys().get(1).roles());
    }

    @Test
    void parsesValidSignatureEnvelopeWithExactFieldResults() throws Exception {
        UpdateDocuments.SignatureEnvelope envelope = parseSignatures(validSignatures());
        assertEquals(SHA256_ABC, envelope.payloadSha256());
        assertEquals(1, envelope.signatures().size());
        assertEquals(SIG_KID, envelope.signatures().get(0).kid());
        assertEquals(SIG, envelope.signatures().get(0).sig());
    }

    @Test
    void parsesValidReleaseManifestWithExactFieldResults() throws Exception {
        UpdateDocuments.ReleaseManifest manifest = parseRelease(validRelease());
        assertEquals(42L, manifest.seq());
        assertEquals(Instant.parse(TS1), manifest.issued());
        assertEquals(Instant.parse(TS2), manifest.expires());
        assertEquals(3L, manifest.minRevocationsSeq());
        assertEquals(1, manifest.releases().size());
        UpdateDocuments.ReleaseEntry release = manifest.releases().get(0);
        assertEquals("fabric-26x", release.platform());
        assertEquals(UpdateVersion.parse("0.4.0"), release.version());
        assertEquals(List.of("26.1", "26.1.1"), release.targets());
        assertEquals("quicraft-fabric-26x-0.4.0.jar", release.file());
        assertEquals(4194304L, release.size());
        assertEquals(SHA512_A, release.sha512());
        assertEquals("v0.4.0", release.tag());
        assertEquals(1, manifest.withdrawn().size());
        UpdateDocuments.Withdrawal withdrawal = manifest.withdrawn().get(0);
        assertEquals("fabric-26x", withdrawal.platform());
        assertEquals(UpdateVersion.parse("0.3.9"), withdrawal.version());
        assertEquals(H9, withdrawal.sha512());
    }

    @Test
    void parsesValidRevocationsDocumentWithExactFieldResults() throws Exception {
        UpdateDocuments.Revocations revocations = parseRevocations(validRevocations());
        assertEquals(3L, revocations.seq());
        assertEquals(Instant.parse(TS1), revocations.issued());
        assertEquals(Instant.parse("2027-11-01T12:00:00Z"), revocations.expires());
        assertEquals(0L, revocations.minManifestSeq());
        assertEquals(1, revocations.revokedKeys().size());
        assertEquals(KID_C, revocations.revokedKeys().get(0).kid());
        assertEquals(PUB_C, revocations.revokedKeys().get(0).pub());
        assertEquals(List.of(H7), revocations.blockedArtifacts());
    }

    // ---- header: type and version ------------------------------------------------------------

    @Test
    void rejectsWrongTypeOnEverySchema() {
        rejectsTrust(replaceOnce(validTrust(), "\"type\":\"quicraft.trust\"",
                "\"type\":\"quicraft.release\""));
        rejectsSignatures(replaceOnce(validSignatures(), "\"type\":\"quicraft.sig\"",
                "\"type\":\"quicraft.release\""));
        rejectsRelease(replaceOnce(validRelease(), "\"type\":\"quicraft.release\"",
                "\"type\":\"quicraft.revocations\""));
        rejectsRevocations(replaceOnce(validRevocations(), "\"type\":\"quicraft.revocations\"",
                "\"type\":\"quicraft.trust\""));
    }

    @Test
    void rejectsVersionOtherThanOneOnEverySchema() {
        rejectsTrust(replaceOnce(validTrust(), "\"v\":1", "\"v\":0"));
        rejectsTrust(replaceOnce(validTrust(), "\"v\":1", "\"v\":2"));
        rejectsSignatures(replaceOnce(validSignatures(), "\"v\":1", "\"v\":0"));
        rejectsRelease(replaceOnce(validRelease(), "\"v\":1", "\"v\":2"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"v\":1", "\"v\":0"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"v\":1", "\"v\":\"1\""));
    }

    // ---- trust -------------------------------------------------------------------------------

    @Test
    void trustRejectsZeroKeysAndAcceptsSingleKey() throws Exception {
        rejectsTrust(trustWithKeys());
        UpdateDocuments.Trust one =
                parseTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[\"release\",\"revocation\"]")));
        assertEquals(1, one.keys().size());
    }

    @Test
    void trustAcceptsFourKeysAndRejectsFive() throws Exception {
        String four = trustWithKeys(
                generatedKeyJson(1, "[\"release\"]"),
                generatedKeyJson(2, "[\"release\"]"),
                generatedKeyJson(3, "[\"release\"]"),
                generatedKeyJson(4, "[\"release\",\"revocation\"]"));
        assertEquals(4, parseTrust(four).keys().size());
        String five = trustWithKeys(
                generatedKeyJson(1, "[\"release\"]"),
                generatedKeyJson(2, "[\"release\"]"),
                generatedKeyJson(3, "[\"release\"]"),
                generatedKeyJson(4, "[\"release\"]"),
                generatedKeyJson(5, "[\"release\",\"revocation\"]"));
        rejectsTrust(five);
    }

    @Test
    void trustAcceptsRolesInEitherOrder() throws Exception {
        assertNotNull(parseTrust(trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\"]"),
                keyJson(KID_B, PUB_B, "[\"revocation\",\"release\"]"))));
    }

    @Test
    void trustRejectsNoRevocationKey() {
        rejectsTrust(trustWithKeys(keyJson(KID_A, PUB_A, "[\"release\"]")));
        rejectsTrust(trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\"]"),
                keyJson(KID_B, PUB_B, "[\"release\"]")));
    }

    @Test
    void trustRejectsTwoRevocationKeys() {
        rejectsTrust(trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\",\"revocation\"]"),
                keyJson(KID_B, PUB_B, "[\"release\",\"revocation\"]")));
    }

    @Test
    void trustRejectsRevocationRoleWithoutRelease() {
        rejectsTrust(trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\"]"),
                keyJson(KID_B, PUB_B, "[\"revocation\"]")));
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[\"revocation\"]")));
    }

    @Test
    void trustRejectsDuplicatePublicKey() {
        rejectsTrust(trustWithKeys(
                keyJson(KID_A, PUB_A, "[\"release\"]"),
                keyJson(KID_A, PUB_A, "[\"release\",\"revocation\"]")));
    }

    @Test
    void trustRejectsKidNotDerivedFromPublicKey() {
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_A, "[\"release\",\"revocation\"]")));
        rejectsTrust(trustWithKeys(keyJson(KID_A, PUB_B, "[\"release\",\"revocation\"]")));
    }

    @Test
    void trustRejectsEmptyUnknownAndDuplicateRoles() {
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[]")));
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[\"root\"]")));
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[\"release\",\"release\"]")));
        rejectsTrust(trustWithKeys(keyJson(KID_B, PUB_B, "[\"release\",\"release\",\"revocation\"]")));
    }

    @Test
    void trustRejectsEpochOutsideIntRange() throws Exception {
        assertEquals(1, parseTrust(validTrust()).epoch());
        assertEquals(Integer.MAX_VALUE,
                parseTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":2147483647")).epoch());
        rejectsTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":0"));
        rejectsTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":2147483648"));
        rejectsTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":-1"));
    }

    @Test
    void trustRejectsSeqFloorsOutsideUint53() throws Exception {
        long max = 9007199254740991L;
        assertEquals(max, parseTrust(replaceOnce(validTrust(), "\"revocationsSeqFloor\":0",
                "\"revocationsSeqFloor\":" + max)).revocationsSeqFloor());
        rejectsTrust(replaceOnce(validTrust(), "\"revocationsSeqFloor\":0",
                "\"revocationsSeqFloor\":9007199254740992"));
        rejectsTrust(replaceOnce(validTrust(), "\"manifestSeqFloor\":0",
                "\"manifestSeqFloor\":-1"));
    }

    @Test
    void trustRejectsRootAndKeyLevelUnknownMissingNullWrongType() {
        rejectsTrust(addRootField(validTrust(), "\"extra\":1"));
        rejectsTrust(replaceOnce(validTrust(), ",\"epoch\":1", ""));
        rejectsTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":null"));
        rejectsTrust(replaceOnce(validTrust(), "\"epoch\":1", "\"epoch\":\"1\""));

        rejectsTrust(replaceOnce(validTrust(), "\"roles\":[\"release\"]",
                "\"roles\":[\"release\"],\"extra\":1"));
        rejectsTrust(replaceOnce(validTrust(), "\"kid\":\"" + KID_A + "\",", ""));
        rejectsTrust(replaceOnce(validTrust(), "\"kid\":\"" + KID_A + "\"", "\"kid\":null"));
        rejectsTrust(replaceOnce(validTrust(), "\"pub\":\"" + PUB_A + "\"", "\"pub\":123"));
    }

    @Test
    void trustByteCapExactAcceptedOneOverRejected() throws Exception {
        byte[] base = utf8(validTrust());
        assertNotNull(DocumentParser.parseTrust(padTo(base, TRUST_CAP)));
        rejectsTrustBytes(padTo(base, TRUST_CAP + 1));
    }

    // ---- signatures --------------------------------------------------------------------------

    @Test
    void signatureAcceptsOneAndTwoEntriesAndRejectsZeroAndThree() throws Exception {
        assertEquals(1, parseSignatures(validSignatures()).signatures().size());
        assertEquals(2, parseSignatures(signaturesJson(SHA256_ABC,
                sigEntry(SIG_KID, SIG) + "," + sigEntry("1111111111111111", SIG)))
                .signatures().size());
        rejectsSignatures(signaturesJson(SHA256_ABC, ""));
        rejectsSignatures(signaturesJson(SHA256_ABC,
                sigEntry(SIG_KID, SIG) + "," + sigEntry("1111111111111111", SIG)
                        + "," + sigEntry("2222222222222222", SIG)));
    }

    @Test
    void signatureRejectsDuplicateKid() {
        rejectsSignatures(signaturesJson(SHA256_ABC,
                sigEntry(SIG_KID, SIG) + "," + sigEntry(SIG_KID, "02".repeat(64))));
    }

    static Stream<String> malformedSignatures() {
        return Stream.of(
                replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\"",
                        "\"kid\":\"0123456789abcde\""),
                replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\"",
                        "\"kid\":\"0123456789abcdef0\""),
                replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\"",
                        "\"kid\":\"0123456789ABCDEF\""),
                replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\"",
                        "\"kid\":\"0123456789abcdeg\""),
                replaceOnce(validSignatures(), "\"sig\":\"" + SIG + "\"",
                        "\"sig\":\"" + "01".repeat(63) + "\""),
                replaceOnce(validSignatures(), "\"sig\":\"" + SIG + "\"",
                        "\"sig\":\"" + "01".repeat(65) + "\""),
                replaceOnce(validSignatures(), "\"sig\":\"" + SIG + "\"",
                        "\"sig\":\"" + "0A".repeat(64) + "\""),
                replaceOnce(validSignatures(), "\"payloadSha256\":\"" + SHA256_ABC + "\"",
                        "\"payloadSha256\":\"" + SHA256_ABC.substring(0, 62) + "\""),
                replaceOnce(validSignatures(), "\"payloadSha256\":\"" + SHA256_ABC + "\"",
                        "\"payloadSha256\":\"" + SHA256_ABC + "00\""),
                replaceOnce(validSignatures(), "\"payloadSha256\":\"" + SHA256_ABC + "\"",
                        "\"payloadSha256\":\"" + SHA256_ABC.toUpperCase(Locale.ROOT) + "\""));
    }

    @ParameterizedTest
    @MethodSource("malformedSignatures")
    void signatureRejectsBadKidSigAndHashShapes(String json) {
        rejectsSignatures(json);
    }

    @Test
    void signatureAcceptsUntrustedKidWithoutAuthenticityClaim() throws Exception {
        UpdateDocuments.SignatureEnvelope envelope = parseSignatures(validSignatures());
        assertEquals(SIG_KID, envelope.signatures().get(0).kid());
        // A structurally valid kid that is not trusted still parses: trust is a separate layer.
        assertNotNull(parseSignatures(
                signaturesJson(SHA256_ABC, sigEntry("ffffffffffffffff", SIG))));
    }

    @Test
    void signatureRejectsRootAndEntryLevelUnknownMissingNullWrongType() {
        rejectsSignatures(addRootField(validSignatures(), "\"extra\":1"));
        rejectsSignatures(replaceOnce(validSignatures(),
                ",\"payloadSha256\":\"" + SHA256_ABC + "\"", ""));
        rejectsSignatures(replaceOnce(validSignatures(),
                "\"payloadSha256\":\"" + SHA256_ABC + "\"", "\"payloadSha256\":null"));
        rejectsSignatures(replaceOnce(validSignatures(),
                "\"payloadSha256\":\"" + SHA256_ABC + "\"", "\"payloadSha256\":42"));

        rejectsSignatures(replaceOnce(validSignatures(), "\"sig\":\"" + SIG + "\"",
                "\"sig\":\"" + SIG + "\",\"extra\":1"));
        rejectsSignatures(replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\",", ""));
        rejectsSignatures(replaceOnce(validSignatures(), "\"kid\":\"" + SIG_KID + "\"",
                "\"kid\":null"));
        rejectsSignatures(replaceOnce(validSignatures(), "\"sig\":\"" + SIG + "\"", "\"sig\":true"));
    }

    @Test
    void signatureByteCapExactAcceptedOneOverRejected() throws Exception {
        byte[] base = utf8(validSignatures());
        assertNotNull(DocumentParser.parseSignatures(padTo(base, SIGNATURES_CAP)));
        rejectsSignaturesBytes(padTo(base, SIGNATURES_CAP + 1));
    }

    // ---- release manifest --------------------------------------------------------------------

    @Test
    void releaseAcceptsZeroReleases() throws Exception {
        UpdateDocuments.ReleaseManifest manifest =
                parseRelease(releaseDoc("1", TS1, TS2, "0", "", ""));
        assertEquals(0, manifest.releases().size());
        assertEquals(0, manifest.withdrawn().size());
    }

    @Test
    void releaseAcceptsThreeDistinctPlatformsAndRejectsFourthDuplicate() throws Exception {
        String three = releaseDoc("1", TS1, TS2, "0",
                releaseEntry("velocity", "0.4.0", "\"4.2\"", "q-velocity.jar", "1", SHA512_A,
                        "v0.4.0") + ","
                        + releaseEntry("fabric-26x", "0.4.0", "\"26.1\"", "q-fabric26x.jar", "1",
                                SHA512_B, "v0.4.0") + ","
                        + releaseEntry("fabric-1.21.11", "0.4.0", "\"1.21.11\"", "q-fabric121.jar",
                                "1", SHA512_C, "v0.4.0"),
                "");
        assertEquals(3, parseRelease(three).releases().size());
        String four = releaseDoc("1", TS1, TS2, "0",
                releaseEntry("velocity", "0.4.0", "\"4.2\"", "q-velocity.jar", "1", SHA512_A,
                        "v0.4.0") + ","
                        + releaseEntry("fabric-26x", "0.4.0", "\"26.1\"", "q-fabric26x.jar", "1",
                                SHA512_B, "v0.4.0") + ","
                        + releaseEntry("fabric-1.21.11", "0.4.0", "\"1.21.11\"", "q-fabric121.jar",
                                "1", SHA512_C, "v0.4.0") + ","
                        + releaseEntry("velocity", "0.5.0", "\"4.2\"", "q-velocity2.jar", "1",
                                "11".repeat(64), "v0.5.0"),
                "");
        rejectsRelease(four);
    }

    @Test
    void releaseRejectsSeventeenEntriesViaPlatformUnicity() {
        // The 16-entry cap is unreachable with only three platforms, so platform unicity rejects
        // a 17-entry manifest first. Either way the document must be rejected.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String platform = switch (i % 3) {
                case 0 -> "velocity";
                case 1 -> "fabric-26x";
                default -> "fabric-1.21.11";
            };
            sb.append(releaseEntry(platform, "0.0." + i, "\"x\"", "f-" + i + ".jar", "1",
                    hex128(i + 1), "v0.0." + i));
        }
        rejectsRelease(releaseDoc("1", TS1, TS2, "0", sb.toString(), ""));
    }

    @Test
    void releaseRejectsUnknownPlatform() {
        rejectsRelease(releaseWithSingleEntry("paper", "0.4.0", "\"26.1\"", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric", "0.4.0", "\"26.1\"", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("Velocity", "0.4.0", "\"4.2\"", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
    }

    private static String targets(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append('t').append(i).append('"');
        }
        return sb.toString();
    }

    @Test
    void releaseRejectsTargetsBoundsAndDuplicates() throws Exception {
        assertEquals(1, parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0")).releases().get(0).targets().size());
        assertEquals(64, parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", targets(64),
                defaultFile(), "1", SHA512_A, "v0.4.0")).releases().get(0).targets().size());
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", targets(65), defaultFile(),
                "1", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"\"", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\",\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0"));
        assertEquals(1, parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0",
                "\"" + "a".repeat(32) + "\"", defaultFile(), "1", SHA512_A, "v0.4.0"))
                .releases().get(0).targets().size());
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0",
                "\"" + "a".repeat(33) + "\"", defaultFile(), "1", SHA512_A, "v0.4.0"));
    }

    @Test
    void releaseRejectsFileNamesOutsideRegex() throws Exception {
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                "a.jar", "1", SHA512_A, "v0.4.0")));
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                "a".repeat(124) + ".jar", "1", SHA512_A, "v0.4.0")));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", "jarfile", "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", "bad name.jar",
                "1", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", "bad/name.jar",
                "1", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", ".jar", "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", "UPPER.JAR", "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                "a".repeat(125) + ".jar", "1", SHA512_A, "v0.4.0"));
    }

    @Test
    void releaseRejectsTagsOutsideRegex() throws Exception {
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0")));
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v" + "a".repeat(40))));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "v"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "v" + "a".repeat(41)));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "v1/2"));
    }

    @Test
    void releaseRejectsSha512Shape() throws Exception {
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0")));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A.substring(0, 126), "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A + "00", "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A.toUpperCase(Locale.ROOT), "v0.4.0"));
    }

    @Test
    void releaseRejectsSizeBounds() throws Exception {
        assertEquals(1L, parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0")).releases().get(0).size());
        assertEquals(67108864L, parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0",
                "\"26.1\"", defaultFile(), "67108864", SHA512_A, "v0.4.0"))
                .releases().get(0).size());
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "0", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "67108865", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"", defaultFile(),
                "\"1\"", SHA512_A, "v0.4.0"));
    }

    @Test
    void releaseRejectsVersionGrammar() throws Exception {
        assertNotNull(parseRelease(releaseWithSingleEntry("fabric-26x", "0.4.0", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0")));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "v0.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4", "\"26.1\"", defaultFile(), "1",
                SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "01.4.0", "\"26.1\"", defaultFile(),
                "1", SHA512_A, "v0.4.0"));
        rejectsRelease(releaseWithSingleEntry("fabric-26x", "0.4.0+build", "\"26.1\"",
                defaultFile(), "1", SHA512_A, "v0.4.0"));
    }

    @Test
    void releaseRejectsSeqOutsideUint53() throws Exception {
        assertEquals(9007199254740991L, parseRelease(replaceOnce(validRelease(), "\"seq\":42",
                "\"seq\":9007199254740991")).seq());
        rejectsRelease(replaceOnce(validRelease(), "\"seq\":42",
                "\"seq\":9007199254740992"));
        rejectsRelease(replaceOnce(validRelease(), "\"seq\":42", "\"seq\":-1"));
        rejectsRelease(replaceOnce(validRelease(), "\"seq\":42", "\"seq\":01"));
    }

    @Test
    void releaseAcceptsExpiresBeforeIssuedBecauseOrderingIsNotChecked() throws Exception {
        // issued < expires is a freshness policy rule (docs/updater.md 12.5), not a schema check.
        assertNotNull(parseRelease(releaseDoc("1", TS2, TS1, "0", "", "")));
        assertNotNull(parseRelease(releaseDoc("1", TS1, TS1, "0", "", "")));
    }

    @Test
    void withdrawalAcceptsZeroAndSixtyFourAndRejectsSixtyFive() throws Exception {
        assertEquals(0, parseRelease(releaseDoc("1", TS1, TS2, "0", "", "")).withdrawn().size());
        assertEquals(64, parseRelease(releaseWithWithdrawals(64)).withdrawn().size());
        rejectsRelease(releaseWithWithdrawals(65));
    }

    private static String releaseWithWithdrawals(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            String platform = switch (i % 3) {
                case 0 -> "velocity";
                case 1 -> "fabric-26x";
                default -> "fabric-1.21.11";
            };
            sb.append(withdrawal(platform, "0.0." + i, hex128(i)));
        }
        return releaseDoc("1", TS1, TS2, "0", "", sb.toString());
    }

    @Test
    void withdrawalRejectsDuplicatePlatformVersionAndDuplicateHash() {
        rejectsRelease(releaseDoc("1", TS1, TS2, "0", "",
                withdrawal("velocity", "0.1.0", SHA512_A) + ","
                        + withdrawal("velocity", "0.1.0", SHA512_B)));
        rejectsRelease(releaseDoc("1", TS1, TS2, "0", "",
                withdrawal("velocity", "0.1.0", SHA512_A) + ","
                        + withdrawal("fabric-26x", "0.1.1", SHA512_A)));
    }

    @Test
    void releaseRejectsRootAndEntryLevelUnknownMissingNullWrongType() {
        rejectsRelease(addRootField(validRelease(), "\"extra\":1"));
        rejectsRelease(replaceOnce(validRelease(), ",\"seq\":42", ""));
        rejectsRelease(replaceOnce(validRelease(), "\"seq\":42", "\"seq\":null"));
        rejectsRelease(replaceOnce(validRelease(), "\"seq\":42", "\"seq\":\"42\""));

        rejectsRelease(replaceOnce(validRelease(), "\"tag\":\"v0.4.0\"",
                "\"tag\":\"v0.4.0\",\"extra\":1"));
        rejectsRelease(replaceOnce(validRelease(), ",\"tag\":\"v0.4.0\"", ""));
        rejectsRelease(replaceOnce(validRelease(), "\"tag\":\"v0.4.0\"", "\"tag\":null"));
        rejectsRelease(replaceOnce(validRelease(), "\"file\":\"quicraft-fabric-26x-0.4.0.jar\"",
                "\"file\":42"));
    }

    @Test
    void releaseRejectsWithdrawalLevelUnknownMissingNullWrongType() {
        rejectsRelease(replaceOnce(validRelease(), "\"version\":\"0.3.9\"",
                "\"version\":\"0.3.9\",\"extra\":1"));
        rejectsRelease(replaceOnce(validRelease(), ",\"version\":\"0.3.9\"", ""));
        rejectsRelease(replaceOnce(validRelease(), "\"version\":\"0.3.9\"", "\"version\":null"));
        rejectsRelease(replaceOnce(validRelease(), "\"sha512\":\"" + H9 + "\"", "\"sha512\":123"));
    }

    @Test
    void releaseByteCapExactAcceptedOneOverRejected() throws Exception {
        byte[] base = utf8(validRelease());
        assertNotNull(DocumentParser.parseRelease(padTo(base, RELEASE_CAP)));
        rejectsReleaseBytes(padTo(base, RELEASE_CAP + 1));
    }

    // ---- revocations -------------------------------------------------------------------------

    @Test
    void revocationsAcceptsZeroKeysAndRejectsSeventeen() throws Exception {
        assertEquals(0, parseRevocations(revocationsDoc("1", TS1, TS2, "0", "", ""))
                .revokedKeys().size());
        assertEquals(16, parseRevocations(revocationsWithKeys(16)).revokedKeys().size());
        rejectsRevocations(revocationsWithKeys(17));
    }

    private static String revocationsWithKeys(int count) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            String pub = pubHex(i);
            sb.append(revokedKey(independentKid(pub), pub));
        }
        return revocationsDoc("1", TS1, TS2, "0", sb.toString(), "");
    }

    @Test
    void revocationsRejectsKidNotDerivedAndDuplicatePublicKey() throws Exception {
        rejectsRevocations(replaceOnce(validRevocations(), "\"kid\":\"" + KID_C + "\"",
                "\"kid\":\"0000000000000000\""));
        rejectsRevocations(revocationsDoc("1", TS1, TS2, "0",
                revokedKey(KID_C, PUB_C) + "," + revokedKey(KID_C, PUB_C), ""));
    }

    @Test
    void revocationsAccepts256BlockedAndRejects257() throws Exception {
        assertEquals(0, parseRevocations(revocationsDoc("1", TS1, TS2, "0", "", ""))
                .blockedArtifacts().size());
        assertEquals(256, parseRevocations(revocationsWithBlocked(256)).blockedArtifacts().size());
        rejectsRevocations(revocationsWithBlocked(257));
    }

    private static String revocationsWithBlocked(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append(blockedEntry(hex128(i)));
        }
        return revocationsDoc("1", TS1, TS2, "0", "", sb.toString());
    }

    @Test
    void revocationsRejectsDuplicateBlockedHash() {
        rejectsRevocations(revocationsDoc("1", TS1, TS2, "0", "",
                blockedEntry(H7) + "," + blockedEntry(H7)));
    }

    @Test
    void revocationsRejectsRootKeyAndBlockedLevelUnknownMissingNullWrongType() {
        rejectsRevocations(addRootField(validRevocations(), "\"extra\":1"));
        rejectsRevocations(replaceOnce(validRevocations(), ",\"seq\":3", ""));
        rejectsRevocations(replaceOnce(validRevocations(), "\"seq\":3", "\"seq\":null"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"seq\":3", "\"seq\":\"3\""));

        rejectsRevocations(replaceOnce(validRevocations(), "\"kid\":\"" + KID_C + "\"",
                "\"kid\":\"" + KID_C + "\",\"extra\":1"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"kid\":\"" + KID_C + "\",", ""));
        rejectsRevocations(replaceOnce(validRevocations(), "\"kid\":\"" + KID_C + "\"",
                "\"kid\":null"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"pub\":\"" + PUB_C + "\"",
                "\"pub\":123"));

        rejectsRevocations(replaceOnce(validRevocations(), "\"sha512\":\"" + H7 + "\"",
                "\"sha512\":\"" + H7 + "\",\"extra\":1"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"sha512\":\"" + H7 + "\"", ""));
        rejectsRevocations(replaceOnce(validRevocations(), "\"sha512\":\"" + H7 + "\"",
                "\"sha512\":null"));
        rejectsRevocations(replaceOnce(validRevocations(), "\"sha512\":\"" + H7 + "\"",
                "\"sha512\":123"));
    }

    @Test
    void revocationsByteCapExactAcceptedOneOverRejected() throws Exception {
        byte[] base = utf8(validRevocations());
        assertNotNull(DocumentParser.parseRevocations(padTo(base, REVOCATIONS_CAP)));
        rejectsRevocationsBytes(padTo(base, REVOCATIONS_CAP + 1));
    }

    // ---- timestamps --------------------------------------------------------------------------

    static Stream<String> acceptedTimestamps() {
        return Stream.of(
                "2026-11-01T12:00:00Z",
                "2024-02-29T00:00:00Z",
                "2000-01-01T23:59:59Z",
                "1999-12-31T00:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("acceptedTimestamps")
    void acceptsStrictUtcTimestamps(String timestamp) throws Exception {
        assertNotNull(parseRelease(releaseDoc("1", timestamp, TS2, "0", "", "")));
    }

    static Stream<String> rejectedTimestamps() {
        return Stream.of(
                "2026-11-01T12:00:00",
                "2026-11-01T12:00:00z",
                "2026-11-01 12:00:00Z",
                "2026-11-01T12:00:00+00:00",
                "2026-11-01T12:00:00.5Z",
                "2026-11-01T12:00:60Z",
                "2026-11-01T24:00:00Z",
                "2026-13-01T12:00:00Z",
                "2026-11-32T12:00:00Z",
                "2023-02-29T12:00:00Z",
                "2026-11-01T12:60:00Z",
                "2026-1-01T12:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("rejectedTimestamps")
    void rejectsNonStrictOrNonUtcTimestamps(String timestamp) {
        rejectsRelease(releaseDoc("1", timestamp, TS2, "0", "", ""));
    }

    // ---- immutability ------------------------------------------------------------------------

    @Test
    void recordsDefensivelyCopyEveryListField() throws Exception {
        List<String> roles = new ArrayList<>(List.of("release"));
        UpdateDocuments.TrustedKey trustedKey =
                new UpdateDocuments.TrustedKey(KID_A, PUB_A, roles);
        roles.add("revocation");
        assertEquals(List.of("release"), trustedKey.roles());
        assertThrows(UnsupportedOperationException.class, () -> trustedKey.roles().add("x"));

        List<UpdateDocuments.TrustedKey> keys = new ArrayList<>(List.of(trustedKey));
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, Instant.EPOCH, 0, 0, keys);
        keys.clear();
        assertEquals(1, trust.keys().size());
        assertThrows(UnsupportedOperationException.class, () -> trust.keys().clear());

        List<UpdateDocuments.SignatureEntry> entries = new ArrayList<>(
                List.of(new UpdateDocuments.SignatureEntry(SIG_KID, SIG)));
        UpdateDocuments.SignatureEnvelope envelope =
                new UpdateDocuments.SignatureEnvelope(SHA256_ABC, entries);
        entries.clear();
        assertEquals(1, envelope.signatures().size());
        assertThrows(UnsupportedOperationException.class, () -> envelope.signatures().clear());

        List<String> targets = new ArrayList<>(List.of("26.1"));
        UpdateDocuments.ReleaseEntry release = new UpdateDocuments.ReleaseEntry(
                "velocity", UpdateVersion.parse("1.0.0"), targets, "a.jar", 1L, SHA512_A, "v1.0.0");
        targets.add("26.2");
        assertEquals(List.of("26.1"), release.targets());
        assertThrows(UnsupportedOperationException.class, () -> release.targets().add("26.3"));

        List<UpdateDocuments.ReleaseEntry> releases = new ArrayList<>(List.of(release));
        List<UpdateDocuments.Withdrawal> withdrawn = new ArrayList<>(
                List.of(new UpdateDocuments.Withdrawal("velocity", UpdateVersion.parse("0.9.0"),
                        SHA512_B)));
        UpdateDocuments.ReleaseManifest manifest =
                new UpdateDocuments.ReleaseManifest(1, Instant.EPOCH, Instant.EPOCH, 0, releases,
                        withdrawn);
        releases.clear();
        withdrawn.clear();
        assertEquals(1, manifest.releases().size());
        assertEquals(1, manifest.withdrawn().size());
        assertThrows(UnsupportedOperationException.class, () -> manifest.releases().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.withdrawn().clear());

        List<UpdateDocuments.RevokedKey> revokedKeys = new ArrayList<>(
                List.of(new UpdateDocuments.RevokedKey(KID_C, PUB_C)));
        List<String> blockedArtifacts = new ArrayList<>(List.of(H7));
        UpdateDocuments.Revocations revocations = new UpdateDocuments.Revocations(
                1, Instant.EPOCH, Instant.EPOCH, 0, revokedKeys, blockedArtifacts);
        revokedKeys.clear();
        blockedArtifacts.clear();
        assertEquals(1, revocations.revokedKeys().size());
        assertEquals(1, revocations.blockedArtifacts().size());
        assertThrows(UnsupportedOperationException.class, () -> revocations.revokedKeys().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> revocations.blockedArtifacts().clear());
    }

    @Test
    void parserResultsExposeUnmodifiableLists() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> parseTrust(validTrust()).keys().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> parseSignatures(validSignatures()).signatures().clear());
        UpdateDocuments.ReleaseManifest manifest = parseRelease(validRelease());
        assertThrows(UnsupportedOperationException.class, () -> manifest.releases().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.withdrawn().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.releases().get(0)
                .targets().clear());
        UpdateDocuments.Revocations revocations = parseRevocations(validRevocations());
        assertThrows(UnsupportedOperationException.class, () -> revocations.revokedKeys().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> revocations.blockedArtifacts().clear());
    }

    // ---- error hygiene -----------------------------------------------------------------------

    @Test
    void rejectionMessagesNeverEchoSuppliedInput() {
        assertNoEcho(() -> DocumentParser.parseTrust(utf8(replaceOnce(validTrust(), "\"epoch\":1",
                "\"epoch\":\"" + MARKER + "\""))));
        assertNoEcho(() -> DocumentParser.parseSignatures(utf8(replaceOnce(validSignatures(),
                "\"payloadSha256\":\"" + SHA256_ABC + "\"",
                "\"payloadSha256\":\"" + MARKER + "\""))));
        assertNoEcho(() -> DocumentParser.parseRelease(utf8(replaceOnce(validRelease(),
                "\"tag\":\"v0.4.0\"", "\"tag\":\"" + MARKER + "\""))));
        assertNoEcho(() -> DocumentParser.parseRevocations(utf8(replaceOnce(validRevocations(),
                "\"sha512\":\"" + H7 + "\"", "\"sha512\":\"" + MARKER + "\""))));
    }

    @Test
    void trustFileWithAllZeroKeyIsRejected() throws Exception {
        String zero = "00".repeat(32);
        rejectsTrust(trustWithKeys(keyJson(Hex.kid(zero), zero, "[\"release\",\"revocation\"]")));
        assertNotNull(parseTrust(validTrust()));
    }
}
