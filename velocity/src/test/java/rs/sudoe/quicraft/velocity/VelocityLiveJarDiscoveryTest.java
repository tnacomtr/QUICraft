// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
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

    @Test
    void aSiblingWithTwoDescriptorsFailsClosed(@TempDir Path root) throws Exception {
        Path scan = plugins(root);
        Path live = jar(scan.resolve("quicraft.jar"), "quicraft");
        twoDescriptorJar(scan.resolve("twin.jar"),
                "{\"id\":\"other\",\"main\":\"a.B\",\"version\":\"1.0.0\"}",
                "{\"id\":\"someone-else\",\"main\":\"a.B\",\"version\":\"1.0.0\"}");
        assertThrows(UpdateRejectedException.class, () -> discovery(live, scan).discover());
    }

    @Test
    void idOfRefusesTwoDescriptorEntries(@TempDir Path root) throws Exception {
        Path twin = twoDescriptorJar(root.resolve("twin.jar"),
                "{\"id\":\"other\",\"main\":\"a.B\",\"version\":\"1.0.0\"}",
                "{\"id\":\"someone-else\",\"main\":\"a.B\",\"version\":\"1.0.0\"}");
        assertThrows(IOException.class, () -> VelocityLiveJarDiscovery.idOf(twin));
    }

    /**
     * A zip whose central directory holds two entries with the same name, assembled by hand:
     * {@link ZipOutputStream} refuses a duplicate name ("duplicate entry"), while readers disagree on
     * which of the entries counts, which is exactly what must be refused. Both entries are stored
     * (uncompressed), so no compressor is involved.
     */
    private static Path twoDescriptorJar(Path file, String first, String second) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] name = VelocityLiveJarDiscovery.DESCRIPTOR.getBytes(UTF_8);
        List<byte[]> bodies = new ArrayList<>();
        bodies.add(first.getBytes(UTF_8));
        bodies.add(second.getBytes(UTF_8));

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        List<byte[]> records = new ArrayList<>();
        for (byte[] body : bodies) {
            int offset = zip.size();
            zip.writeBytes(le32(0x04034b50L)); // local file header
            zip.writeBytes(le16(20)); // version needed
            zip.writeBytes(le16(0)); // flags
            zip.writeBytes(le16(0)); // stored
            zip.writeBytes(le16(0)); // time
            zip.writeBytes(le16(0x21)); // 1980-01-01
            zip.writeBytes(le32(crc32(body)));
            zip.writeBytes(le32(body.length));
            zip.writeBytes(le32(body.length));
            zip.writeBytes(le16(name.length));
            zip.writeBytes(le16(0)); // no extra field
            zip.writeBytes(name);
            zip.writeBytes(body);
            records.add(centralRecord(name, body, offset));
        }
        int directoryOffset = zip.size();
        for (byte[] record : records) {
            zip.writeBytes(record);
        }
        int directorySize = zip.size() - directoryOffset;
        zip.writeBytes(le32(0x06054b50L)); // end of central directory
        zip.writeBytes(le16(0)); // this disk
        zip.writeBytes(le16(0)); // directory start disk
        zip.writeBytes(le16(records.size()));
        zip.writeBytes(le16(records.size()));
        zip.writeBytes(le32(directorySize));
        zip.writeBytes(le32(directoryOffset));
        zip.writeBytes(le16(0)); // no comment
        Files.write(file, zip.toByteArray());
        return file;
    }

    private static byte[] centralRecord(byte[] name, byte[] body, int offset) {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.writeBytes(le32(0x02014b50L));
        record.writeBytes(le16(20)); // version made by
        record.writeBytes(le16(20)); // version needed
        record.writeBytes(le16(0)); // flags
        record.writeBytes(le16(0)); // stored
        record.writeBytes(le16(0)); // time
        record.writeBytes(le16(0x21)); // date
        record.writeBytes(le32(crc32(body)));
        record.writeBytes(le32(body.length));
        record.writeBytes(le32(body.length));
        record.writeBytes(le16(name.length));
        record.writeBytes(le16(0)); // no extra field
        record.writeBytes(le16(0)); // no comment
        record.writeBytes(le16(0)); // disk number start
        record.writeBytes(le16(0)); // internal attributes
        record.writeBytes(le32(0)); // external attributes
        record.writeBytes(le32(offset));
        record.writeBytes(name);
        return record.toByteArray();
    }

    private static byte[] le16(int value) {
        return new byte[] {(byte) (value & 0xff), (byte) ((value >>> 8) & 0xff)};
    }

    private static byte[] le32(long value) {
        return new byte[] {(byte) (value & 0xff), (byte) ((value >>> 8) & 0xff),
                (byte) ((value >>> 16) & 0xff), (byte) ((value >>> 24) & 0xff)};
    }

    private static long crc32(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return crc.getValue();
    }
}
