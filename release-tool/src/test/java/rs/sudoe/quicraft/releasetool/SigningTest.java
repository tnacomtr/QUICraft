// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateDocuments;

/**
 * {@code assemble-sig}, {@code verify} and {@code payload-info}: the signature envelope accepts raw
 * and hex signatures, and verification fails for every pair the updater must reject
 * (docs/updater.md 12.3, 12.12).
 */
class SigningTest {

    @TempDir
    Path dir;

    /** A release payload composed by the tool, plus the trust file it was composed against. */
    private record Pair(Path trust, Path payload, byte[] payloadBytes) {
    }

    private Pair releasePair() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path out = dir.resolve("release.v1.json");
        ReleaseFixture.Result composed = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "45",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--trust", trust.toString());
        assertTrue(composed.ok(), composed.err());
        return new Pair(trust, out, Files.readAllBytes(out));
    }

    private Path revocations() throws Exception {
        Path out = dir.resolve("revocations.v1.json");
        ReleaseFixture.Result composed = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "400",
                "--min-manifest-seq", "0");
        assertTrue(composed.ok(), composed.err());
        return out;
    }

    private Path writeSig(String name, String payloadSha256, String... entries)
            throws Exception {
        return ReleaseFixture.write(dir, name,
                ReleaseFixture.signatureEnvelope(payloadSha256, entries));
    }

    private String sha256(byte[] bytes) {
        return Hexes.sha256Hex(bytes);
    }

    @Test
    void assembleSigAcceptsRawAndHexSignaturesAndVerifiesBoth() throws Exception {
        Pair pair = releasePair();
        byte[] raw = ReleaseFixture.sign(ReleaseFixture.SEED_1, pair.payloadBytes());
        Path rawFile = ReleaseFixture.write(dir, "primary.sigbin", raw);
        Path hexFile = ReleaseFixture.write(dir, "backup.sighex",
                ReleaseFixture.utf8(ReleaseFixture.hex(
                        ReleaseFixture.sign(ReleaseFixture.SEED_2, pair.payloadBytes())) + "\n"));
        Path sigOut = dir.resolve("release.v1.json.sig");

        ReleaseFixture.Result result = ReleaseFixture.run("assemble-sig",
                "--payload", pair.payload().toString(),
                "--sig", ReleaseFixture.KID_1 + "=" + rawFile,
                "--sig", ReleaseFixture.KID_2 + "=" + hexFile,
                "--out", sigOut.toString(),
                "--trust", pair.trust().toString(),
                "--now", ReleaseFixture.ISSUED);

        assertTrue(result.ok(), result.err());
        assertEquals(64, raw.length);

        UpdateDocuments.SignatureEnvelope envelope =
                DocumentParser.parseSignatures(Files.readAllBytes(sigOut));
        assertEquals(sha256(pair.payloadBytes()), envelope.payloadSha256());
        assertEquals(List.of(ReleaseFixture.KID_1, ReleaseFixture.KID_2),
                envelope.signatures().stream().map(UpdateDocuments.SignatureEntry::kid).toList());

        // The same pair verifies through the standalone command as well.
        ReleaseFixture.Result verified = ReleaseFixture.run("verify",
                "--trust", pair.trust().toString(),
                "--payload", pair.payload().toString(),
                "--sig", sigOut.toString(),
                "--now", ReleaseFixture.ISSUED);
        assertTrue(verified.ok(), verified.err());
        assertTrue(verified.out().contains("verified by kid " + ReleaseFixture.KID_1),
                verified.out());
    }

    @Test
    void assembleSigWritesTheFileButFailsWhenTheSignatureIsWrong() throws Exception {
        Pair pair = releasePair();
        byte[] wrong = ReleaseFixture.sign(ReleaseFixture.SEED_1,
                ReleaseFixture.utf8("another document"));
        Path wrongFile = ReleaseFixture.write(dir, "wrong.sigbin", wrong);
        Path sigOut = dir.resolve("release.v1.json.sig");

        ReleaseFixture.Result result = ReleaseFixture.run("assemble-sig",
                "--payload", pair.payload().toString(),
                "--sig", ReleaseFixture.KID_1 + "=" + wrongFile,
                "--out", sigOut.toString(),
                "--trust", pair.trust().toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(Files.exists(sigOut), "the written file is kept for inspection");
        assertTrue(result.err().contains("does not verify"), result.err());
    }

    @Test
    void verifyRejectsAFlippedPayloadByte() throws Exception {
        Pair pair = releasePair();
        Path sig = writeSig("release.v1.json.sig", sha256(pair.payloadBytes()),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_1, ReleaseFixture.SEED_1,
                        pair.payloadBytes()));
        // One flipped character: still a valid document, so only the signature pair can fail.
        String tamperedText = ReleaseFixture.text(pair.payload())
                .replace("\"minRevocationsSeq\":0", "\"minRevocationsSeq\":1");
        Path tampered = ReleaseFixture.write(dir, "tampered.json",
                ReleaseFixture.utf8(tamperedText));
        assertFalse(java.util.Arrays.equals(pair.payloadBytes(), Files.readAllBytes(tampered)),
                "the tampered payload must differ from the composed one");
        DocumentParser.parseRelease(Files.readAllBytes(tampered));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", pair.trust().toString(),
                "--payload", tampered.toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("payloadSha256"), result.err());
    }

    @Test
    void verifyRejectsAPayloadSha256Mismatch() throws Exception {
        Pair pair = releasePair();
        Path sig = writeSig("torn.sig", "00".repeat(32),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_1, ReleaseFixture.SEED_1,
                        pair.payloadBytes()));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", pair.trust().toString(),
                "--payload", pair.payload().toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("torn"), result.err());
    }

    @Test
    void verifyRejectsAReleaseOnlyKeySigningRevocations() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path payload = revocations();
        byte[] payloadBytes = Files.readAllBytes(payload);
        Path sig = writeSig("revocations.v1.json.sig", sha256(payloadBytes),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_1, ReleaseFixture.SEED_1,
                        payloadBytes));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", trust.toString(),
                "--payload", payload.toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("revocation role"), result.err());
    }

    @Test
    void verifyAcceptsTheBackupKeySigningRevocations() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path payload = revocations();
        byte[] payloadBytes = Files.readAllBytes(payload);
        Path sig = writeSig("revocations.v1.json.sig", sha256(payloadBytes),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_2, ReleaseFixture.SEED_2,
                        payloadBytes));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", trust.toString(),
                "--payload", payload.toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertTrue(result.ok(), result.err());
        assertTrue(result.out().contains("verified by kid " + ReleaseFixture.KID_2), result.out());
    }

    @Test
    void verifyRejectsAnUnknownKeyId() throws Exception {
        Pair pair = releasePair();
        Path sig = writeSig("unknown.sig", sha256(pair.payloadBytes()),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_UNKNOWN, ReleaseFixture.SEED_1,
                        pair.payloadBytes()));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", pair.trust().toString(),
                "--payload", pair.payload().toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("no signature verified"), result.err());
    }

    @Test
    void verifyRejectsAnExpiredDocument() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path out = dir.resolve("expired.json");
        assertTrue(ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", "2026-01-01T00:00:00Z",
                "--days", "1",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--trust", trust.toString()).ok());
        byte[] payloadBytes = Files.readAllBytes(out);
        Path sig = writeSig("expired.json.sig", sha256(payloadBytes),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_1, ReleaseFixture.SEED_1,
                        payloadBytes));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", trust.toString(),
                "--payload", out.toString(),
                "--sig", sig.toString(),
                "--now", "2026-06-01T00:00:00Z");

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("expired"), result.err());
    }

    @Test
    void verifyRejectsATrustFileThatDoesNotParse() throws Exception {
        Pair pair = releasePair();
        Path sig = writeSig("release.v1.json.sig", sha256(pair.payloadBytes()),
                ReleaseFixture.signatureEntry(ReleaseFixture.KID_1, ReleaseFixture.SEED_1,
                        pair.payloadBytes()));
        Path broken = ReleaseFixture.write(dir, "broken.json", ReleaseFixture.utf8("{}"));

        ReleaseFixture.Result result = ReleaseFixture.run("verify",
                "--trust", broken.toString(),
                "--payload", pair.payload().toString(),
                "--sig", sig.toString(),
                "--now", ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertFalse(result.err().isBlank());
    }

    @Test
    void payloadInfoPrintsSizeHashAndTheOpensslCommandPerKey() throws Exception {
        Pair pair = releasePair();

        ReleaseFixture.Result result = ReleaseFixture.run("payload-info",
                "--payload", pair.payload().toString(),
                "--trust", pair.trust().toString());

        assertTrue(result.ok(), result.err());
        String out = result.out();
        assertTrue(out.contains("size: " + pair.payloadBytes().length), out);
        assertTrue(out.contains("sha256: " + sha256(pair.payloadBytes())), out);
        assertTrue(out.contains("openssl pkeyutl -sign -inkey <private-key.pem> -rawin -in "
                + "release.v1.json -out release.v1.json." + ReleaseFixture.KID_1 + ".sigbin"), out);
        assertTrue(out.contains("release.v1.json." + ReleaseFixture.KID_2 + ".sigbin"), out);
    }

    @Test
    void verifyHelpAndUnknownCommandsReportUsage() {
        assertTrue(ReleaseFixture.run("help").ok());
        ReleaseFixture.Result unknown = ReleaseFixture.run("sign-everything");
        assertNotEquals(0, unknown.code());
        assertTrue(unknown.err().contains("unknown command"), unknown.err());
    }
}
