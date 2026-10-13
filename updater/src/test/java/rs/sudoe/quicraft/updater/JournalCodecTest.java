// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JournalCodecTest {

    private static final String TXID = "0123456789abcdef0123456789abcdef";
    private static final String HEX_A = "ab".repeat(64);
    private static final String HEX_C = "cd".repeat(64);

    private static JournalRecord sample() throws UpdateRejectedException {
        return new JournalRecord(TXID, JournalPhase.PREPARED, "fabric-26x", 42,
                new JournalLive("/srv/mc/mods/quicraft.jar", 4_100_000L, HEX_A,
                        UpdateVersion.parse("0.3.1")),
                new JournalStaged(4_194_304L, HEX_C, UpdateVersion.parse("0.4.0")),
                HEX_A, Instant.parse("2026-11-02T09:00:00Z"), 0);
    }

    private static String json(JournalRecord r) throws UpdateRejectedException {
        return new String(JournalCodec.encode(r), StandardCharsets.UTF_8);
    }

    /** Replaces exactly one occurrence, so a mutation test never silently does nothing. */
    private static byte[] mutate(String canonical, String from, String to) {
        int first = canonical.indexOf(from);
        assertTrue(first >= 0, "fixture lacks " + from);
        assertEquals(-1, canonical.indexOf(from, first + 1), "fixture repeats " + from);
        return canonical.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripPreservesEveryField() throws Exception {
        JournalRecord r = sample();
        assertEquals(r, JournalCodec.decode(JournalCodec.encode(r)));
    }

    @Test
    void encodingIsDeterministicAndStableAcrossDecodeEncode() throws Exception {
        JournalRecord r = sample();
        byte[] first = JournalCodec.encode(r);
        assertArrayEquals(first, JournalCodec.encode(r));
        assertArrayEquals(first, JournalCodec.encode(JournalCodec.decode(first)));
        String text = new String(first, StandardCharsets.UTF_8);
        assertEquals(-1, text.indexOf(' '), "canonical form has no insignificant spaces");
    }

    @Test
    void phasesRoundTripWithTheirWireNames() throws Exception {
        for (JournalPhase p : JournalPhase.values()) {
            JournalRecord r = sample().withPhase(p);
            assertEquals(p, JournalCodec.decode(JournalCodec.encode(r)).phase());
        }
        assertEquals("boot-pending", JournalPhase.BOOT_PENDING.wire());
        assertEquals("recovery-required", JournalPhase.RECOVERY_REQUIRED.wire());
    }

    @Test
    void bootAttemptsRoundTripAndRange() throws Exception {
        assertEquals(9, JournalCodec.decode(JournalCodec.encode(sample().withBootAttempts(9)))
                .bootAttempts());
        assertThrows(IllegalArgumentException.class, () -> sample().withBootAttempts(10));
        assertThrows(IllegalArgumentException.class, () -> sample().withBootAttempts(-1));
    }

    @Test
    void rejectsUnknownFieldsAndMissingFields() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"type\"", "\"extra\":1,\"type\""));
        assertRejected(mutate(c, "\"created\":\"2026-11-02T09:00:00Z\",", ""));
    }

    @Test
    void rejectsWrongHeader() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "quicraft.journal", "quicraft.state"));
        assertRejected(mutate(c, "\"v\":1", "\"v\":2"));
    }

    @Test
    void rejectsBadTxidPhasePlatformAndSeq() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, TXID, "0123456789ABCDEF0123456789ABCDEF"));
        assertRejected(mutate(c, TXID, "0123"));
        assertRejected(mutate(c, "\"phase\":\"prepared\"", "\"phase\":\"done\""));
        assertRejected(mutate(c, "\"platform\":\"fabric-26x\"", "\"platform\":\"linux\""));
        assertRejected(mutate(c, "\"manifestSeq\":42", "\"manifestSeq\":-1"));
    }

    @Test
    void rejectsBootAttemptsOutOfRange() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"bootAttempts\":0", "\"bootAttempts\":10"));
        assertRejected(mutate(c, "\"bootAttempts\":0", "\"bootAttempts\":\"0\""));
    }

    @Test
    void rejectsRelativeOrOverlongLivePath() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"path\":\"/srv", "\"path\":\"srv"));
        String longPath = "/" + "é".repeat(4095);
        assertRejected(mutate(c, "/srv/mc/mods/quicraft.jar", longPath));
    }

    @Test
    void rejectsBackupHashThatDiffersFromLiveHash() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"backupSha512\":\"" + HEX_A + "\"",
                "\"backupSha512\":\"" + HEX_C + "\""));
    }

    @Test
    void rejectsUppercaseOrShortHashes() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"sha512\":\"" + HEX_C + "\"",
                "\"sha512\":\"" + HEX_C.toUpperCase() + "\""));
        assertRejected(mutate(c, "\"sha512\":\"" + HEX_C + "\"", "\"sha512\":\"cd\""));
    }

    @Test
    void rejectsNegativeSizesAndBadVersions() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "\"size\":4194304", "\"size\":-1"));
        assertRejected(mutate(c, "\"version\":\"0.4.0\"", "\"version\":\"0.4\""));
    }

    @Test
    void rejectsOversizeDocument() throws Exception {
        // A 4096-character path of two-byte characters pushes the document past the 8192-byte cap.
        String longPath = "/" + "é".repeat(4095);
        JournalRecord big = new JournalRecord(TXID, JournalPhase.PREPARED, "fabric-26x", 42,
                new JournalLive(longPath, 1L, HEX_A, UpdateVersion.parse("0.3.1")),
                new JournalStaged(1L, HEX_C, UpdateVersion.parse("0.4.0")), HEX_A,
                Instant.parse("2026-11-02T09:00:00Z"), 0);
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.encode(big));
        assertRejected(new byte[JournalCodec.MAX_BYTES + 1]);
    }

    @Test
    void rejectsNonCanonicalTimestamp() throws Exception {
        String c = json(sample());
        assertRejected(mutate(c, "2026-11-02T09:00:00Z", "2026-11-02 09:00:00"));
    }

    private static void assertRejected(byte[] bytes) {
        assertThrows(UpdateRejectedException.class, () -> JournalCodec.decode(bytes));
    }

    @Test
    void sizesAreCappedAtTwoToTheFiftyThreeMinusOne() throws Exception {
        long max = 9007199254740991L;
        assertEquals(max, new JournalStaged(max, HEX_C, UpdateVersion.parse("0.4.0")).size());
        assertThrows(IllegalArgumentException.class,
                () -> new JournalStaged(max + 1, HEX_C, UpdateVersion.parse("0.4.0")));
        assertThrows(IllegalArgumentException.class,
                () -> new JournalLive("/a/b.jar", max + 1, HEX_A, UpdateVersion.parse("0.3.1")));
        String c = json(sample());
        assertRejected(mutate(c, "\"size\":4100000", "\"size\":9007199254740992"));
        assertRejected(mutate(c, "\"size\":4194304", "\"size\":9007199254740992"));
        assertEquals(max, JournalCodec.decode(mutate(c, "\"size\":4194304", "\"size\":9007199254740991"))
                .staged().size());
    }
}
