// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Staged-jar verification and staged trust validation (docs/updater.md 12.2 and 12.10 step 5). */
class ArtifactVerifierTest {

    private static final String PLATFORM = "fabric-26x";
    private static final String TARGET = "26.1";
    private static final String VERSION = "0.4.0";
    private static final String RUNNING = "0.3.0";
    private static final String ON_DISK = "0.3.0";
    private static final String PAYLOAD_NAME = "payload.bin";
    private static final byte[] PAYLOAD = payload(4096);

    @TempDir
    Path dir;

    // ---- happy path and verify() rejections ----

    @Test
    void happyPathReturnsStagedArtifactWithIdentityAndTrust() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("ok.jar", jar);
        StagedArtifact artifact = ArtifactVerifier.verify(
                staged, candidateFor(jar, PLATFORM, VERSION, List.of(TARGET)),
                runtime(RUNNING), UpdateVersion.parse(ON_DISK), currentState());

        assertEquals(staged, artifact.path());
        assertEquals(
                new ArtifactIdentity(PLATFORM, UpdateVersion.parse(VERSION), jar.length, sha512Hex(jar)),
                artifact.identity());
        assertEquals(1, artifact.stagedTrust().epoch());
        assertEquals(fingerprint(standardKeys()), artifact.stagedKeySetFingerprint());
    }

    @Test
    void sizeMismatchIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidate(PLATFORM, VERSION, List.of(TARGET), jar.length + 1, sha512Hex(jar));
        Path staged = stage("size.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void sha512MismatchIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidate(PLATFORM, VERSION, List.of(TARGET), jar.length, SignedFixtures.SHA512_B);
        Path staged = stage("sha.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void symlinkedJarIsRefused() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        Path real = stage("real.jar", jar);
        Path link = dir.resolve("link.jar");
        Files.createSymbolicLink(link, real);
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        assertRejected(() -> verify(link, c, RUNNING, ON_DISK));
    }

    @Test
    void wrongPlatformIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson("velocity", VERSION));
        AcceptedCandidate c = candidateFor(jar, "velocity", VERSION, List.of(TARGET));
        Path staged = stage("platform.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void missingTargetIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of("26.2"));
        Path staged = stage("target.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void versionNotGreaterThanRunningIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("running.jar", jar);
        assertRejected(() -> verify(staged, c, VERSION, ON_DISK));
    }

    @Test
    void versionNotGreaterThanOnDiskIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("disk.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, VERSION));
    }

    @Test
    void missingTrustEntryIsRejected() throws Exception {
        byte[] jar = zip(entry(PAYLOAD_NAME, PAYLOAD), entry(ArtifactVerifier.BUILD_ENTRY, bytes(buildJson(PLATFORM, VERSION))));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("notrust.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void duplicateTrustEntryIsRejected() throws Exception {
        byte[] trust = bytes(trustJson(1, standardKeys()));
        byte[] jar = rawZip(
                entry(ArtifactVerifier.TRUST_ENTRY, trust),
                entry(ArtifactVerifier.TRUST_ENTRY, trust),
                entry(ArtifactVerifier.BUILD_ENTRY, bytes(buildJson(PLATFORM, VERSION))));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("duptrust.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void duplicateBuildEntryIsRejected() throws Exception {
        byte[] build = bytes(buildJson(PLATFORM, VERSION));
        byte[] jar = rawZip(
                entry(ArtifactVerifier.TRUST_ENTRY, bytes(trustJson(1, standardKeys()))),
                entry(ArtifactVerifier.BUILD_ENTRY, build),
                entry(ArtifactVerifier.BUILD_ENTRY, build));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("dupbuild.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void oversizedBuildEntryIsRejected() throws Exception {
        String padded = buildJson(PLATFORM, VERSION) + " ".repeat(1100);
        byte[] jar = standardJar(trustJson(1, standardKeys()), padded);
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("bigbuild.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void oversizedTrustEntryIsRejected() throws Exception {
        String padded = trustJson(1, standardKeys()) + " ".repeat(65537);
        byte[] jar = standardJar(padded, buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("bigtrust.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void buildPlatformMismatchIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson("velocity", VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("buildplat.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void buildVersionMismatchIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, "0.4.1"));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("buildver.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void verifyRejectsStagedTrustWithDifferentRevocationKey() throws Exception {
        String swapped = trustJson(1, keyJson(TestKeys.KID_1, TestKeys.PUB_1, "[\"release\",\"revocation\"]")
                + "," + keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\"]"));
        byte[] jar = standardJar(swapped, buildJson(PLATFORM, VERSION));
        AcceptedCandidate c = candidateFor(jar, PLATFORM, VERSION, List.of(TARGET));
        Path staged = stage("swap.jar", jar);
        assertRejected(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    // ---- file identity between hashing and ZIP reads ----

    @Test
    void sameSizeRewriteWithNewModificationTimeIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("toctou.jar", jar);
        java.nio.file.attribute.BasicFileAttributes before = Files.readAttributes(
                staged, java.nio.file.attribute.BasicFileAttributes.class);
        // Same inode and same size; only the modification time moves.
        Files.write(staged, jar);
        Files.setLastModifiedTime(staged,
                java.nio.file.attribute.FileTime.from(before.lastModifiedTime().toInstant().plusSeconds(3600)));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> ArtifactVerifier.requireUnchanged(staged, before));
        assertEquals("artifact changed during verification", e.getMessage());
    }

    @Test
    void unchangedFileIsAcceptedByRequireUnchanged() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("stable.jar", jar);
        java.nio.file.attribute.BasicFileAttributes before = Files.readAttributes(
                staged, java.nio.file.attribute.BasicFileAttributes.class);
        assertDoesNotThrow(() -> ArtifactVerifier.requireUnchanged(staged, before));
    }

    // ---- key-set change authorization (docs/updater.md 12.2, key-set change rule) ----

    @Test
    void changedKeySetSignedOnlyByReleaseKeyIsRejected() throws Exception {
        byte[] jar = standardJar(trustJson(2, backupOnlyKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("keyset-release.jar", jar);
        AcceptedCandidate c = candidateSigned(jar, releaseSigners());
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> verify(staged, c, RUNNING, ON_DISK));
        assertEquals("staged key-set change is not authorized by the backup key", e.getMessage());
    }

    @Test
    void changedKeySetCoSignedByPersistedBackupKeyIsAccepted() throws Exception {
        byte[] jar = standardJar(trustJson(2, backupOnlyKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("keyset-backup.jar", jar);
        AcceptedCandidate c = candidateSigned(jar, backupCoSigners());
        assertDoesNotThrow(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void unchangedKeySetSignedOnlyByReleaseKeyIsAccepted() throws Exception {
        byte[] jar = standardJar(trustJson(1, standardKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("keyset-same.jar", jar);
        AcceptedCandidate c = candidateSigned(jar, releaseSigners());
        assertDoesNotThrow(() -> verify(staged, c, RUNNING, ON_DISK));
    }

    @Test
    void changedKeySetWithRevokedBackupKeyIsRejected() throws Exception {
        // The persisted backup key is revoked, so the staged trust (which must name it) is refused
        // by validateStagedTrust before the co-signature rule is reached.
        Set<String> revoked = Set.of(TestKeys.PUB_2);
        byte[] jar = standardJar(trustJson(2, backupOnlyKeys()), buildJson(PLATFORM, VERSION));
        Path staged = stage("keyset-revoked.jar", jar);
        AcceptedCandidate c = candidate(PLATFORM, VERSION, List.of(TARGET), jar.length, sha512Hex(jar),
                backupCoSigners(), runningSnapshot(revoked));
        assertRejected(() -> verifyWith(staged, c, state(1, fingerprint(standardKeys()), TestKeys.PUB_2, revoked)));
    }

    // ---- validateStagedTrust ----

    @Test
    void differentRevocationKeyIsRejected() throws Exception {
        UpdateDocuments.Trust staged = parseTrust(trustJson(1,
                keyJson(TestKeys.KID_1, TestKeys.PUB_1, "[\"release\",\"revocation\"]")
                        + "," + keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\"]")));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, currentState()));
    }

    @Test
    void revokedKeyIsRejected() throws Exception {
        StateRecords.Snapshot current = state(1, fingerprint(standardKeys()), TestKeys.PUB_2,
                Set.of(TestKeys.PUB_1));
        UpdateDocuments.Trust staged = parseTrust(trustJson(1, standardKeys()));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, current));
    }

    @Test
    void lowerEpochIsRejected() throws Exception {
        StateRecords.Snapshot current = state(2, fingerprint(standardKeys()), TestKeys.PUB_2, Set.of());
        UpdateDocuments.Trust staged = parseTrust(trustJson(1, standardKeys()));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, current));
    }

    @Test
    void equalEpochWithDifferentFingerprintIsRejected() throws Exception {
        UpdateDocuments.Trust staged = parseTrust(trustJson(1,
                keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\",\"revocation\"]")));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, currentState()));
    }

    @Test
    void higherEpochWithSameRevocationKeyIsAccepted() throws Exception {
        UpdateDocuments.Trust staged = parseTrust(trustJson(2,
                keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\",\"revocation\"]")));
        assertDoesNotThrow(() -> ArtifactVerifier.validateStagedTrust(staged, currentState()));
    }

    @Test
    void zeroRevocationKeysIsRejected() throws Exception {
        UpdateDocuments.Trust staged = new UpdateDocuments.Trust(1, Instant.parse(SignedFixtures.TS1), 0, 0,
                List.of(new UpdateDocuments.TrustedKey(TestKeys.KID_1, TestKeys.PUB_1, List.of("release"))));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, currentState()));
    }

    @Test
    void twoRevocationKeysIsRejected() throws Exception {
        UpdateDocuments.Trust staged = new UpdateDocuments.Trust(1, Instant.parse(SignedFixtures.TS1), 0, 0,
                List.of(
                        new UpdateDocuments.TrustedKey(TestKeys.KID_1, TestKeys.PUB_1,
                                List.of("release", "revocation")),
                        new UpdateDocuments.TrustedKey(TestKeys.KID_2, TestKeys.PUB_2,
                                List.of("release", "revocation"))));
        assertRejected(() -> ArtifactVerifier.validateStagedTrust(staged, currentState()));
    }

    // ---- fixtures ----

    private void verify(Path staged, AcceptedCandidate c, String running, String onDisk) throws Exception {
        ArtifactVerifier.verify(staged, c, runtime(running), UpdateVersion.parse(onDisk), currentState());
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable action) {
        assertThrows(UpdateRejectedException.class, action);
    }

    private Path stage(String name, byte[] jar) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, jar);
        return path;
    }

    private static byte[] standardJar(String trust, String build) throws IOException {
        return zip(entry(PAYLOAD_NAME, PAYLOAD),
                entry(ArtifactVerifier.TRUST_ENTRY, bytes(trust)),
                entry(ArtifactVerifier.BUILD_ENTRY, bytes(build)));
    }

    private static AcceptedCandidate candidateFor(byte[] jar, String platform, String version, List<String> targets)
            throws Exception {
        return candidate(platform, version, targets, jar.length, sha512Hex(jar));
    }

    private static AcceptedCandidate candidate(
            String platform, String version, List<String> targets, long size, String sha512) throws Exception {
        return candidate(platform, version, targets, size, sha512,
                new VerifiedSigners(List.of()), runningSnapshot(Set.of()));
    }

    private static AcceptedCandidate candidate(
            String platform, String version, List<String> targets, long size, String sha512,
            VerifiedSigners signers, TrustSnapshot snapshot) throws Exception {
        UpdateDocuments.ReleaseEntry entry = new UpdateDocuments.ReleaseEntry(
                platform, UpdateVersion.parse(version), targets, "quicraft-" + platform + "-" + version + ".jar",
                size, sha512, "v" + version);
        UpdateDocuments.ReleaseManifest manifest = new UpdateDocuments.ReleaseManifest(
                42, Instant.parse(SignedFixtures.TS1), Instant.parse(SignedFixtures.TS2), 0,
                List.of(entry), List.of());
        VerifiedDocument<UpdateDocuments.ReleaseManifest> doc = new VerifiedDocument<>(
                manifest, bytes("{}"), signers, snapshot);
        return new AcceptedCandidate(doc, new ManifestEvidence(bytes("{}"), bytes("{}")), entry,
                List.of(new ReleaseHost("modrinth", URI.create("https://modrinth.example"))));
    }

    /** A candidate for {@code jar} whose manifest was verified under the running trust, with {@code signers}. */
    private static AcceptedCandidate candidateSigned(byte[] jar, VerifiedSigners signers) throws Exception {
        return candidate(PLATFORM, VERSION, List.of(TARGET), jar.length, sha512Hex(jar),
                signers, runningSnapshot(Set.of()));
    }

    /** The snapshot of the running trust (standard keys, epoch 1) with the given revoked public keys. */
    private static TrustSnapshot runningSnapshot(Set<String> revoked) throws Exception {
        return TrustSnapshot.of(parseTrust(trustJson(1, standardKeys())), revoked);
    }

    /** Manifest signed only by the release key (TEST 1). */
    private static VerifiedSigners releaseSigners() {
        return new VerifiedSigners(List.of(
                new VerifiedSigner(TestKeys.KID_1, TestKeys.PUB_1, Set.of(KeyRole.RELEASE))));
    }

    /** Manifest signed by the release key and by the persisted backup key (TEST 2, both roles). */
    private static VerifiedSigners backupCoSigners() {
        return new VerifiedSigners(List.of(
                new VerifiedSigner(TestKeys.KID_1, TestKeys.PUB_1, Set.of(KeyRole.RELEASE)),
                new VerifiedSigner(TestKeys.KID_2, TestKeys.PUB_2,
                        Set.of(KeyRole.RELEASE, KeyRole.REVOCATION))));
    }

    /** A staged key set that differs from the standard one: only the backup key remains. */
    private static String backupOnlyKeys() {
        return keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\",\"revocation\"]");
    }

    private static void verifyWith(Path staged, AcceptedCandidate c, StateRecords.Snapshot current)
            throws Exception {
        ArtifactVerifier.verify(staged, c, runtime(RUNNING), UpdateVersion.parse(ON_DISK), current);
    }

    private static RuntimeIdentity runtime(String running) throws Exception {
        return new RuntimeIdentity(PLATFORM, TARGET, UpdateVersion.parse(running),
                parseTrust(trustJson(1, standardKeys())), SignedFixtures.SHA512_B);
    }

    private static StateRecords.Snapshot currentState() throws Exception {
        return state(1, fingerprint(standardKeys()), TestKeys.PUB_2, Set.of());
    }

    private static StateRecords.Snapshot state(int epoch, String fingerprint, String revocationPub,
            Set<String> revokedPubs) throws Exception {
        List<UpdateDocuments.RevokedKey> revoked = new java.util.ArrayList<>();
        for (String pub : revokedPubs) {
            revoked.add(new UpdateDocuments.RevokedKey(Hex.kid(pub), pub));
        }
        StateRecords.UpdaterState state = new StateRecords.UpdaterState(
                epoch, fingerprint, revocationPub,
                new StateRecords.CurrentRevocations(0, "", null, null, 0),
                Map.of(), List.of(), "", List.of(), Map.of());
        return new StateRecords.Snapshot(state, new StateRecords.RevokedState(revoked, List.of()));
    }

    private static UpdateDocuments.Trust parseTrust(String json) throws Exception {
        return DocumentParser.parseTrust(bytes(json));
    }

    private static String fingerprint(String keysJson) throws Exception {
        return KeySetFingerprint.of(parseTrust(trustJson(1, keysJson)).keys());
    }

    /** Standard test keys: TEST 1 release only, TEST 2 release and revocation. */
    private static String standardKeys() {
        return keyJson(TestKeys.KID_1, TestKeys.PUB_1, "[\"release\"]")
                + "," + keyJson(TestKeys.KID_2, TestKeys.PUB_2, "[\"release\",\"revocation\"]");
    }

    private static String keyJson(String kid, String pub, String roles) {
        return "{\"kid\":\"" + kid + "\",\"pub\":\"" + pub + "\",\"roles\":" + roles + "}";
    }

    private static String trustJson(int epoch, String keys) {
        return "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":" + epoch
                + ",\"built\":\"" + SignedFixtures.TS1 + "\""
                + ",\"revocationsSeqFloor\":0,\"manifestSeqFloor\":0,\"keys\":[" + keys + "]}";
    }

    private static String buildJson(String platform, String version) {
        return "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"" + platform
                + "\",\"version\":\"" + version + "\"}";
    }

    private static byte[] payload(int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (i * 31 + 7);
        }
        return out;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha512Hex(byte[] data) throws Exception {
        return Hex.encode(MessageDigest.getInstance("SHA-512").digest(data));
    }

    private record Entry(String name, byte[] data) {
    }

    private static Entry entry(String name, byte[] data) {
        return new Entry(name, data);
    }

    /** A well-formed archive built with {@link ZipOutputStream}. */
    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.data());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * An archive with stored entries written by hand, so that duplicate names can be produced
     * (ZipOutputStream refuses them).
     */
    private static byte[] rawZip(Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
            byte[] data = e.data();
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = out.size();
            le32(out, 0x04034b50L);
            le16(out, 20);
            le16(out, 0);
            le16(out, 0);
            le16(out, 0);
            le16(out, 0);
            le32(out, crc.getValue());
            le32(out, data.length);
            le32(out, data.length);
            le16(out, name.length);
            le16(out, 0);
            out.write(name);
            out.write(data);

            le32(central, 0x02014b50L);
            le16(central, 20);
            le16(central, 20);
            le16(central, 0);
            le16(central, 0);
            le16(central, 0);
            le16(central, 0);
            le32(central, crc.getValue());
            le32(central, data.length);
            le32(central, data.length);
            le16(central, name.length);
            le16(central, 0);
            le16(central, 0);
            le16(central, 0);
            le16(central, 0);
            le32(central, 0);
            le32(central, offset);
            central.write(name);
        }
        int cdOffset = out.size();
        byte[] cd = central.toByteArray();
        out.write(cd);
        le32(out, 0x06054b50L);
        le16(out, 0);
        le16(out, 0);
        le16(out, entries.length);
        le16(out, entries.length);
        le32(out, cd.length);
        le32(out, cdOffset);
        le16(out, 0);
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static void le32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 24) & 0xff));
    }
}
