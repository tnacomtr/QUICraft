// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class FailureCacheTest {
    private static final long MIN = TimeUnit.MINUTES.toMillis(1);
    private static InetAddress ip;
    private static Fingerprint fp;

    @BeforeAll
    static void setUp() throws Exception {
        ip = InetAddress.getByName("192.0.2.10");
        fp = ServerIdentity.generate().fingerprint();
    }

    @Test
    void backsOffOneMinuteDoublingToOneHour() {
        AtomicLong now = new AtomicLong(1_000_000);
        FailureCache cache = FailureCache.load(null, now::get);
        assertTrue(cache.allows(ip, 25565, fp));
        long[] expected = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long minutes : expected) {
            cache.recordFailure(ip, 25565, fp);
            now.addAndGet(minutes * MIN - 1);
            assertFalse(cache.allows(ip, 25565, fp), "still blocked just before " + minutes + " min");
            now.addAndGet(1);
            assertTrue(cache.allows(ip, 25565, fp), "allowed again after " + minutes + " min");
        }
    }

    @Test
    void successClearsAndKeysAreIndependent() throws Exception {
        AtomicLong now = new AtomicLong(0);
        FailureCache cache = FailureCache.load(null, now::get);
        cache.recordFailure(ip, 25565, fp);
        assertFalse(cache.allows(ip, 25565, fp));
        assertTrue(cache.allows(ip, 25566, fp), "other port");
        assertTrue(cache.allows(InetAddress.getByName("192.0.2.11"), 25565, fp), "other address");
        assertTrue(cache.allows(ip, 25565, ServerIdentity.generate().fingerprint()), "rotated certificate");
        cache.recordSuccess(ip, 25565, fp);
        assertTrue(cache.allows(ip, 25565, fp));
        cache.recordFailure(ip, 25565, fp);
        now.addAndGet(MIN);
        assertTrue(cache.allows(ip, 25565, fp), "back to the 1 minute start after a success");
    }

    @Test
    void clearForgetsEverythingAlsoOnDisk(@TempDir Path dir) {
        Path file = dir.resolve("quic-failures.properties");
        AtomicLong now = new AtomicLong(0);
        FailureCache cache = FailureCache.load(file, now::get);
        cache.recordFailure(ip, 25565, fp);
        cache.recordFailure(ip, 25566, fp);
        cache.clear();
        assertTrue(cache.allows(ip, 25565, fp));
        assertTrue(cache.allows(ip, 25566, fp));
        assertTrue(FailureCache.load(file, now::get).allows(ip, 25565, fp), "cleared on disk too");
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("quic-failures.properties");
        AtomicLong now = new AtomicLong(5_000_000);
        FailureCache.load(file, now::get).recordFailure(ip, 25565, fp);
        assertTrue(Files.isRegularFile(file));
        FailureCache reloaded = FailureCache.load(file, now::get);
        assertFalse(reloaded.allows(ip, 25565, fp));
        now.addAndGet(MIN);
        assertTrue(reloaded.allows(ip, 25565, fp));
    }

    @Test
    void aCorruptFileReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("quic-failures.properties");
        Files.write(file, "garbage\n\u0000ÿ=1,x\n".getBytes(StandardCharsets.ISO_8859_1));
        FailureCache cache = FailureCache.load(file, () -> 0L);
        assertTrue(cache.allows(ip, 25565, fp));
        cache.recordFailure(ip, 25565, fp); // and it can be written again
        assertFalse(FailureCache.load(file, () -> 0L).allows(ip, 25565, fp));
    }

    @Test
    void implausibleEntriesAreIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("quic-failures.properties");
        String key = FailureCache.key(ip, 25565, fp).replace(":", "\\:");
        Files.write(file, (key + "=3," + Long.MAX_VALUE + "\n").getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(FailureCache.load(file, () -> 0L).allows(ip, 25565, fp), "a block far beyond 1 hour is ignored");
    }

    @Test
    void reasonCodesRoundTrip() {
        for (FallbackReason r : FallbackReason.values()) {
            assertEquals(r, FallbackReason.fromCode(r.code()));
        }
        assertEquals(FallbackReason.OTHER, FallbackReason.fromCode(99));
    }
}
