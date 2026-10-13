// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Verifies a staged jar against its accepted release entry and validates the trust file it carries
 * (docs/updater.md 12.2, "Validating a staged jar's trust file", and 12.10 step 5).
 *
 * <p>The jar is only read: its bytes are hashed and its ZIP entries are read as data. No class is
 * loaded or defined. Every rejection throws {@link UpdateRejectedException} with a fixed message.
 */
public final class ArtifactVerifier {

    static final String TRUST_ENTRY = "META-INF/quicraft/updater-trust.json";
    static final String BUILD_ENTRY = "META-INF/quicraft/build.json";
    static final int TRUST_MAX_BYTES = 65536;
    static final int BUILD_MAX_BYTES = 1024;

    private static final int COPY_BUFFER_BYTES = 8192;

    private ArtifactVerifier() {
    }

    /**
     * Verifies the staged jar at {@code stagedJar} against {@code c}, for the running server
     * {@code id}, with the version on disk {@code onDiskVersion} and the persisted {@code current}
     * state.
     *
     * @throws IOException if the file cannot be read
     * @throws UpdateRejectedException on any mismatch, malformed metadata or rejected trust file
     */
    public static StagedArtifact verify(
            Path stagedJar,
            AcceptedCandidate c,
            RuntimeIdentity id,
            UpdateVersion onDiskVersion,
            StateRecords.Snapshot current) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(stagedJar, "stagedJar");
        Objects.requireNonNull(c, "candidate");
        Objects.requireNonNull(id, "identity");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");
        Objects.requireNonNull(current, "current");

        UpdateDocuments.ReleaseEntry entry = c.entry();
        if (entry.size() <= 0) {
            throw reject("artifact size invalid");
        }
        if (!entry.platform().equals(id.platform())) {
            throw reject("artifact platform mismatch");
        }
        if (!entry.targets().contains(id.target())) {
            throw reject("artifact target mismatch");
        }
        UpdateVersion floor = max(id.runningVersion(), onDiskVersion);
        if (entry.version().compareTo(floor) <= 0) {
            throw reject("artifact version not newer than running or on-disk version");
        }

        BasicFileAttributes before = regularFileAttributes(stagedJar);
        if (before.size() != entry.size()) {
            throw reject("artifact size mismatch");
        }
        String digest = hashNoFollow(stagedJar, before, entry.size());
        if (!digest.equals(entry.sha512())) {
            throw reject("artifact sha512 mismatch");
        }

        Map<String, Integer> streamCounts = countWithZipInputStream(stagedJar);
        requireSingleMetadata(streamCounts);
        byte[] trustBytes;
        byte[] buildBytes;
        try (ZipFile zip = new ZipFile(stagedJar.toFile())) {
            requireUnchanged(stagedJar, before);
            requireSingleMetadata(countWithZipFile(zip));
            trustBytes = readCapped(zip, TRUST_ENTRY, TRUST_MAX_BYTES);
            buildBytes = readCapped(zip, BUILD_ENTRY, BUILD_MAX_BYTES);
        }

        UpdateDocuments.Trust trust = DocumentParser.parseTrust(trustBytes);
        BuildInfo build = DocumentParser.parseBuild(buildBytes);
        if (!build.platform().equals(entry.platform()) || !build.version().equals(entry.version())) {
            throw reject("artifact build metadata mismatch");
        }
        validateStagedTrust(trust, current);

        String fingerprint = KeySetFingerprint.of(trust.keys());
        requireKeySetChangeAuthorized(c, fingerprint, id, current);
        ArtifactIdentity identity = new ArtifactIdentity(
                entry.platform(), entry.version(), entry.size(), entry.sha512());
        return new StagedArtifact(stagedJar, identity, trust, fingerprint);
    }

    /**
     * Checks a staged trust file against the persisted state: exactly one revocation key, equal to
     * the persisted revocation key; no staged key is permanently revoked; the epoch is not lower; and
     * at an equal epoch the key-set fingerprint is unchanged. A different backup key is always
     * rejected, with no co-signature waiver.
     *
     * @throws UpdateRejectedException with a fixed message on any violation
     */
    public static void validateStagedTrust(UpdateDocuments.Trust staged, StateRecords.Snapshot current)
            throws UpdateRejectedException {
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(current, "current");
        StateRecords.UpdaterState state = current.state();

        int revocationKeys = 0;
        String revocationPub = "";
        for (UpdateDocuments.TrustedKey key : staged.keys()) {
            if (TrustedKeyRoles.of(key).contains(KeyRole.REVOCATION)) {
                revocationKeys++;
                revocationPub = key.pub();
            }
        }
        if (revocationKeys != 1) {
            throw reject("staged trust must hold exactly one revocation key");
        }
        if (!revocationPub.equals(state.revocationKey())) {
            throw reject("staged revocation key differs from persisted revocation key");
        }

        Set<String> revokedPubs = current.revoked().revokedPubs();
        for (UpdateDocuments.TrustedKey key : staged.keys()) {
            if (revokedPubs.contains(key.pub())) {
                throw reject("staged trust holds a revoked key");
            }
        }

        if (staged.epoch() < state.epoch()) {
            throw reject("staged trust epoch is older than persisted epoch");
        }
        if (staged.epoch() == state.epoch()
                && !KeySetFingerprint.of(staged.keys()).equals(state.keySetFingerprint())) {
            throw reject("staged key set differs from persisted key set at the same epoch");
        }
    }

    /**
     * Requires the staged key-set change to be authorized by the verified manifest and the persisted
     * backup key (docs/updater.md 12.2, key-set change rule). This is an extra requirement on top of
     * {@link #validateStagedTrust}, never a waiver.
     */
    private static void requireKeySetChangeAuthorized(
            AcceptedCandidate c, String fingerprint, RuntimeIdentity id, StateRecords.Snapshot current)
            throws UpdateRejectedException {
        TrustSnapshot snapshot = TrustSnapshot.of(id.runningTrust(), current.revoked().revokedPubs());
        if (!KeySetChangeAuthorization.check(
                c.manifest(), fingerprint, current.state().persistedTrust(), snapshot)) {
            throw reject("staged key-set change is not authorized by the backup key");
        }
    }

    private static BasicFileAttributes regularFileAttributes(Path path)
            throws IOException, UpdateRejectedException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw reject("artifact is not a regular file");
        }
        return attributes;
    }

    /** Re-reads the path without following links and requires it to still name the same file. */
    private static void requireUnchanged(Path path, BasicFileAttributes before)
            throws IOException, UpdateRejectedException {
        BasicFileAttributes now = regularFileAttributes(path);
        if (!Objects.equals(before.fileKey(), now.fileKey()) || before.size() != now.size()) {
            throw reject("artifact changed during verification");
        }
    }

    private static String hashNoFollow(Path path, BasicFileAttributes before, long expectedSize)
            throws IOException, UpdateRejectedException {
        MessageDigest digest = sha512();
        long counted = 0;
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            requireUnchanged(path, before);
            InputStream in = Channels.newInputStream(channel);
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
                counted += n;
                if (counted > expectedSize) {
                    throw reject("artifact size mismatch");
                }
            }
        }
        if (counted != expectedSize) {
            throw reject("artifact size mismatch");
        }
        return Hex.encode(digest.digest());
    }

    /** Counts entries by exact name with {@link ZipInputStream}, which sees duplicates in order. */
    private static Map<String, Integer> countWithZipInputStream(Path path) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                ZipInputStream zin = new ZipInputStream(Channels.newInputStream(channel))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                counts.merge(entry.getName(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Counts entries by exact name with {@link ZipFile}, the reader used for the content reads. */
    private static Map<String, Integer> countWithZipFile(ZipFile zip) {
        Map<String, Integer> counts = new HashMap<>();
        zip.stream().forEach(entry -> counts.merge(entry.getName(), 1, Integer::sum));
        return counts;
    }

    private static void requireSingleMetadata(Map<String, Integer> counts)
            throws UpdateRejectedException {
        if (counts.getOrDefault(TRUST_ENTRY, 0) != 1) {
            throw reject("artifact must hold exactly one trust entry");
        }
        if (counts.getOrDefault(BUILD_ENTRY, 0) != 1) {
            throw reject("artifact must hold exactly one build entry");
        }
    }

    /** Reads a metadata entry, reading at most {@code cap + 1} bytes so the cap is enforced. */
    private static byte[] readCapped(ZipFile zip, String name, int cap)
            throws IOException, UpdateRejectedException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw reject("artifact metadata entry missing");
        }
        if (entry.getSize() > cap) {
            throw reject("artifact metadata entry too large");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(cap + 1);
            if (bytes.length > cap) {
                throw reject("artifact metadata entry too large");
            }
            return bytes;
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private static UpdateVersion max(UpdateVersion a, UpdateVersion b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static UpdateRejectedException reject(String message) {
        return new UpdateRejectedException(message);
    }
}
