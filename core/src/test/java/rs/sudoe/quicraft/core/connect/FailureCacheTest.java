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

    /**
     * 12.14: an activation failure overrides a previous handshake success and is cleared only by a
     * later successful activation for that key, so the handshake-level {@code recordSuccess} must
     * not touch it.
     */
    @Test
    void aHandshakeSuccessDoesNotClearAnActivationFailure() {
        AtomicLong now = new AtomicLong(1_000_000);
        FailureCache cache = FailureCache.load(null, now::get);
        assertFalse(cache.isActivationBlocked(ip, 25565, fp, now.get()), "nothing recorded yet");

        cache.recordActivationFailure(ip, 25565, fp);
        assertTrue(cache.isActivationBlocked(ip, 25565, fp, now.get()));

        cache.recordSuccess(ip, 25565, fp);
        assertTrue(cache.isActivationBlocked(ip, 25565, fp, now.get()),
                "a handshake win does not clear an activation failure");
        assertTrue(cache.allows(ip, 25565, fp), "the handshake entry itself is cleared");

        // Only a later successful activation clears it.
        cache.clearActivationFailure(ip, 25565, fp);
        assertFalse(cache.isActivationBlocked(ip, 25565, fp, now.get()));
    }

    @Test
    void activationFailuresFollowSectionSixsScheduleAndTheirOwnKeys() throws Exception {
        AtomicLong now = new AtomicLong(0);
        FailureCache cache = FailureCache.load(null, now::get);
        cache.recordActivationFailure(ip, 25565, fp);
        now.addAndGet(MIN - 1);
        assertTrue(cache.isActivationBlocked(ip, 25565, fp, now.get()), "still blocked just before 1 min");
        now.addAndGet(1);
        assertFalse(cache.isActivationBlocked(ip, 25565, fp, now.get()), "allowed again after 1 min");

        // A second consecutive activation failure doubles the window, like §6.
        cache.recordActivationFailure(ip, 25565, fp);
        now.addAndGet(MIN * 2 - 1);
        assertTrue(cache.isActivationBlocked(ip, 25565, fp, now.get()));
        now.addAndGet(1);
        assertFalse(cache.isActivationBlocked(ip, 25565, fp, now.get()));

        // Independent keys, like the handshake entries.
        assertFalse(cache.isActivationBlocked(ip, 25566, fp, now.get()), "other port");
        assertFalse(cache.isActivationBlocked(InetAddress.getByName("192.0.2.11"), 25565, fp, now.get()),
                "other address");
        assertFalse(cache.isActivationBlocked(ip, 25565, ServerIdentity.generate().fingerprint(), now.get()),
                "rotated certificate");
    }

    /**
     * The activation state is persisted next to the handshake entries, and a file written before it
     * existed (handshake entries only) still loads: adding the state must not break the old format.
     */
    @Test
    void activationFailuresSurviveARestartAndTheOldFormatStillLoads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("quic-failures.properties");
        AtomicLong now = new AtomicLong(5_000_000);
        FailureCache cache = FailureCache.load(file, now::get);
        cache.recordFailure(ip, 25565, fp);
        cache.recordActivationFailure(ip, 25566, fp);

        FailureCache reloaded = FailureCache.load(file, now::get);
        assertFalse(reloaded.allows(ip, 25565, fp), "the handshake entry survives");
        assertTrue(reloaded.isActivationBlocked(ip, 25566, fp, now.get()),
                "the activation entry survives");
        assertTrue(reloaded.allows(ip, 25566, fp), "and it is not also a handshake entry");

        // A file written before activation failures existed: one plain property, no prefix.
        Path old = dir.resolve("old.properties");
        String key = FailureCache.key(ip, 25565, fp).replace(":", "\\:");
        Files.write(old, (key + "=2," + (now.get() + MIN) + "\n").getBytes(StandardCharsets.ISO_8859_1));
        FailureCache fromOld = FailureCache.load(old, now::get);
        assertFalse(fromOld.allows(ip, 25565, fp), "the old handshake entry is still read");
        assertFalse(fromOld.isActivationBlocked(ip, 25565, fp, now.get()),
                "and it is not mistaken for an activation failure");
    }

    @Test
    void forgettingFailuresClearsBothStates() {
        AtomicLong now = new AtomicLong(0);
        FailureCache cache = FailureCache.load(null, now::get);
        cache.recordFailure(ip, 25565, fp);
        cache.recordActivationFailure(ip, 25565, fp);
        cache.clear();
        assertTrue(cache.allows(ip, 25565, fp));
        assertFalse(cache.isActivationBlocked(ip, 25565, fp, now.get()));
    }

    @Test
    void theUnseenCounterAndItsShortestStepSurviveARestart(@TempDir Path dir) {
        Path file = dir.resolve("quic-failures.properties");
        AtomicLong now = new AtomicLong(5_000_000);
        assertFalse(FailureCache.load(file, now::get).recordUnseenPrerequisite(ip, 25565, fp),
                "one counted event writes nothing");

        now.addAndGet(5 * MIN);
        assertTrue(FailureCache.load(file, now::get).recordUnseenPrerequisite(ip, 25565, fp),
                "the second event within ten minutes pairs with the first after a restart");
        FailureCache reloaded = FailureCache.load(file, now::get);
        assertFalse(reloaded.allows(ip, 25565, fp), "the shortest-step entry is on disk");
        now.addAndGet(MIN);
        assertTrue(reloaded.allows(ip, 25565, fp), "and it is one minute long");
    }

    @Test
    void anUnseenCounterTenMinutesOldDoesNotPairAfterARestart(@TempDir Path dir) {
        Path file = dir.resolve("quic-failures.properties");
        AtomicLong now = new AtomicLong(5_000_000);
        FailureCache.load(file, now::get).recordUnseenPrerequisite(ip, 25565, fp);
        now.addAndGet(10 * MIN);
        assertFalse(FailureCache.load(file, now::get).recordUnseenPrerequisite(ip, 25565, fp),
                "ten minutes is not within the interval, so it starts a new count");
        assertTrue(FailureCache.load(file, now::get).allows(ip, 25565, fp));
    }
}
