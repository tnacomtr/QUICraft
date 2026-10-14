// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateDocuments;
import rs.sudoe.quicraft.updater.UpdateVersion;

/**
 * {@code compose-release}: jar metadata, size and SHA-512 from a fixture jar, the byte-identical
 * trust-file rule, both lifetime limits, and the updater's own parser as the acceptance check
 * (docs/updater.md 12.2, 12.3, 12.5).
 */
class ComposeReleaseTest {

    @TempDir
    Path dir;

    private static String sha512(Path file) throws Exception {
        return ReleaseFixture.hex(MessageDigest.getInstance("SHA-512")
                .digest(Files.readAllBytes(file)));
    }

    @Test
    void composesAReleaseWhoseEntryMatchesTheFixtureJar() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path jar = ReleaseFixture.jar(dir, "quicraft-fabric-26x-0.1.0-alpha.1.jar",
                ReleaseFixture.BUILD_JSON, Files.readAllBytes(trust));
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "45",
                "--min-revocations-seq", "1",
                "--tag", "v0.1.0-alpha.1",
                "--entry", "fabric-26x=" + jar + "=26.1,26.1.1,26.3",
                "--withdrawn", "fabric-26x:0.0.9:" + ReleaseFixture.SHA512_B,
                "--trust", trust.toString());

        assertTrue(result.ok(), result.err());
        byte[] bytes = Files.readAllBytes(out);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains(" "), "the document must have no whitespace");
        assertNotEquals('\n', json.charAt(json.length() - 1));

        UpdateDocuments.ReleaseManifest manifest = DocumentParser.parseRelease(bytes);
        assertEquals(1, manifest.seq());
        assertEquals(1, manifest.minRevocationsSeq());
        assertEquals(Instant.parse("2026-12-16T12:00:00Z"), manifest.expires());
        assertEquals(1, manifest.releases().size());
        UpdateDocuments.ReleaseEntry entry = manifest.releases().get(0);
        assertEquals("fabric-26x", entry.platform());
        assertEquals(UpdateVersion.parse("0.1.0-alpha.1"), entry.version());
        assertEquals(java.util.List.of("26.1", "26.1.1", "26.3"), entry.targets());
        assertEquals("quicraft-fabric-26x-0.1.0-alpha.1.jar", entry.file());
        assertEquals(Files.size(jar), entry.size());
        assertEquals(sha512(jar), entry.sha512());
        assertEquals("v0.1.0-alpha.1", entry.tag());

        assertEquals(1, manifest.withdrawn().size());
        assertEquals("fabric-26x", manifest.withdrawn().get(0).platform());
        assertEquals(UpdateVersion.parse("0.0.9"), manifest.withdrawn().get(0).version());
        assertEquals(ReleaseFixture.SHA512_B, manifest.withdrawn().get(0).sha512());
    }

    @Test
    void defaultsIssuedToTheCurrentUtcSecondWhenOmitted() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path jar = ReleaseFixture.jar(dir, "quicraft-fabric-26x-0.1.0-alpha.1.jar",
                ReleaseFixture.BUILD_JSON, Files.readAllBytes(trust));
        Path out = dir.resolve("release.v1.json");
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "2",
                "--days", "30",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--entry", "fabric-26x=" + jar + "=26.1",
                "--trust", trust.toString());

        assertTrue(result.ok(), result.err());
        UpdateDocuments.ReleaseManifest manifest =
                DocumentParser.parseRelease(Files.readAllBytes(out));
        Instant after = Instant.now().plusSeconds(5);
        assertFalse(manifest.issued().isBefore(before), "issued must be the time of the run");
        assertFalse(manifest.issued().isAfter(after), "issued must be the time of the run");
        assertEquals(java.time.Duration.ofDays(30),
                java.time.Duration.between(manifest.issued(), manifest.expires()));
    }

    @Test
    void refusesAJarWhoseTrustEntryDiffersFromTheTrustFile() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path otherTrust = ReleaseFixture.write(dir, "other-trust.json",
                ReleaseFixture.utf8(ReleaseFixture.TRUST_JSON.replace("\"epoch\":1", "\"epoch\":2")));
        Path jar = ReleaseFixture.jar(dir, "quicraft-fabric-26x-0.1.0-alpha.1.jar",
                ReleaseFixture.BUILD_JSON, Files.readAllBytes(otherTrust));
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "30",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--entry", "fabric-26x=" + jar + "=26.1",
                "--trust", trust.toString());

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("not byte-identical"), result.err());
        assertFalse(Files.exists(out), "nothing may be published when the jar trust differs");
    }

    @Test
    void refusesAJarBuiltForAnotherPlatform() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path jar = ReleaseFixture.jar(dir, "quicraft-velocity-0.1.0-alpha.1.jar",
                ReleaseFixture.BUILD_JSON.replace("fabric-26x", "velocity"),
                Files.readAllBytes(trust));
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "30",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--entry", "fabric-26x=" + jar + "=26.1",
                "--trust", trust.toString());

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("built for platform"), result.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void rejectsAManifestLifetimeBeyondTheLimit() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "46",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--trust", trust.toString());

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("--days"), result.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void refusesAJarNameOutsideTheSchema() throws Exception {
        Path trust = ReleaseFixture.trustFile(dir, "updater-trust.json");
        Path jar = ReleaseFixture.jar(dir, "quicraft alpha.jar", ReleaseFixture.BUILD_JSON,
                Files.readAllBytes(trust));
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "30",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--entry", "fabric-26x=" + jar + "=26.1",
                "--trust", trust.toString());

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("jar file name"), result.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void refusesATrustFileTheUpdaterRejects() throws Exception {
        Path trust = ReleaseFixture.write(dir, "broken-trust.json",
                ReleaseFixture.utf8("{\"type\":\"quicraft.trust\",\"v\":1}"));
        Path out = dir.resolve("release.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-release",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "30",
                "--min-revocations-seq", "0",
                "--tag", "v0.1.0-alpha.1",
                "--trust", trust.toString());

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("trust file"), result.err());
        assertFalse(Files.exists(out));
    }
}
