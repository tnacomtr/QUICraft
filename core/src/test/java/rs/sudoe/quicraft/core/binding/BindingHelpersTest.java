// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Input validation of the pure helpers of docs/protocol.md §12.6, §12.7 and §12.8: a mode is 0
 * (offline) or 1 (online), Minecraft's shared secret {@code S} is exactly 16 raw bytes, and no
 * helper silently works from a malformed input.
 */
class BindingHelpersTest {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);

    @Test
    void transcriptAcceptsOnlyModeZeroOrOne() {
        assertNotNull(BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER, 0, F, R));
        assertNotNull(BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER, 1, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingTranscript
                .compute(BindingTestData.PROTOCOL_NUMBER, 2, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingTranscript
                .compute(BindingTestData.PROTOCOL_NUMBER, -1, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingTranscript
                .compute(BindingTestData.PROTOCOL_NUMBER, 255, F, R));
    }

    @Test
    void transcriptStillRejectsWrongLengthDigests() {
        assertThrows(IllegalArgumentException.class, () -> BindingTranscript
                .compute(BindingTestData.PROTOCOL_NUMBER, 1, new byte[31], R));
        assertThrows(IllegalArgumentException.class, () -> BindingTranscript
                .compute(BindingTestData.PROTOCOL_NUMBER, 1, F, null));
    }

    @Test
    void keyDerivationRequiresAnExact16ByteSecret() {
        assertNotNull(BindingKeys.derive(new byte[16]));
        assertArrayEquals(BindingKeys.derive(S), BindingKeys.derive(S));
        assertThrows(IllegalArgumentException.class, () -> BindingKeys.derive(null));
        assertThrows(IllegalArgumentException.class, () -> BindingKeys.derive(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> BindingKeys.derive(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> BindingKeys.derive(new byte[17]));
    }

    @Test
    void tagRequiresAKind() {
        byte[] key = BindingKeys.derive(S);
        byte[] transcript = BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER,
                BindingTestData.MODE_ONLINE, F, R);
        assertNotNull(BindingTags.tag(key, BindingKind.CLIENT_PROOF, transcript, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> BindingTags
                .tag(key, null, transcript, new byte[32]));
    }

    @Test
    void tagStillRejectsWrongLengthInputs() {
        byte[] transcript = BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER,
                BindingTestData.MODE_ONLINE, F, R);
        assertThrows(IllegalArgumentException.class, () -> BindingTags
                .tag(new byte[31], BindingKind.CLIENT_PROOF, transcript, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> BindingTags
                .tag(new byte[32], BindingKind.CLIENT_PROOF, new byte[33], new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> BindingTags
                .tag(new byte[32], BindingKind.CLIENT_PROOF, transcript, null));
    }

    @Test
    void messageFactoriesAcceptOnlyModeZeroOrOne() {
        assertNotNull(BindingMessage.offer(0, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertNotNull(BindingMessage.offer(1, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingMessage
                .offer(2, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingMessage
                .of(BindingKind.CLIENT_PROOF, 2, BindingTestData.PROTOCOL_NUMBER, F, R, new byte[32]));
    }
}
