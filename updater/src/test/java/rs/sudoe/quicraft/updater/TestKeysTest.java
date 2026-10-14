// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Self-test: the throwaway test seeds really produce the published RFC 8032 public keys. */
class TestKeysTest {

    private static final byte[] MESSAGE = "abc".getBytes(StandardCharsets.UTF_8);

    @Test
    void seedOneSignaturesVerifyUnderPublishedPublicKey() throws Exception {
        String sig = TestKeys.sign(TestKeys.SEED_1, MESSAGE);
        assertTrue(Ed25519.verify(MESSAGE, TestKeys.PUB_1, sig));
        assertFalse(Ed25519.verify("abd".getBytes(StandardCharsets.UTF_8), TestKeys.PUB_1, sig));
        assertEquals(TestKeys.KID_1, Hex.kid(TestKeys.PUB_1));
    }

    @Test
    void seedTwoSignaturesVerifyUnderPublishedPublicKey() throws Exception {
        String sig = TestKeys.sign(TestKeys.SEED_2, MESSAGE);
        assertTrue(Ed25519.verify(MESSAGE, TestKeys.PUB_2, sig));
        assertFalse(Ed25519.verify(MESSAGE, TestKeys.PUB_1, sig));
        assertEquals(TestKeys.KID_2, Hex.kid(TestKeys.PUB_2));
    }
}
