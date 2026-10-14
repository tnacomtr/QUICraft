// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Live-jar validation rules of docs/updater.md 12.14 (the loader-neutral part). */
class LiveJarValidatorTest {

    @TempDir
    Path tmp;

    private static final UpdateVersion V123 = versionOf("1.2.3");

    private static UpdateVersion versionOf(String text) {
        try {
            return UpdateVersion.parse(text);
        } catch (UpdateRejectedException e) {
            throw new AssertionError(e);
        }
    }

    /** The on-disk version reader the adapters pass: OwnJarVersion, with its checked errors wrapped. */
    private static UpdateVersion readVersion(Path jar) {
        try {
            return OwnJarVersion.read(jar);
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }

    private LiveJar validate(Path reported, Path scan) throws Exception {
        return LiveJarValidator.validate(reported, scan, JarFixtures::velocityLike,
                JarFixtures::testIds, JarFixtures.OWN_ID, LiveJarValidatorTest::readVersion);
    }

    private void assertRefused(Path reported, Path scan, String reasonPart) {
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> validate(reported, scan));
        assertTrue(e.getMessage().contains(reasonPart),
                () -> "reason was: " + e.getMessage());
    }

    private static String sha512Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(Files.readAllBytes(file));
        return Hex.encode(md.digest());
    }

    /** False only when the platform cannot make symbolic links (for example Windows without rights). */
    private static boolean canSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return false;
        }
    }

    @Test
    void happyPathReturnsSizeHashAndVersion() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft-1.2.3.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);

        LiveJar live = validate(jar, scan);

        assertEquals(jar.toRealPath(), live.realPath());
        assertEquals(scan.toRealPath(), live.realScanDirectory());
        assertEquals(Files.size(jar), live.size());
        assertEquals(sha512Hex(jar), live.sha512());
        assertEquals(V123, live.onDiskVersion());
    }

    @Test
    void reportedSymlinkIsRefusedEvenWhenItPointsInsideTheScanDirectory() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "real.jar", JarFixtures.OWN_ID, JarFixtures.BUILD_JSON_V1);
        Path link = scan.resolve("link.jar");
        assumeTrue(canSymlink(link, jar), "symbolic links unsupported here");

        assertRefused(link, scan, "live jar is a symbolic link");
    }

    @Test
    void parentDirectorySymlinkIsRefused() throws Exception {
        Path real = tmp.resolve("real-plugins");
        Path jar = JarFixtures.jar(real, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        Path scan = tmp.resolve("plugins");
        Files.createDirectories(scan);
        Path linkDir = tmp.resolve("linked");
        assumeTrue(canSymlink(linkDir, real), "symbolic links unsupported here");

        assertRefused(linkDir.resolve(jar.getFileName()), scan, "directory is a symbolic link");
    }

    @Test
    void realPathOutsideTheScanDirectoryIsRefused() throws Exception {
        // The fabric.addMods style: our jar is loaded from somewhere that is not the mods directory.
        Path other = JarFixtures.jar(tmp.resolve("elsewhere"), "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        Path scan = tmp.resolve("mods");
        Files.createDirectories(scan);

        assertRefused(other, scan, "outside the loader scan directory");
    }

    @Test
    void nameNotEndingInJarIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path file = JarFixtures.jar(scan, "quicraft.zip", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);

        assertRefused(file, scan, "does not end with .jar");
    }

    @Test
    void loaderFilterRejectingTheLiveJarIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);

        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> LiveJarValidator.validate(jar, scan, p -> false, JarFixtures::testIds,
                        JarFixtures.OWN_ID, LiveJarValidatorTest::readVersion));
        assertTrue(e.getMessage().contains("would not load"));
    }

    @Test
    void secondJarWithSameIdIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        JarFixtures.jar(scan, "quicraft-copy.jar", JarFixtures.OWN_ID, JarFixtures.BUILD_JSON_V1);

        assertRefused(jar, scan, "more than one QUICraft jar");
    }

    @Test
    void secondJarWithDifferentIdIsAccepted() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        JarFixtures.jar(scan, "other.jar", "someone-else", null);

        LiveJar live = validate(jar, scan);

        assertEquals(jar.toRealPath(), live.realPath());
    }

    @Test
    void hiddenJarThatTheFilterLoadsAndThatCarriesOurIdIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        JarFixtures.jar(scan, ".quicraft-x.jar", JarFixtures.OWN_ID, JarFixtures.BUILD_JSON_V1);

        assertRefused(jar, scan, "more than one QUICraft jar");
    }

    @Test
    void backupCopyThatTheFilterRejectsIsAccepted() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        JarFixtures.jar(scan, "quicraft.jar.bak", JarFixtures.OWN_ID, JarFixtures.BUILD_JSON_V1);

        LiveJar live = validate(jar, scan);

        assertEquals(jar.toRealPath(), live.realPath());
    }

    @Test
    void unreadableSiblingJarIsRefusedAsUninspectable() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);
        Files.write(scan.resolve("broken.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));

        assertRefused(jar, scan, "cannot inspect broken.jar for a duplicate QUICraft jar");
    }

    @Test
    void liveJarWithoutOwnIdIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", "someone-else", JarFixtures.BUILD_JSON_V1);

        assertRefused(jar, scan, "does not carry the QUICraft id");
    }

    @Test
    void directoryInsteadOfFileIsRefused() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path dir = scan.resolve("quicraft.jar");
        Files.createDirectories(dir);

        assertRefused(dir, scan, "not a regular file");
    }

    @Test
    void missingBuildJsonIsRefusedThroughTheVersionReader() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID, null);

        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, () -> validate(jar, scan));
        assertEquals("cannot read the live jar version", e.getMessage());
    }

    @Test
    void oversizedJarIsRefusedBeforeHashing() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.sparse(scan.resolve("quicraft.jar"), LiveJarValidator.MAX_JAR_BYTES + 1);

        // The id is stubbed so the size rule is the one being tested; the file is not a zip.
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> LiveJarValidator.validate(jar, scan, JarFixtures::velocityLike,
                        p -> Optional.of(JarFixtures.OWN_ID), JarFixtures.OWN_ID,
                        LiveJarValidatorTest::readVersion));
        assertEquals("live jar size is out of range", e.getMessage());
    }

    @Test
    void versionReaderThatThrowsRefusesTheJar() throws Exception {
        Path scan = tmp.resolve("plugins");
        Path jar = JarFixtures.jar(scan, "quicraft.jar", JarFixtures.OWN_ID,
                JarFixtures.BUILD_JSON_V1);

        assertThrows(UpdateRejectedException.class,
                () -> LiveJarValidator.validate(jar, scan, JarFixtures::velocityLike,
                        JarFixtures::testIds, JarFixtures.OWN_ID, p -> {
                            throw new IllegalStateException("boom");
                        }));
    }
}
