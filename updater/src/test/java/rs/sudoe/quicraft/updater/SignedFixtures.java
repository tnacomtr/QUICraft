// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Set;

/**
 * Valid JSON fixtures and envelope builders for authentication tests. Document field sets are
 * copied from the valid examples in DocumentParserTest. Signing uses only the throwaway
 * {@link TestKeys}.
 */
final class SignedFixtures {

    static final String TS1 = "2026-11-01T12:00:00Z";
    static final String TS2 = "2026-12-01T12:00:00Z";
    static final String SHA512_A = "cd".repeat(64);
    static final String SHA512_B = "ab".repeat(64);
    static final String H7 = String.format("%0128x", 7);
    static final String H9 = String.format("%0128x", 9);
    /** Well-formed kid that is not in any test trust file. */
    static final String KID_UNKNOWN = "0123456789abcdef";
    static final String BAD_SIG = "01".repeat(64);

    private SignedFixtures() {
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Trust file: TEST 1 holds release only; TEST 2 holds release and revocation. */
    static String trustJson() {
        return "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":1,\"built\":\"" + TS1 + "\""
                + ",\"revocationsSeqFloor\":0,\"manifestSeqFloor\":0,\"keys\":["
                + "{\"kid\":\"" + TestKeys.KID_1 + "\",\"pub\":\"" + TestKeys.PUB_1
                + "\",\"roles\":[\"release\"]},"
                + "{\"kid\":\"" + TestKeys.KID_2 + "\",\"pub\":\"" + TestKeys.PUB_2
                + "\",\"roles\":[\"release\",\"revocation\"]}]}";
    }

    /** Snapshot built through the real trust parser from {@link #trustJson()}. */
    static TrustSnapshot standardSnapshot(Set<String> revokedPubs) throws Exception {
        return TrustSnapshot.of(DocumentParser.parseTrust(bytes(trustJson())), revokedPubs);
    }

    /** Valid release manifest, copied from the DocumentParserTest fixture. */
    static String releaseJson() {
        return "{\"type\":\"quicraft.release\",\"v\":1,\"seq\":42"
                + ",\"issued\":\"" + TS1 + "\",\"expires\":\"" + TS2 + "\""
                + ",\"minRevocationsSeq\":3,\"releases\":[{\"platform\":\"fabric-26x\""
                + ",\"version\":\"0.4.0\",\"targets\":[\"26.1\",\"26.1.1\"]"
                + ",\"file\":\"quicraft-fabric-26x-0.4.0.jar\",\"size\":4194304"
                + ",\"sha512\":\"" + SHA512_A + "\",\"tag\":\"v0.4.0\"}]"
                + ",\"withdrawn\":[{\"platform\":\"fabric-26x\",\"version\":\"0.3.9\""
                + ",\"sha512\":\"" + H9 + "\"}]}";
    }

    /** Valid revocations document, copied from the DocumentParserTest fixture. */
    static String revocationsJson() {
        return "{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":3"
                + ",\"issued\":\"" + TS1 + "\",\"expires\":\"2027-11-01T12:00:00Z\""
                + ",\"minManifestSeq\":0,\"revokedKeys\":[{\"kid\":\"dac073e0123bdea5\""
                + ",\"pub\":\"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025\"}]"
                + ",\"blockedArtifacts\":[{\"sha512\":\"" + H7 + "\"}]}";
    }

    /** A signature entry with the given kid and signature hex. */
    static String entry(String kid, String sigHex) {
        return "{\"kid\":\"" + kid + "\",\"sig\":\"" + sigHex + "\"}";
    }

    /** A signature entry whose signature is made by {@code seedHex} over {@code document}. */
    static String signedEntry(String kid, String seedHex, byte[] document)
            throws GeneralSecurityException {
        return entry(kid, TestKeys.sign(seedHex, document));
    }

    /** A complete {@code *.sig} envelope for the given payload hash and entries. */
    static byte[] envelope(String payloadSha256, String... entries) {
        return bytes("{\"type\":\"quicraft.sig\",\"v\":1,\"payloadSha256\":\"" + payloadSha256
                + "\",\"signatures\":[" + String.join(",", entries) + "]}");
    }

    /** A complete envelope for {@code document}, with the true payload hash. */
    static byte[] envelopeFor(byte[] document, String... entries) {
        return envelope(Hex.sha256(document), entries);
    }

    /** A snapshot that holds only a revocation role for TEST 2 (not reachable from the parser). */
    static TrustSnapshot revocationOnlySnapshot() {
        return new TrustSnapshot(1, "ab".repeat(32), Set.of(),
                Map.of(TestKeys.KID_2, TestKeys.PUB_2),
                Map.of(TestKeys.PUB_2, Set.of(KeyRole.REVOCATION)));
    }
}
