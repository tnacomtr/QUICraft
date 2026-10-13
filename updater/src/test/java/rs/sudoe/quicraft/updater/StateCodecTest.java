// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;

/** Round-trip, determinism and strictness tests for the state.json codec. */
class StateCodecTest {

    private static final String HEX64_A = "a".repeat(64);
    private static final String HEX64_B = "b".repeat(64);
    private static final String HEX64_C = "c".repeat(64);
    private static final String KID = "0123456789abcdef";
    private static final Instant T1 = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2027-11-01T12:00:00Z");

    private static UpdaterState full() {
        return new UpdaterState(
                1,
                HEX64_A,
                HEX64_B,
                new CurrentRevocations(3, HEX64_C, T1, T2, 0),
                Map.of(KID, new KidSeq(42, HEX64_C, T1)),
                List.of(new WithdrawnEntry("d".repeat(128), version("0.4.1"), KID)),
                "0.4.0",
                List.of(HEX64_A),
                Map.of("modrinth", T1));
    }

    private static UpdaterState seed() {
        return new UpdaterState(
                2,
                HEX64_A,
                HEX64_B,
                new CurrentRevocations(0, "", null, null, 0),
                Map.of(),
                List.of(),
                "",
                List.of(),
                Map.of());
    }

    private static UpdateVersion version(String text) {
        try {
            return UpdateVersion.parse(text);
        } catch (UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A complete, valid document; the parameters replace one slot each. */
    private static String doc(String seqByKid, String withdrawn) {
        return "{\"type\":\"quicraft.updater-state\",\"v\":1,\"epoch\":1,"
                + "\"keySetFingerprint\":\"" + HEX64_A + "\","
                + "\"revocationKey\":\"" + HEX64_B + "\","
                + "\"revocations\":{\"seq\":0,\"sha256\":\"\",\"issued\":\"\",\"expires\":\"\","
                + "\"minManifestSeq\":0},"
                + "\"seqByKid\":" + seqByKid + ","
                + "\"withdrawn\":" + withdrawn + ","
                + "\"lastAnnounced\":\"\",\"warnedBad\":[],\"tornSince\":{}}";
    }

    @Test
    void roundTripPreservesEveryField() throws Exception {
        UpdaterState s = full();
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)));
    }

    @Test
    void seedFormRoundTripsWithEmptyStrings() throws Exception {
        UpdaterState s = seed();
        byte[] bytes = StateCodec.encode(s);
        String json = text(bytes);
        assertTrue(json.contains("\"expires\":\"\",\"issued\":\"\""), json);
        assertTrue(json.contains("\"sha256\":\"\""), json);
        assertEquals(s, StateCodec.decode(bytes));
    }

    @Test
    void encodingIsDeterministicAndStableAcrossDecode() throws Exception {
        byte[] first = StateCodec.encode(full());
        assertArrayEquals(first, StateCodec.encode(full()));
        assertArrayEquals(first, StateCodec.encode(StateCodec.decode(first)));
    }

    @Test
    void keysAreWrittenInSortedOrder() {
        String json = text(StateCodec.encode(full()));
        String[] order = {"\"epoch\"", "\"keySetFingerprint\"", "\"lastAnnounced\"",
            "\"revocationKey\"", "\"revocations\"", "\"seqByKid\"", "\"tornSince\"", "\"type\"",
            "\"v\"", "\"warnedBad\"", "\"withdrawn\""};
        int last = -1;
        for (String key : order) {
            int at = json.indexOf(key);
            assertTrue(at > last, key + " out of order in " + json);
            last = at;
        }
        assertTrue(json.startsWith("{\"epoch\":1,"), json);
        assertTrue(!json.contains(" "), "no whitespace expected");
    }

    @Test
    void rejectsUnknownKey() {
        String bad = doc("{}", "[]").replace("\"tornSince\":{}",
                "\"tornSince\":{},\"extra\":1");
        assertThrows(UpdateRejectedException.class,
                () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsMissingKey() {
        String bad = doc("{}", "[]").replace("\"lastAnnounced\":\"\",", "");
        assertThrows(UpdateRejectedException.class,
                () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsWrongValueType() {
        String bad = doc("{}", "[]").replace("\"epoch\":1", "\"epoch\":\"1\"");
        assertThrows(UpdateRejectedException.class,
                () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsWrongVersionAndType() {
        String wrongV = doc("{}", "[]").replace("\"v\":1", "\"v\":2");
        String wrongType = doc("{}", "[]").replace("quicraft.updater-state", "quicraft.revoked");
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(wrongV)));
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(wrongType)));
    }

    @Test
    void rejectsSeqByKidOverCap() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(String.format("%016x", i)).append("\":{\"seq\":1,\"sha256\":\"")
                    .append(HEX64_A).append("\",\"issued\":\"2026-11-01T12:00:00Z\"}");
        }
        sb.append('}');
        byte[] bytes = bytes(doc(sb.toString(), "[]"));
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes));
    }

    @Test
    void rejectsWithdrawnOverCap() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 257; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"sha512\":\"").append("d".repeat(128)).append("\",\"version\":\"0.4.1\","
                    + "\"kid\":\"").append(KID).append("\"}");
        }
        sb.append(']');
        byte[] bytes = bytes(doc("{}", sb.toString()));
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes));
    }

    @Test
    void rejectsWarnedBadOverCap() {
        String list = "[" + ("\"" + HEX64_A + "\",").repeat(32) + "\"" + HEX64_B + "\"]";
        String bad = doc("{}", "[]").replace("\"warnedBad\":[]", "\"warnedBad\":" + list);
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsOversizedInput() {
        byte[] huge = new byte[StateCodec.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(huge));
    }

    @Test
    void rejectsKidThatIsNotSixteenHex() {
        String bad = doc("{\"0123456789ABCDEF\":{\"seq\":1,\"sha256\":\"" + HEX64_A
                + "\",\"issued\":\"2026-11-01T12:00:00Z\"}}", "[]");
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsNonEmptyShaWithoutTimestamps() {
        String bad = doc("{}", "[]").replace("\"sha256\":\"\"", "\"sha256\":\"" + HEX64_C + "\"");
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void rejectsUnknownTornHost() {
        String bad = doc("{}", "[]").replace("\"tornSince\":{}",
                "\"tornSince\":{\"example\":\"2026-11-01T12:00:00Z\"}");
        assertThrows(UpdateRejectedException.class, () -> StateCodec.decode(bytes(bad)));
    }

    @Test
    void encodeRejectsSubSecondTimestamps() {
        UpdaterState s = new UpdaterState(1, HEX64_A, HEX64_B,
                new CurrentRevocations(0, "", null, null, 0),
                Map.of(KID, new KidSeq(1, HEX64_C, Instant.parse("2026-11-01T12:00:00.5Z"))),
                List.of(), "", List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> StateCodec.encode(s));
    }

    @Test
    void encodeRejectsOverCapStateAsFull() {
        Map<String, KidSeq> many = new TreeMap<>();
        for (int i = 0; i < 33; i++) {
            many.put(String.format("%016x", i), new KidSeq(1, HEX64_C, T1));
        }
        UpdaterState s = new UpdaterState(1, HEX64_A, HEX64_B,
                new CurrentRevocations(0, "", null, null, 0), many, List.of(), "", List.of(),
                Map.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> StateCodec.encode(s));
        assertEquals("state full", e.getMessage());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
