// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.connect.V3Plan;

/**
 * What the player is told (docs/protocol.md 12.14, 12.16): the user's exact fallback wording, once,
 * at the time the join reaches play.
 */
class V3NoticesTest {
    @BeforeEach
    @AfterEach
    void reset() {
        V3Notices.clear();
    }

    @Test
    void theFallbackLineIsTheUsersWordingAndComesFromThePlan() {
        String line = V3Notices.fellBack("channel binding failed");

        assertEquals("QUIC multi-stream failed (channel binding failed), fell back to TCP", line);
        assertEquals(V3Plan.fallbackMessage("channel binding failed"), line);
    }

    @Test
    void theNoticeIsHeldForThePlayAndTakenOnce() {
        V3Notices.fellBack(V3Plan.HANDSHAKE_TIMED_OUT);

        List<V3Notices.Notice> shown = V3Notices.drain();

        assertEquals(1, shown.size());
        assertEquals("QUIC multi-stream failed (handshake timed out), fell back to TCP", shown.get(0).literal());
        assertTrue(V3Notices.drain().isEmpty(), "a second join reaching play shows nothing");
        assertTrue(V3Notices.isEmpty());
    }

    @Test
    void aMessageTheConnectorBuiltIsShownAsIs() {
        String message = V3Plan.fallbackMessage("UDP is unreachable");

        V3Notices.fellBackWith(message);

        assertEquals(message, V3Notices.drain().get(0).literal());
    }

    @Test
    void aReboundCertificateNoticeNamesBothFingerprints() {
        V3Notices.rebound("aaaa", "bbbb");

        V3Notices.Notice notice = V3Notices.drain().get(0);

        assertEquals("quicraft.notice.rebind", notice.key());
        assertEquals(List.of("aaaa", "bbbb"), List.of(notice.args()));
    }

    @Test
    void anOverlongQueueKeepsTheFirstEight() {
        for (int i = 0; i < 20; i++) {
            V3Notices.fellBack("reason " + i);
        }

        assertEquals(8, V3Notices.drain().size());
    }

    @Test
    void aFingerprintIsShortenedForTheChat() {
        assertEquals("0123456789abcdef...", V3Client.shortFingerprint("sha256:0123456789abcdef0123456789abcdef"));
        assertEquals("abcd", V3Client.shortFingerprint("abcd"));
    }
}
