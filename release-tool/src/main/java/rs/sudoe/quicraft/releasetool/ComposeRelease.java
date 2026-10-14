// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import rs.sudoe.quicraft.updater.BuildInfo;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
import rs.sudoe.quicraft.updater.UpdateVersion;

/**
 * {@code compose-release}: writes {@code release.v1.json} as exact compact bytes
 * (docs/updater.md 12.3) and re-parses the file on disk with the updater's own parser.
 *
 * <p>For every {@code --entry} the jar is read as data only: its
 * {@code META-INF/quicraft/build.json} supplies the version and platform, and its
 * {@code META-INF/quicraft/updater-trust.json} must be byte-identical to the owner's trust file
 * (docs/updater.md 12.2, "Validating a staged jar's trust file"; 12.7 step 2). Size and SHA-512 come
 * from the jar bytes. The tool never touches a private key.
 */
final class ComposeRelease {

    static final Set<String> OPTIONS = Set.of(
            "--out", "--seq", "--issued", "--days", "--min-revocations-seq", "--tag", "--entry",
            "--withdrawn", "--trust");

    static final Set<String> REPEATABLE = Set.of("--entry", "--withdrawn");

    /** The metadata entries of the project's own jars. */
    private static final String BUILD_ENTRY = "META-INF/quicraft/build.json";

    private static final String TRUST_ENTRY = "META-INF/quicraft/updater-trust.json";

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,124}\\.jar");

    private static final Pattern TAG = Pattern.compile("v[0-9A-Za-z.-]{1,40}");

    private static final int MAX_RELEASES = 16;

    private static final int MAX_WITHDRAWN = 64;

    private static final int MAX_TARGETS = 64;

    private static final int MAX_TARGET_CHARS = 32;

    private static final int READ_BUFFER_BYTES = 8192;

    private ComposeRelease() {
    }

    static int run(Cli cli, PrintStream out) throws ToolException {
        Path outFile = cli.requirePath("--out");
        long seq = cli.requireLong("--seq", 0, Limits.MAX_SEQ);
        Instant issued = cli.optionalTimestamp("--issued", Timestamps.now());
        long days = cli.requireLong("--days", 1, Limits.MAX_RELEASE_DAYS);
        long minRevocationsSeq = cli.requireLong("--min-revocations-seq", 0, Limits.MAX_SEQ);
        String tag = cli.require("--tag");
        if (!TAG.matcher(tag).matches()) {
            throw new ToolException("--tag must match " + TAG.pattern() + ": " + tag);
        }
        Path trustPath = cli.optionalPath("--trust", TrustFiles.defaultPath());
        byte[] trustBytes = TrustFiles.readBytes(trustPath);
        TrustFiles.parse(trustBytes);

        List<Release> releases = releases(cli.list("--entry"), trustBytes);
        List<Withdrawal> withdrawn = withdrawals(cli.list("--withdrawn"));
        Instant expires = Timestamps.plusDays(issued, days);

        StringBuilder json = new StringBuilder(512);
        json.append("{\"type\":\"quicraft.release\",\"v\":1")
                .append(",\"seq\":").append(seq)
                .append(",\"issued\":\"").append(Timestamps.format(issued)).append('"')
                .append(",\"expires\":\"").append(Timestamps.format(expires)).append('"')
                .append(",\"minRevocationsSeq\":").append(minRevocationsSeq)
                .append(",\"releases\":[");
        for (int i = 0; i < releases.size(); i++) {
            Release release = releases.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"platform\":").append(Json.quote(release.platform()))
                    .append(",\"version\":").append(Json.quote(release.version()))
                    .append(",\"targets\":[");
            for (int t = 0; t < release.targets().size(); t++) {
                if (t > 0) {
                    json.append(',');
                }
                json.append(Json.quote(release.targets().get(t)));
            }
            json.append("],\"file\":").append(Json.quote(release.file()))
                    .append(",\"size\":").append(release.size())
                    .append(",\"sha512\":").append(Json.quote(release.sha512()))
                    .append(",\"tag\":").append(Json.quote(tag)).append('}');
        }
        json.append("],\"withdrawn\":[");
        for (int i = 0; i < withdrawn.size(); i++) {
            Withdrawal entry = withdrawn.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"platform\":").append(Json.quote(entry.platform()))
                    .append(",\"version\":").append(Json.quote(entry.version().toString()))
                    .append(",\"sha512\":").append(Json.quote(entry.sha512())).append('}');
        }
        json.append("]}");

        byte[] bytes = ToolFiles.utf8(json.toString());
        ToolFiles.write(outFile, bytes);
        // Parse what is on disk: that exact byte sequence is what the owner signs.
        try {
            DocumentParser.parseRelease(Files.readAllBytes(outFile));
        } catch (UpdateRejectedException e) {
            throw new ToolException(
                    "the updater's parser rejected the composed release file: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ToolException("cannot read back " + outFile + ": " + e.getMessage(), e);
        }

        out.println("wrote " + outFile + " (" + bytes.length + " bytes, seq " + seq + ", "
                + releases.size() + " releases, " + withdrawn.size() + " withdrawn)");
        return ReleaseTool.EXIT_OK;
    }

    private record Release(
            String platform, String version, List<String> targets, String file, long size,
            String sha512) {
    }

    private record Withdrawal(String platform, UpdateVersion version, String sha512) {
    }

    private static List<Release> releases(List<String> specs, byte[] trustBytes)
            throws ToolException {
        if (specs.size() > MAX_RELEASES) {
            throw new ToolException("at most " + MAX_RELEASES + " --entry options are allowed");
        }
        List<Release> releases = new ArrayList<>(specs.size());
        Set<String> platforms = new HashSet<>();
        for (String spec : specs) {
            int first = spec.indexOf('=');
            int second = first < 0 ? -1 : spec.indexOf('=', first + 1);
            if (first <= 0 || second < 0 || second == spec.length() - 1) {
                throw new ToolException(
                        "--entry must be PLATFORM=JARPATH=TARGET1,TARGET2,...: " + spec);
            }
            String platform = Platforms.require(spec.substring(0, first));
            Path jar = Path.of(spec.substring(first + 1, second));
            List<String> targets = targets(spec.substring(second + 1));
            if (!platforms.add(platform)) {
                throw new ToolException("--entry repeats platform " + platform);
            }
            releases.add(readRelease(platform, jar, targets, trustBytes));
        }
        return releases;
    }

    /** Reads one jar as data: build metadata, trust entry, size and SHA-512. */
    private static Release readRelease(String platform, Path jar, List<String> targets,
            byte[] trustBytes) throws ToolException {
        if (!Files.isRegularFile(jar)) {
            throw new ToolException("--entry jar is not a regular file: " + jar);
        }
        String fileName = jar.getFileName() == null ? null : jar.getFileName().toString();
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new ToolException("jar file name must match " + FILE_NAME.pattern() + ": " + jar);
        }
        long size;
        try {
            size = Files.size(jar);
        } catch (IOException e) {
            throw new ToolException("cannot size " + jar + ": " + e.getMessage(), e);
        }
        if (size < 1 || size > Limits.MAX_JAR_BYTES) {
            throw new ToolException("jar size must be 1.." + Limits.MAX_JAR_BYTES + ": " + size);
        }

        byte[] buildBytes;
        byte[] jarTrustBytes;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            buildBytes = metadata(zip, BUILD_ENTRY, Limits.BUILD_MAX_BYTES);
            jarTrustBytes = metadata(zip, TRUST_ENTRY, Limits.TRUST_MAX_BYTES);
        } catch (IOException e) {
            throw new ToolException("cannot read " + jar + ": " + e.getMessage(), e);
        }

        BuildInfo build;
        try {
            build = DocumentParser.parseBuild(buildBytes);
        } catch (UpdateRejectedException e) {
            throw new ToolException(jar + " has invalid " + BUILD_ENTRY + ": " + e.getMessage(), e);
        }
        if (!build.platform().equals(platform)) {
            throw new ToolException(jar + " is built for platform '" + build.platform()
                    + "', but --entry says '" + platform + "'");
        }
        if (!Arrays.equals(jarTrustBytes, trustBytes)) {
            throw new ToolException(jar + " carries a " + TRUST_ENTRY
                    + " that is not byte-identical to the trust file given by --trust");
        }
        return new Release(platform, build.version().toString(), targets, fileName, size,
                sha512(jar));
    }

    /** The single metadata entry of that exact name, read as data with the entry size cap. */
    private static byte[] metadata(ZipFile zip, String name, int cap)
            throws ToolException, IOException {
        long matches = zip.stream().filter(entry -> entry.getName().equals(name)).count();
        if (matches != 1) {
            throw new ToolException(
                    "the jar must hold exactly one " + name + ", found " + matches);
        }
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new ToolException("the jar must hold exactly one readable " + name);
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes(cap + 1);
        }
        if (bytes.length > cap) {
            throw new ToolException(name + " exceeds the " + cap + " byte limit");
        }
        return bytes;
    }

    private static List<String> targets(String text) throws ToolException {
        if (text.isEmpty()) {
            throw new ToolException("--entry needs at least one target");
        }
        List<String> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String target : text.split(",", -1)) {
            if (target.isEmpty() || target.length() > MAX_TARGET_CHARS || !seen.add(target)) {
                throw new ToolException("invalid or repeated target '" + target
                        + "': each must be non-empty, unique and at most " + MAX_TARGET_CHARS
                        + " characters");
            }
            targets.add(target);
        }
        if (targets.size() > MAX_TARGETS) {
            throw new ToolException("at most " + MAX_TARGETS + " targets per release entry");
        }
        return targets;
    }

    private static List<Withdrawal> withdrawals(List<String> specs) throws ToolException {
        if (specs.size() > MAX_WITHDRAWN) {
            throw new ToolException("at most " + MAX_WITHDRAWN + " --withdrawn options are allowed");
        }
        List<Withdrawal> withdrawn = new ArrayList<>(specs.size());
        Set<String> platformVersions = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (String spec : specs) {
            int first = spec.indexOf(':');
            int second = first < 0 ? -1 : spec.indexOf(':', first + 1);
            if (first <= 0 || second < 0 || second == spec.length() - 1) {
                throw new ToolException("--withdrawn must be PLATFORM:VERSION:SHA512: " + spec);
            }
            String platform = Platforms.require(spec.substring(0, first));
            String versionText = spec.substring(first + 1, second);
            UpdateVersion version;
            try {
                version = UpdateVersion.parse(versionText);
            } catch (UpdateRejectedException e) {
                throw new ToolException("--withdrawn version rejected by the updater: "
                        + e.getMessage(), e);
            }
            String sha512 = spec.substring(second + 1);
            Hexes.decode(sha512, 64);
            if (!platformVersions.add(platform + "\u0000" + version)
                    || !hashes.add(sha512)) {
                throw new ToolException("--withdrawn repeats a platform/version or a hash: " + spec);
            }
            withdrawn.add(new Withdrawal(platform, version, sha512));
        }
        return withdrawn;
    }

    private static String sha512(Path file) throws ToolException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new ToolException("cannot hash " + file + ": " + e.getMessage(), e);
        }
        return Hexes.encode(digest.digest());
    }
}
