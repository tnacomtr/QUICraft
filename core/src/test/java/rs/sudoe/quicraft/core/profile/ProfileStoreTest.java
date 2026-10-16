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
    void aMalformedOverrideFailsToLoadAndNamesTheField() throws IOException {
        Path file = dir.resolve("profile.properties");
        Files.write(file, "maxConnections=lots\n".getBytes(StandardCharsets.UTF_8));
        ProfileStore store = new ProfileStore(file, ServerProfileTest.valid());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, store::load);
        assertTrue(e.getMessage().contains("maxConnections") || e.getMessage().contains("missing"));
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
