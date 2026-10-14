// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateRejectedException;

/**
 * {@code compose-revocations}: writes {@code revocations.v1.json} as exact compact bytes
 * (docs/updater.md 12.3) and re-parses the file on disk with the updater's own parser.
 *
 * <p>The document is unsigned here. The owner signs these exact bytes by hand with OpenSSL and
 * {@link AssembleSig} then builds the {@code .sig} file. The tool never sees a private key.
 */
final class ComposeRevocations {

    static final Set<String> OPTIONS = Set.of(
            "--out", "--seq", "--issued", "--days", "--min-manifest-seq", "--revoke-key", "--block");

    static final Set<String> REPEATABLE = Set.of("--revoke-key", "--block");

    private ComposeRevocations() {
    }

    static int run(Cli cli, PrintStream out) throws ToolException {
        Path outFile = cli.requirePath("--out");
        long seq = cli.requireLong("--seq", 0, Limits.MAX_SEQ);
        Instant issued = cli.requireTimestamp("--issued");
        long days = cli.requireLong("--days", 1, Limits.MAX_REVOCATION_DAYS);
        long minManifestSeq = cli.requireLong("--min-manifest-seq", 0, Limits.MAX_SEQ);
        List<Key> revokedKeys = revokedKeys(cli.list("--revoke-key"));
        List<String> blockedArtifacts = blockedArtifacts(cli.list("--block"));
        Instant expires = Timestamps.plusDays(issued, days);

        StringBuilder json = new StringBuilder(256);
        json.append("{\"type\":\"quicraft.revocations\",\"v\":1")
                .append(",\"seq\":").append(seq)
                .append(",\"issued\":\"").append(Timestamps.format(issued)).append('"')
                .append(",\"expires\":\"").append(Timestamps.format(expires)).append('"')
                .append(",\"minManifestSeq\":").append(minManifestSeq)
                .append(",\"revokedKeys\":[");
        for (int i = 0; i < revokedKeys.size(); i++) {
            Key key = revokedKeys.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"kid\":").append(Json.quote(key.kid()))
                    .append(",\"pub\":").append(Json.quote(key.pub())).append('}');
        }
        json.append("],\"blockedArtifacts\":[");
        for (int i = 0; i < blockedArtifacts.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"sha512\":").append(Json.quote(blockedArtifacts.get(i))).append('}');
        }
        json.append("]}");

        byte[] bytes = ToolFiles.utf8(json.toString());
        ToolFiles.write(outFile, bytes);
        // Parse what is on disk, not the array in memory: the file the owner signs is the file the
        // updater must accept, including its exact byte length (no trailing newline).
        try {
            DocumentParser.parseRevocations(Files.readAllBytes(outFile));
        } catch (UpdateRejectedException e) {
            throw new ToolException(
                    "the updater's parser rejected the composed revocations file: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ToolException("cannot read back " + outFile + ": " + e.getMessage(), e);
        }

        out.println("wrote " + outFile + " (" + bytes.length + " bytes, seq " + seq
                + ", " + revokedKeys.size() + " revoked keys, " + blockedArtifacts.size()
                + " blocked artifacts)");
        return ReleaseTool.EXIT_OK;
    }

    private record Key(String kid, String pub) {
    }

    private static List<Key> revokedKeys(List<String> specs) throws ToolException {
        List<Key> keys = new ArrayList<>(specs.size());
        Set<String> kids = new HashSet<>();
        Set<String> pubs = new HashSet<>();
        for (String spec : specs) {
            int colon = spec.indexOf(':');
            if (colon <= 0 || colon == spec.length() - 1) {
                throw new ToolException("--revoke-key must be kid:pub: " + spec);
            }
            String kid = spec.substring(0, colon);
            String pub = spec.substring(colon + 1);
            if (!Hexes.isLowerHex(kid, 16)) {
                throw new ToolException("--revoke-key needs a 16-character lowercase hex kid: " + spec);
            }
            Hexes.decode(pub, 32);
            String derived = Hexes.kid(pub);
            if (!derived.equals(kid)) {
                throw new ToolException("--revoke-key kid does not match pub: expected " + derived);
            }
            if (!kids.add(kid) || !pubs.add(pub)) {
                throw new ToolException("--revoke-key repeats a key: " + kid);
            }
            keys.add(new Key(kid, pub));
        }
        return keys;
    }

    private static List<String> blockedArtifacts(List<String> specs) throws ToolException {
        List<String> hashes = new ArrayList<>(specs.size());
        Set<String> seen = new HashSet<>();
        for (String sha512 : specs) {
            Hexes.decode(sha512, 64);
            if (!seen.add(sha512)) {
                throw new ToolException("--block repeats a hash: " + sha512);
            }
            hashes.add(sha512);
        }
        return hashes;
    }
}
