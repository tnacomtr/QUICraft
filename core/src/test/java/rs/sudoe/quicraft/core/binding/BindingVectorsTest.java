// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Golden vectors for key derivation, transcript and every message, computed from the fixed inputs
 * below written down here. The expected bytes were produced once and are recomputed independently
 * here with raw {@code javax.crypto} primitives (not through the production HKDF/label helpers), so
 * a change in either implementation breaks the test.
 *
 * <pre>
 * S = 000102030405060708090a0b0c0d0e0f                                   (16-byte Minecraft secret)
 * E = 101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f   (32-byte exporter)
 * F = 404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f   (32-byte fingerprint)
 * R = 606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f   (32-byte routing digest)
 * protocol_number = 775, mode = 1 (online)
 * </pre>
 */
class BindingVectorsTest {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] E = BindingTestData.hex(BindingTestData.E_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);

    private static final String K_HEX = "6fadb032f4939a3fccc24ac9301a79361ca714ad51d8b01fa706ab415fe4e1ef";
    private static final String T_HEX = "bcf30df25bace53f8650d895594046e57ef942547bac59fabb22cc584226eb89";
    private static final String TAG_OFFER = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String TAG_CLIENT_PROOF = "a568a91d7a4d0638a14efb67295f380a4a500d9564796fb5cafa13bb5f0ae435";
    private static final String TAG_SERVER_PROOF = "ab2ccbc227cc755f29bc9b94dc6d7b91fb272d94e689903a64dc6ac2ccd1f422";
    private static final String TAG_CLIENT_SWITCH = "80d48c4b476252f28f46cf1e19e6bb8d50ae84b0372d850d5be59a9ee811db85";
    private static final String TAG_SERVER_SWITCH = "f23597ad19a624631d08216f88bf4e6374a30bf0079520e1b4496d4318de8e6e";
    private static final String MSG_OFFER = "02000100000307404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String MSG_CLIENT_PROOF = "02010100000307404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + TAG_CLIENT_PROOF;
    private static final String MSG_SERVER_PROOF = "02020100000307404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + TAG_SERVER_PROOF;
    private static final String MSG_CLIENT_SWITCH = "02030100000307404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + TAG_CLIENT_SWITCH;
    private static final String MSG_SERVER_SWITCH = "02040100000307404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
            + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
            + TAG_SERVER_SWITCH;

    private static final byte[] LABEL_SALT = "quicraft binding v2 salt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] LABEL_KEY = "quicraft binding v2 key".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] LABEL_T = "quicraft/3 binding v2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] LABEL_TAG = "quicraft binding v2 message".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @Test
    void derivesTheGoldenKeyAndMatchesAnIndependentHkdf() throws Exception {
        byte[] key = BindingKeys.derive(S);
        assertArrayEquals(BindingTestData.hex(K_HEX), key);
        assertArrayEquals(independentKey(), key);
    }

    @Test
    void computesTheGoldenTranscriptAndMatchesAnIndependentDigest() throws Exception {
        byte[] transcript = BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER, BindingTestData.MODE_ONLINE, F, R);
        assertArrayEquals(BindingTestData.hex(T_HEX), transcript);
        assertArrayEquals(independentTranscript(), transcript);
    }

    @Test
    void computesTheGoldenTagsAndMatchesIndependentMacs() throws Exception {
        byte[] key = BindingKeys.derive(S);
        byte[] transcript = BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER, BindingTestData.MODE_ONLINE, F, R);
        assertEquals(TAG_OFFER, BindingTestData.hex(BindingTags.zeros()));
        assertTag(BindingKind.CLIENT_PROOF, TAG_CLIENT_PROOF, key, transcript);
        assertTag(BindingKind.SERVER_PROOF, TAG_SERVER_PROOF, key, transcript);
        assertTag(BindingKind.CLIENT_SWITCH, TAG_CLIENT_SWITCH, key, transcript);
        assertTag(BindingKind.SERVER_SWITCH, TAG_SERVER_SWITCH, key, transcript);
    }

    @Test
    void encodesTheGoldenMessages() {
        assertEquals(MSG_OFFER, BindingTestData.hex(BindingMessage
                .offer(BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R).encode()));
        assertMessage(BindingKind.CLIENT_PROOF, MSG_CLIENT_PROOF);
        assertMessage(BindingKind.SERVER_PROOF, MSG_SERVER_PROOF);
        assertMessage(BindingKind.CLIENT_SWITCH, MSG_CLIENT_SWITCH);
        assertMessage(BindingKind.SERVER_SWITCH, MSG_SERVER_SWITCH);
    }

    @Test
    void everyGoldenMessageIsExactly103Bytes() throws Exception {
        assertEquals(103, BindingMessage.ENCODED_LENGTH);
        for (String msg : Arrays.asList(MSG_OFFER, MSG_CLIENT_PROOF, MSG_SERVER_PROOF, MSG_CLIENT_SWITCH, MSG_SERVER_SWITCH)) {
            assertArrayEquals(BindingTestData.hex(msg), BindingMessage.decode(BindingTestData.hex(msg)).encode());
        }
    }

    private static void assertTag(BindingKind kind, String expectedHex, byte[] key, byte[] transcript) throws Exception {
        byte[] tag = BindingTags.tag(key, kind, transcript, E);
        assertArrayEquals(BindingTestData.hex(expectedHex), tag);
        assertArrayEquals(independentTag(key, kind, transcript), tag);
    }

    private static void assertMessage(BindingKind kind, String expectedHex) {
        byte[] expected = BindingTestData.hex(expectedHex);
        byte[] tag = Arrays.copyOfRange(expected, 71, 103);
        BindingMessage message = BindingMessage.of(kind, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R, tag);
        assertEquals(expectedHex, BindingTestData.hex(message.encode()));
    }

    // Independent reimplementations, deliberately not calling the production helpers.

    private static byte[] independentKey() throws Exception {
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(LABEL_SALT);
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extract.doFinal(S);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(LABEL_KEY);
        expand.update((byte) 1);
        return expand.doFinal();
    }

    private static byte[] independentTranscript() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(LABEL_T);
        digest.update(new byte[]{0x00, 0x00, 0x03, 0x07});
        digest.update((byte) 1);
        digest.update(F);
        digest.update(R);
        return digest.digest();
    }

    private static byte[] independentTag(byte[] key, BindingKind kind, byte[] transcript) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(LABEL_TAG);
        mac.update((byte) kind.code());
        mac.update(transcript);
        mac.update(E);
        return mac.doFinal();
    }
}