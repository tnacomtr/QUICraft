// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** docs/protocol.md 12.13: the validated-attempt budget shares an exhausted window by prefix. */
class ConnectionAdmissionFairTest {
    private static ConnectionAdmission admission(int globalPerSecond, long[] clock) {
        return new ConnectionAdmission(globalPerSecond, 1000, 1000, 1000, globalPerSecond, 1000, () -> clock[0]);
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void anExhaustedWindowAdmitsAnHonestPrefixByTakingFromAnOverSharePrefix() throws Exception {
        long[] clock = {1};
        ConnectionAdmission validated = admission(10, clock);
        for (int i = 0; i < 10; i++) {
            assertTrue(validated.allowFairAttempt(ip("192.0.2.10")), "the attacker takes the whole window");
        }
        // Two prefixes now: the honest one is within its share of 10 / 2 = 5 and gets in.
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertFalse(validated.allowFairAttempt(ip("198.51.100.5")), "the honest prefix is at its share of five");
    }

    @Test
    void theOverSharePrefixIsRefusedWhileTheWindowIsExhausted() throws Exception {
        long[] clock = {1};
        ConnectionAdmission validated = admission(10, clock);
        for (int i = 0; i < 10; i++) {
            validated.allowFairAttempt(ip("192.0.2.10"));
        }
        assertTrue(validated.allowFairAttempt(ip("198.51.100.5")));
        assertFalse(validated.allowFairAttempt(ip("192.0.2.10")), "the attacker is over its share");
    }

    @Test
    void theTokenLessBudgetKeepsItsPlainLimit() throws Exception {
        long[] clock = {1};
        ConnectionAdmission admission = admission(10, clock);
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.allowAttempt(ip("192.0.2.10")));
        }
        assertFalse(admission.allowAttempt(ip("198.51.100.5")), "no fair transfer for token-less attempts");
    }

    @Test
    void aNewWindowResetsTheFairShares() throws Exception {
        long[] clock = {1};
        ConnectionAdmission validated = admission(10, clock);
        for (int i = 0; i < 10; i++) {
            validated.allowFairAttempt(ip("192.0.2.10"));
        }
        clock[0] += 1_000_000_000L;
        assertTrue(validated.allowFairAttempt(ip("192.0.2.10")));
    }

    @Test
    void theCoarsePrefixIsSlash48ForIpv6AndSlash24ForIpv4() throws Exception {
        assertTrue(PreLoginRegistry.coarsePrefix(ip("2001:db8:1:1::1"))
                .equals(PreLoginRegistry.coarsePrefix(ip("2001:db8:1:ffff::9"))));
        assertFalse(PreLoginRegistry.coarsePrefix(ip("2001:db8:1::1"))
                .equals(PreLoginRegistry.coarsePrefix(ip("2001:db8:2::1"))));
        assertTrue(PreLoginRegistry.coarsePrefix(ip("192.0.2.10")).equals(PreLoginRegistry.coarsePrefix(ip("192.0.2.200"))));
        assertFalse(PreLoginRegistry.coarsePrefix(ip("192.0.2.10")).equals(PreLoginRegistry.coarsePrefix(ip("192.0.3.10"))));
    }
}
