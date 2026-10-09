// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Transport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class ClientSideDecisionTest {
    @Test
    void decidesPerProtocolSection4() throws Exception {
        Optional<Advertisement> ad = Optional.of(Advertisement.v1(25565, ServerIdentity.generate().fingerprint()));
        Optional<Advertisement> none = Optional.empty();
        assertEquals(Transport.RACE, ConnectDecision.decide(Mode.AUTO, true, ad, true));
        assertEquals(Transport.TCP, ConnectDecision.decide(Mode.AUTO, false, ad, true), "no native");
        assertEquals(Transport.TCP, ConnectDecision.decide(Mode.AUTO, true, none, true), "no advertisement");
        assertEquals(Transport.TCP, ConnectDecision.decide(Mode.AUTO, true, ad, false), "backing off");
        assertEquals(Transport.TCP, ConnectDecision.decide(Mode.TCP_ONLY, true, ad, true));
        assertEquals(Transport.QUIC_ONLY, ConnectDecision.decide(Mode.QUIC_ONLY, true, none, false));
    }

    @Test
    void fallbackReportRoundTripsAndRejectsJunk() {
        for (FallbackReason r : FallbackReason.values()) {
            assertEquals(Optional.of(r), FallbackReport.decode(FallbackReport.encode(r)));
        }
        assertArrayEquals(new byte[] {1, 3}, FallbackReport.encode(FallbackReason.FINGERPRINT_MISMATCH));
        assertFalse(FallbackReport.decode(new byte[] {2, 1}).isPresent(), "unknown version");
        assertFalse(FallbackReport.decode(new byte[] {1}).isPresent(), "truncated");
        assertFalse(FallbackReport.decode(new byte[] {1, 1, 0}).isPresent(), "trailing bytes");
        assertFalse(FallbackReport.decode(new byte[] {(byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0})
                .isPresent(), "overlong VarInt");
        assertFalse(FallbackReport.decode(new byte[64]).isPresent(), "oversized");
        assertFalse(FallbackReport.decode(null).isPresent());
        assertEquals(Optional.of(FallbackReason.OTHER), FallbackReport.decode(new byte[] {1, 99}), "unknown reason");
    }

    @Test
    void serverLogsAtMostOneHintPerReasonPerTenMinutes() {
        AtomicLong now = new AtomicLong(0);
        FallbackReport.Log log = new FallbackReport.Log(25565, now::get);
        byte[] timeout = FallbackReport.encode(FallbackReason.TIMEOUT_OR_LOST_RACE);
        assertTrue(log.report(timeout));
        for (int i = 0; i < 1000; i++) {
            assertFalse(log.report(timeout));
        }
        assertTrue(log.report(FallbackReport.encode(FallbackReason.FINGERPRINT_MISMATCH)), "separate per reason");
        assertFalse(log.report(new byte[] {9, 9}), "junk is ignored");
        now.addAndGet(FallbackReport.Log.INTERVAL_MILLIS);
        assertTrue(log.report(timeout));
    }
}
