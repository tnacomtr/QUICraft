// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateDocuments;

/**
 * {@code compose-revocations}: exact compact bytes, schema field order, and the updater's own
 * parser as the acceptance check (docs/updater.md 12.3, 12.5).
 */
class ComposeRevocationsTest {

    @TempDir
    Path dir;

    @Test
    void composesTheExactCompactBytesAndTheUpdaterParsesThem() throws Exception {
        Path out = dir.resolve("revocations.v1.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "3",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "1",
                "--min-manifest-seq", "42",
                "--revoke-key", ReleaseFixture.KID_REVOKED + ":" + ReleaseFixture.PUB_REVOKED,
                "--block", ReleaseFixture.SHA512_A);

        assertTrue(result.ok(), result.err());
        byte[] bytes = Files.readAllBytes(out);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":3"
                + ",\"issued\":\"" + ReleaseFixture.ISSUED + "\""
                + ",\"expires\":\"" + ReleaseFixture.NEXT_DAY + "\""
                + ",\"minManifestSeq\":42"
                + ",\"revokedKeys\":[{\"kid\":\"" + ReleaseFixture.KID_REVOKED
                + "\",\"pub\":\"" + ReleaseFixture.PUB_REVOKED + "\"}]"
                + ",\"blockedArtifacts\":[{\"sha512\":\"" + ReleaseFixture.SHA512_A + "\"}]}",
                json);
        // Compact and with no trailing newline: the exact bytes the owner signs.
        assertFalse(json.contains(" "), "the document must have no whitespace");
        assertNotEquals('\n', json.charAt(json.length() - 1));

        UpdateDocuments.Revocations parsed = DocumentParser.parseRevocations(bytes);
        assertEquals(3, parsed.seq());
        assertEquals(42, parsed.minManifestSeq());
        assertEquals(ReleaseFixture.KID_REVOKED, parsed.revokedKeys().get(0).kid());
        assertEquals(ReleaseFixture.PUB_REVOKED, parsed.revokedKeys().get(0).pub());
        assertEquals(java.util.List.of(ReleaseFixture.SHA512_A), parsed.blockedArtifacts());
    }

    @Test
    void acceptsAnEmptyRevocationsDocumentWithTheLifetimeLimit() throws Exception {
        Path out = dir.resolve("empty.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "400",
                "--min-manifest-seq", "0");

        assertTrue(result.ok(), result.err());
        UpdateDocuments.Revocations parsed =
                DocumentParser.parseRevocations(Files.readAllBytes(out));
        assertTrue(parsed.revokedKeys().isEmpty());
        assertTrue(parsed.blockedArtifacts().isEmpty());
        assertEquals(Duration.ofDays(400),
                Duration.between(parsed.issued(), parsed.expires()));
    }

    @Test
    void rejectsALifetimeBeyondTheRevocationsLimit() throws Exception {
        Path out = dir.resolve("too-long.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "401",
                "--min-manifest-seq", "0");

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("--days"), result.err());
        assertFalse(Files.exists(out), "nothing may be written when the arguments are rejected");
    }

    @Test
    void rejectsAKeyIdThatDoesNotMatchItsPublicKey() throws Exception {
        Path out = dir.resolve("bad-kid.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "1",
                "--min-manifest-seq", "0",
                "--revoke-key", ReleaseFixture.KID_1 + ":" + ReleaseFixture.PUB_REVOKED);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("does not match"), result.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void rejectsATimestampOutsideTheSchemaForm() throws Exception {
        Path out = dir.resolve("bad-time.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", "2026-11-01T12:00:00+00:00",
                "--days", "1",
                "--min-manifest-seq", "0");

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("YYYY-MM-DDTHH:MM:SSZ"), result.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void rejectsARepeatedBlockedArtifact() throws Exception {
        Path out = dir.resolve("dupes.json");

        ReleaseFixture.Result result = ReleaseFixture.run("compose-revocations",
                "--out", out.toString(),
                "--seq", "1",
                "--issued", ReleaseFixture.ISSUED,
                "--days", "1",
                "--min-manifest-seq", "0",
                "--block", ReleaseFixture.SHA512_A,
                "--block", ReleaseFixture.SHA512_A);

        assertNotEquals(0, result.code());
        assertTrue(result.err().contains("repeats"), result.err());
        assertFalse(Files.exists(out));
    }
}
