// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persisted profile overrides: atomic writes, restart survival, restore to defaults. */
class ProfileStoreTest {
    @TempDir
    Path dir;

    private static ServerProfile other() {
        return new ServerProfile(40_000, 3, 128L << 20, 0.05, 2048, 4L << 20, 6, 3, 1);
    }

    private static void assertSame(ServerProfile expected, ServerProfile actual) {
        assertEquals(expected.maxConnections, actual.maxConnections);
        assertEquals(expected.maxLanes, actual.maxLanes);
        assertEquals(expected.nodeBudgetBytes, actual.nodeBudgetBytes);
        assertEquals(expected.preLoginPoolFraction, actual.preLoginPoolFraction);
        assertEquals(expected.queuePacketLimit, actual.queuePacketLimit);
        assertEquals(expected.queueByteLimit, actual.queueByteLimit);
        assertEquals(expected.controlWeight, actual.controlWeight);
        assertEquals(expected.realtimeWeight, actual.realtimeWeight);
        assertEquals(expected.chunkWeight, actual.chunkWeight);
        assertEquals(expected.sessionCallsPerPrefix, actual.sessionCallsPerPrefix);
        assertEquals(expected.declaredBytesPerPrefix, actual.declaredBytesPerPrefix);
    }

    @Test
    void noOverrideFileLoadsTheDefaults() throws IOException {
        ServerProfile defaults = ServerProfileTest.valid();
        ProfileStore store = new ProfileStore(dir.resolve("profile.properties"), defaults);
        assertTrue(store.load() == defaults);
    }

    @Test
    void anOverrideSurvivesARestart() throws IOException {
        Path file = dir.resolve("profile.properties");
        new ProfileStore(file, ServerProfileTest.valid()).save(other());

        ServerProfile restored = new ProfileStore(file, ServerProfileTest.valid()).load();
        assertSame(other(), restored);
    }

    @Test
    void aBadCombinationIsRejectedAtomicallyAndLeavesTheOldFileAlone() throws IOException {
        Path file = dir.resolve("profile.properties");
        ProfileStore store = new ProfileStore(file, ServerProfileTest.valid());
        store.save(other());
        byte[] before = Files.readAllBytes(file);

        ServerProfile bad = new ServerProfile(40_000, 9, 128L << 20, 0.05, 2048, 4L << 20, 6, 3, 1);
        assertThrows(IllegalArgumentException.class, () -> store.save(bad));

        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the old override is untouched");
        try (Stream<Path> leftovers = Files.list(dir)) {
            assertEquals(1, leftovers.count(), "no temporary file is left behind");
        }
        assertSame(other(), store.load());
    }

    @Test
    void thePerPrefixCapsRoundTripThroughTheStore() throws IOException {
        Path file = dir.resolve("profile.properties");
        ServerProfile tuned = new ServerProfile(40_000, 3, 128L << 20, 0.05, 2048, 4L << 20, 6, 3, 1, 9, 3L << 20);
        new ProfileStore(file, ServerProfileTest.valid()).save(tuned);

        ServerProfile restored = new ProfileStore(file, ServerProfileTest.valid()).load();
        assertSame(tuned, restored);
        assertEquals(9, restored.sessionCallsPerPrefix);
        assertEquals(3L << 20, restored.declaredBytesPerPrefix);
    }

    @Test
    void aFileWrittenBeforeThePerPrefixCapsLoadsTheDefaultsForThem() throws IOException {
        Path file = dir.resolve("profile.properties");
        String oldFormat = "maxConnections=40000\nmaxLanes=3\nnodeBudgetBytes=134217728\npreLoginPoolFraction=0.05\n"
                + "queuePacketLimit=2048\nqueueByteLimit=4194304\ncontrolWeight=6\nrealtimeWeight=3\nchunkWeight=1\n";
        Files.write(file, oldFormat.getBytes(StandardCharsets.UTF_8));
        ServerProfile defaults = ServerProfileTest.valid();

        ServerProfile loaded = new ProfileStore(file, defaults).load();
        assertEquals(defaults.sessionCallsPerPrefix, loaded.sessionCallsPerPrefix);
        assertEquals(defaults.declaredBytesPerPrefix, loaded.declaredBytesPerPrefix);
        assertEquals(40_000, loaded.maxConnections, "the rest of the old file still loads");
    }

    /**
     * The record cap default was 1024 before 13.20. An override still holding exactly 1024 loads as the current
     * default, while any other value, including a deliberate one, loads unchanged.
     */
    @Test
    void aLegacyQueueRecordDefaultLoadsAsTheCurrentDefault() throws IOException {
        Path file = dir.resolve("profile.properties");
        String legacy = "maxConnections=40000\nmaxLanes=3\nnodeBudgetBytes=134217728\npreLoginPoolFraction=0.05\n"
                + "queuePacketLimit=1024\nqueueByteLimit=4194304\ncontrolWeight=6\nrealtimeWeight=3\nchunkWeight=1\n";
        Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));
        ServerProfile defaults = new ServerProfile(50_000, 5, 256L << 20, 0.10, 16384, 16L << 20, 8, 4, 2);

        assertEquals(16384, new ProfileStore(file, defaults).load().queuePacketLimit);

        Files.write(file, legacy.replace("queuePacketLimit=1024", "queuePacketLimit=2048")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(2048, new ProfileStore(file, defaults).load().queuePacketLimit);
    }

    /** The shard cap dropped from 6 to 5 for the ACK stream: an old override loads as 5, never disables /3. */
    @Test
    void anOverrideWrittenForTheOldCapOfSixLoadsAsFiveAndOtherBadValuesStillFail() throws IOException {
        Path file = dir.resolve("profile.properties");
        String text = "maxConnections=40000\nmaxLanes=6\nnodeBudgetBytes=134217728\npreLoginPoolFraction=0.05\n"
                + "queuePacketLimit=2048\nqueueByteLimit=4194304\ncontrolWeight=6\nrealtimeWeight=3\nchunkWeight=1\n";
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        ServerProfile loaded = new ProfileStore(file, ServerProfileTest.valid()).load();
        assertEquals(5, loaded.maxLanes);
        Files.write(file, text.replace("maxLanes=6", "maxLanes=7").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new ProfileStore(file, ServerProfileTest.valid()).load());
    }

    @Test
    void aMalformedPerPrefixCapFailsToLoadAndNamesTheField() throws IOException {
        Path file = dir.resolve("profile.properties");
        String text = "maxConnections=40000\nmaxLanes=3\nnodeBudgetBytes=134217728\npreLoginPoolFraction=0.05\n"
                + "queuePacketLimit=2048\nqueueByteLimit=4194304\ncontrolWeight=6\nrealtimeWeight=3\nchunkWeight=1\n"
                + "sessionCallsPerPrefix=many\n";
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ProfileStore(file, ServerProfileTest.valid()).load());
        assertTrue(e.getMessage().contains("sessionCallsPerPrefix"));
    }

    @Test
    void aMalformedOverrideFailsToLoadAndNamesTheField() throws IOException {
        Path file = dir.resolve("profile.properties");
        Files.write(file, "maxConnections=lots\n".getBytes(StandardCharsets.UTF_8));
        ProfileStore store = new ProfileStore(file, ServerProfileTest.valid());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, store::load);
        assertTrue(e.getMessage().contains("maxConnections") || e.getMessage().contains("missing"));
    }

    @Test
    void aFilesystemWithoutAtomicMoveFailsTheSaveAndKeepsTheOldFile() throws IOException {
        Path file = dir.resolve("profile.properties");
        new ProfileStore(file, ServerProfileTest.valid()).save(other());
        byte[] before = Files.readAllBytes(file);

        ProfileStore.FileMover refusesAtomic = (source, target, options) -> {
            throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        };
        ProfileStore store = new ProfileStore(file, ServerProfileTest.valid(), refusesAtomic);
        IOException e = assertThrows(IOException.class, () -> store.save(ServerProfileTest.valid()));
        assertTrue(e.getMessage().contains("atomically"), "the message says why the save failed");

        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the old override is untouched");
        try (Stream<Path> leftovers = Files.list(dir)) {
            assertEquals(1, leftovers.count(), "the temporary file is deleted after the failed move");
        }
        assertSame(other(), new ProfileStore(file, ServerProfileTest.valid()).load());
    }

    @Test
    void restoreDefaultsRemovesTheOverrideAndIsIdempotent() throws IOException {
        Path file = dir.resolve("profile.properties");
        ServerProfile defaults = ServerProfileTest.valid();
        ProfileStore store = new ProfileStore(file, defaults);
        store.save(other());
        store.restoreDefaults();
        store.restoreDefaults();
        assertFalse(Files.exists(file));
        assertTrue(store.load() == defaults);
    }
}
