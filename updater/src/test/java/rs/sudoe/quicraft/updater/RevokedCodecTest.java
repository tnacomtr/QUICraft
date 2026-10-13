// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;

/** Round-trip, determinism and strictness tests for the revoked.json codec. */
class RevokedCodecTest {

    private static final String PUB = "c".repeat(64);
    private static final String PUB_2 = "e".repeat(64);
    private static final String SHA512 = "d".repeat(128);

    private static RevokedState sample() throws UpdateRejectedException {
        return new RevokedState(
                List.of(new UpdateDocuments.RevokedKey(Hex.kid(PUB), PUB)),
                List.of(SHA512));
    }

    private static String doc(String keys, String blocked) {
        return "{\"type\":\"quicraft.revoked\",\"v\":1,\"revokedKeys\":" + keys
                + ",\"blockedArtifacts\":" + blocked + "}";
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripPreservesRevokedKeysAndBlockedArtifacts() throws Exception {
        RevokedState r = sample();
        assertEquals(r, RevokedCodec.decode(RevokedCodec.encode(r)));
    }

    @Test
    void emptyStateRoundTrips() throws Exception {
        byte[] bytes = RevokedCodec.encode(RevokedState.empty());
        assertArrayEquals(("{\"blockedArtifacts\":[],\"revokedKeys\":[],\"type\":\"quicraft.revoked\","
                + "\"v\":1}").getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals(RevokedState.empty(), RevokedCodec.decode(bytes));
    }

    @Test
    void encodingIsDeterministicAndStableAcrossDecode() throws Exception {
        byte[] first = RevokedCodec.encode(sample());
        assertArrayEquals(first, RevokedCodec.encode(sample()));
        assertArrayEquals(first, RevokedCodec.encode(RevokedCodec.decode(first)));
    }

    @Test
    void keysAreWrittenInSortedOrder() throws Exception {
        String json = new String(RevokedCodec.encode(sample()), StandardCharsets.UTF_8);
        int blocked = json.indexOf("\"blockedArtifacts\"");
        int keys = json.indexOf("\"revokedKeys\"");
        int type = json.indexOf("\"type\"");
        int v = json.indexOf("\"v\"");
        assertTrue(blocked < keys && keys < type && type < v, json);
        assertTrue(json.indexOf("\"kid\"") < json.indexOf("\"pub\""), json);
    }

    @Test
    void rejectsKidThatDoesNotMatchPublicKey() throws Exception {
        String kid = Hex.kid(PUB);
        String wrong = kid.equals("0".repeat(16)) ? "1".repeat(16) : "0".repeat(16);
        byte[] bytes = bytes(doc("[{\"kid\":\"" + wrong + "\",\"pub\":\"" + PUB + "\"}]", "[]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsUnknownKeyInsideRevokedKeyEntry() throws Exception {
        String entry = "{\"kid\":\"" + Hex.kid(PUB) + "\",\"pub\":\"" + PUB + "\",\"x\":1}";
        byte[] bytes = bytes(doc("[" + entry + "]", "[]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsUnknownAndMissingTopLevelKeys() {
        String extra = doc("[]", "[]").replace("\"v\":1", "\"v\":1,\"extra\":true");
        String missing = doc("[]", "[]").replace(",\"blockedArtifacts\":[]", "");
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes(extra)));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes(missing)));
    }

    @Test
    void rejectsStateTypeOfTheOtherFile() {
        String wrong = doc("[]", "[]").replace("quicraft.revoked", "quicraft.updater-state");
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes(wrong)));
    }

    @Test
    void rejectsWrongVersion() {
        String wrong = doc("[]", "[]").replace("\"v\":1", "\"v\":3");
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes(wrong)));
    }

    @Test
    void rejectsDuplicateRevokedPub() {
        String entry = "{\"kid\":\"" + kidOf(PUB) + "\",\"pub\":\"" + PUB + "\"}";
        byte[] bytes = bytes(doc("[" + entry + "," + entry + "]", "[]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsOverCapRevokedKeys() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            String pub = String.format("%064x", i + 1);
            entries.add("{\"kid\":\"" + kidOf(pub) + "\",\"pub\":\"" + pub + "\"}");
        }
        byte[] bytes = bytes(doc("[" + String.join(",", entries) + "]", "[]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsOverCapBlockedArtifacts() {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            hashes.add("\"" + String.format("%0128x", i + 1) + "\"");
        }
        byte[] bytes = bytes(doc("[]", "[" + String.join(",", hashes) + "]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsDuplicateBlockedArtifacts() {
        byte[] bytes = bytes(doc("[]", "[\"" + SHA512 + "\",\"" + SHA512 + "\"]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    @Test
    void rejectsOversizedInput() {
        byte[] huge = new byte[RevokedCodec.MAX_BYTES + 1];
        Arrays.fill(huge, (byte) ' ');
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(huge));
    }

    @Test
    void encodeRejectsOverCapAsStateFull() {
        List<UpdateDocuments.RevokedKey> keys = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            String pub = String.format("%064x", i + 1);
            keys.add(new UpdateDocuments.RevokedKey(kidOf(pub), pub));
        }
        RevokedState r = new RevokedState(keys, List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RevokedCodec.encode(r));
        assertEquals("state full", e.getMessage());
    }

    @Test
    void rejectsPublicKeyOfWrongShape() {
        byte[] bytes = bytes(doc("[{\"kid\":\"0000000000000000\",\"pub\":\"" + PUB_2.toUpperCase()
                + "\"}]", "[]"));
        assertThrows(UpdateRejectedException.class, () -> RevokedCodec.decode(bytes));
    }

    private static String kidOf(String pub) {
        try {
            return Hex.kid(pub);
        } catch (UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }
}
