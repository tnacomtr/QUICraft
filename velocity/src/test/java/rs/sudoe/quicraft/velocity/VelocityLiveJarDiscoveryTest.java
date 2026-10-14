// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.DevelopmentBuildException;
import rs.sudoe.quicraft.updater.LiveJar;
import rs.sudoe.quicraft.updater.UpdateRejectedException;

/** Discovery of the running plugin jar, with Velocity's plugin scan rules (docs/updater.md 12.14). */
class VelocityLiveJarDiscoveryTest {

    private static final String BUILD_JSON =
            "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"velocity\",\"version\":\"0.1.0-alpha.1\"}";
    private static final String SNAPSHOT_BUILD_JSON =
            "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"velocity\",\"version\":\"0.1.0-SNAPSHOT\"}";

    private static Path plugins(Path root) throws IOException {
        return Files.createDirectories(root.resolve("plugins"));
    }

    /** A jar with a velocity-plugin.json naming {@code id}, and the build metadata QUICraft ships. */
    private static Path jar(Path file, String id) throws IOException {
        return jar(file, "{\"id\":\"" + id + "\",\"main\":\"x.Y\",\"version\":\"0.1.0\"}", BUILD_JSON);
    }

    /** A jar with a raw descriptor and raw build metadata, for the awkward cases. */
    private static Path jar(Path file, String descriptor, String buildJson) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "velocity-plugin.json", descriptor);
            put(zip, "META-INF/quicraft/build.json", buildJson);
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(UTF_8));
        zip.closeEntry();
    }

    private static VelocityLiveJarDiscovery discovery(Path source, Path scan) {
        return new VelocityLiveJarDiscovery(() -> Optional.ofNullable(source), scan);
    }

    @Test
    void happyPathFindsTheLiveJar(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft-0.1.0-alpha.1.jar"), "quicraft");

        LiveJar found = discovery(live, scan).discover();

        assertEquals(live.toRealPath(), found.realPath());
        assertEquals(scan.toRealPath(), found.realScanDirectory());
        assertEquals("0.1.0-alpha.1", found.onDiskVersion().toString());
    }

    @Test
    void unknownSourceIsRefused(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        assertThrows(UpdateRejectedException.class, () -> discovery(null, scan).discover());
    }

    @Test
    void sourceOutsidePluginsIsRefused(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path elsewhere = jar(root.resolve("elsewhere/quicraft.jar"), "quicraft");
        assertThrows(UpdateRejectedException.class, () -> discovery(elsewhere, scan).discover());
    }

    @Test
    void symlinkedSourceIsRefused(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path real = jar(root.resolve("store/quicraft.jar"), "quicraft");
        Path link = Files.createSymbolicLink(scan.resolve("quicraft.jar"), real);
        assertThrows(UpdateRejectedException.class, () -> discovery(link, scan).discover());
    }

    @Test
    void duplicateQuicraftJarIsRefused(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve("quicraft-copy.jar"), "quicraft");
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void hiddenDuplicateIsRefusedBecauseVelocityLoadsIt(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve(".quicraft-x.jar"), "quicraft");
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void backupSiblingIsIgnoredBecauseVelocityDoesNotLoadIt(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve("quicraft.jar.bak"), "quicraft");
        LiveJar found = discovery(live, scan).discover();
        assertEquals(live.toRealPath(), found.realPath());
    }

    @Test
    void unreadableJarSiblingFailsClosed(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        Files.writeString(scan.resolve("broken.jar"), "not a zip");
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void oversizedDescriptorSiblingFailsClosed(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        Path big = scan.resolve("big.jar");
        try (OutputStream raw = Files.newOutputStream(big); ZipOutputStream zip = new ZipOutputStream(raw)) {
            put(zip, "velocity-plugin.json", "{\"id\":\"other\",\"pad\":\"" + "a".repeat(70 * 1024) + "\"}");
        }
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void siblingWithAnotherIdDoesNotBlock(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve("someone-else.jar"), "someone-else");
        assertEquals(live.toRealPath(), discovery(live, scan).discover().realPath());
    }

    @Test
    void idOfReadsOnlyTheDescriptor(@TempDir Path root) throws Exception {
        Path jar = jar(root.resolve("x.jar"), "quicraft");
        assertEquals(Optional.of("quicraft"), VelocityLiveJarDiscovery.idOf(jar));
    }

    @Test
    void siblingProvidingTheQuicraftIdIsRefused(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve("alias.jar"),
                "{\"id\":\"other\",\"main\":\"a.B\",\"version\":\"1.0.0\",\"provides\":[\"quicraft\"]}",
                BUILD_JSON);
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void aLongSiblingDescriptionDoesNotRefuse(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        // Velocity's descriptor has no limit on description; StrictJson's 512-char cap must not apply.
        jar(scan.resolve("other.jar"),
                "{\"id\":\"other\",\"main\":\"a.B\",\"version\":\"1.0.0\",\"description\":\""
                        + "d".repeat(600) + "\"}",
                BUILD_JSON);
        assertEquals(live.toRealPath(), discovery(live, scan).discover().realPath());
    }

    @Test
    void aSnapshotOwnJarReachesTheCallerAsADevelopmentBuild(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"),
                "{\"id\":\"quicraft\",\"main\":\"x.Y\",\"version\":\"0.1.0-SNAPSHOT\"}",
                SNAPSHOT_BUILD_JSON);
        assertThrows(DevelopmentBuildException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void aCorruptSiblingDescriptorStillFailsClosed(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        jar(scan.resolve("corrupt.jar"), "{\"id\":\"other\",", BUILD_JSON);
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }
}
