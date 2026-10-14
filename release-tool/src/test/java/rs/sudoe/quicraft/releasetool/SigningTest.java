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
 * and hex signatures, verification fails for every pair the updater must reject, including a
 * document whose lifetime breaks the updater's own rule (docs/updater.md 12.3, 12.5, 12.12), and
 * the OpenSSL command {@code payload-info} prints quotes its file names for the shell.
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

    /**
     * A release manifest the tool composed with a one-day lifetime and then rewrote to
     * {@code expires}. The updater's parser accepts the result on purpose: it does not enforce
     * lifetimes (docs/updater.md 12.5), so only {@code verify}'s own lifetime rule can reject it.
     */
    private Pair releaseWithExpires(String name, String expires) throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path out = dir.resolve(name);
        ReleaseFixture.Result composed = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "1",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--trust", trust.toString());
        assertTrue(composed.ok(), composed.err());
        return forge(trust, out, expires, true);
    }

    /** The same, for a revocations document. */
    private Pair revocationsWithExpires(String name, String expires) throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path out = dir.resolve(name);
        ReleaseFixture.Result composed = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "1",
                "--min-manifest-seq", "0");
        assertTrue(composed.ok(), composed.err());
        return forge(trust, out, expires, false);
    }

    /** Rewrites {@code expires}, checks the updater's parser still accepts the file, returns it. */
    private Pair forge(Path trust, Path out, String expires, boolean release) throws Exception {
        String text = ReleaseFixture.text(out).replace(ReleaseFixture.NEXT_DAY, expires);
        Path forged = ReleaseFixture.write(dir, out.getFileName().toString(),
                ReleaseFixture.utf8(text));
        byte[] bytes = Files.readAllBytes(forged);
        if (release) {
            DocumentParser.parseRelease(bytes);
        } else {
            DocumentParser.parseRevocations(bytes);
        }
        return new Pair(trust, forged, bytes);
    }

    /** Signs the pair's exact bytes with that seed and runs {@code verify} at {@code now}. */
    private ReleaseFixture.Result verifySigned(Pair pair, String kid, String seed, String now)
            throws Exception {
        Path sig = writeSig(pair.payload().getFileName() + ".sig", sha256(pair.payloadBytes()),
                ReleaseFixture.signatureEntry(kid, seed, pair.payloadBytes()));
        return ReleaseFixture.run("verify",
                "--trust", pair.trust().toString(),
                "--payload", pair.payload().toString(),
                "--sig", sig.toString(),
                "--now", now);
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
    void verifyRejectsAManifestLifetimeBeyondTheUpdaterLimit() throws Exception {
        // 75 days of lifetime, unexpired at --now: only the 45-day rule can reject this document.
        Pair pair = releaseWithExpires("long-lifetime.json", "2027-01-15T12:00:00Z");

        ReleaseFixture.Result result = verifySigned(pair, ReleaseFixture.KID_1,
                ReleaseFixture.SEED_1, ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("invalid lifetime"), result.err());
        assertTrue(result.err().contains("45"), result.err());
    }

    @Test
    void verifyRejectsAManifestWithNonPositiveLifetime() throws Exception {
        // expires == issued: the updater requires issued < expires (docs/updater.md 12.5).
        Pair pair = releaseWithExpires("zero-lifetime.json", ReleaseFixture.ISSUED);

        ReleaseFixture.Result result = verifySigned(pair, ReleaseFixture.KID_1,
                ReleaseFixture.SEED_1, ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("invalid lifetime"), result.err());
    }

    @Test
    void verifyRejectsARevocationsLifetimeBeyondTheUpdaterLimit() throws Exception {
        // 486 days of lifetime, unexpired at --now: beyond the 400-day revocations limit.
        Pair pair = revocationsWithExpires("long-revocations.json", "2028-03-01T12:00:00Z");

        ReleaseFixture.Result result = verifySigned(pair, ReleaseFixture.KID_2,
                ReleaseFixture.SEED_2, ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("invalid lifetime"), result.err());
        assertTrue(result.err().contains("400"), result.err());
    }

    @Test
    void verifyRejectsARevocationsWithNonPositiveLifetime() throws Exception {
        // expires before issued.
        Pair pair = revocationsWithExpires("early-revocations.json", "2026-10-31T12:00:00Z");

        ReleaseFixture.Result result = verifySigned(pair, ReleaseFixture.KID_2,
                ReleaseFixture.SEED_2, ReleaseFixture.ISSUED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("invalid lifetime"), result.err());
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
        assertTrue(out.contains(
                "openssl pkeyutl -sign -inkey <private-key.pem> -rawin -in 'release.v1.json'"
                        + " -out 'release.v1.json." + ReleaseFixture.KID_1 + ".sigbin'"),
                out);
        assertTrue(out.contains("'release.v1.json." + ReleaseFixture.KID_2 + ".sigbin'"), out);
    }

    @Test
    void payloadInfoQuotesFileNamesWithSpacesAndShellMetacharacters() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        String name = "release one$(x);two.json";
        Path payload = ReleaseFixture.write(dir, name, ReleaseFixture.utf8("{\"payload\":1}"));

        ReleaseFixture.Result result = ReleaseFixture.run("payload-info",
                "--payload", payload.toString(),
                "--trust", trust.toString());

        assertTrue(result.ok(), result.err());
        String out = result.out();
        // Both the input and the output name are one quoted word, so the space, the $(x) and the
        // semicolon reach OpenSSL untouched.
        assertTrue(out.contains("-in '" + name + "' -out '" + name + "."
                + ReleaseFixture.KID_1 + ".sigbin'"), out);
        assertTrue(out.contains("'" + name + "." + ReleaseFixture.KID_2 + ".sigbin'"), out);
        assertFalse(out.contains("-in " + name + " "), "the name may not be printed unquoted: " + out);
        assertFalse(out.contains(" -out " + name + "."), "the name may not be printed unquoted: " + out);
    }

    @Test
    void payloadInfoEscapesAnEmbeddedSingleQuote() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path payload = ReleaseFixture.write(dir, "it's.json", ReleaseFixture.utf8("{\"payload\":1}"));

        ReleaseFixture.Result result = ReleaseFixture.run("payload-info",
                "--payload", payload.toString(),
                "--trust", trust.toString());

        assertTrue(result.ok(), result.err());
        assertTrue(result.out().contains("-in 'it'\\''s.json' -out 'it'\\''s.json."
                + ReleaseFixture.KID_1 + ".sigbin'"), result.out());
    }

    @Test
    void verifyHelpAndUnknownCommandsReportUsage() {
        assertTrue(ReleaseFixture.run("help").ok());
        ReleaseFixture.Result unknown = ReleaseFixture.run("sign-everything");
        assertNotEquals(0, unknown.code());
        assertTrue(unknown.err().contains("unknown command"), unknown.err());
    }
}
