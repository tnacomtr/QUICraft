// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The production clock feeds instants that state.json and the journal store, and their encoder accepts
 * whole seconds only ({@link CanonicalJson#timestamp}). A sub-second clock made every journal write and
 * every torn-host record throw.
 */
class UpdaterRuntimeSystemClockTest {
    @Test
    void theProductionClockTicksInWholeSeconds() {
        UpdaterRuntime.Seams seams = UpdaterRuntime.Seams.system();
        for (int i = 0; i < 5; i++) {
            Instant now = seams.clock.instant();
            assertEquals(0, now.getNano(), "sub-second instant " + now);
            CanonicalJson.timestamp(now);
            Duration skew = Duration.between(now, Instant.now()).abs();
            assertTrue(skew.compareTo(Duration.ofSeconds(2)) < 0, "clock is off by " + skew);
        }
    }

    @Test
    void aJournalCreatedByTheProductionClockEncodes() throws Exception {
        Instant created = UpdaterRuntime.Seams.system().clock.instant();
        JournalRecord record = new JournalRecord("0123456789abcdef0123456789abcdef", JournalPhase.PREPARED,
                "velocity", 1,
                new JournalLive("/srv/plugins/quicraft.jar", 1, "a".repeat(128), UpdateVersion.parse("0.1.0")),
                new JournalStaged(1, "b".repeat(128), UpdateVersion.parse("0.1.1")), "a".repeat(128), created, 0);
        JournalCodec.encode(record);
    }
}
