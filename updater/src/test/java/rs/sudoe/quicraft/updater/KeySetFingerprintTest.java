// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Tests for {@link KeySetFingerprint}: order, roles, duplicates, malformed keys, known answer. */
class KeySetFingerprintTest {
    private static final String PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";

    private static UpdateDocuments.TrustedKey key(String pub, String... roles) throws Exception {
        return new UpdateDocuments.TrustedKey(Hex.kid(pub), pub, List.of(roles));
    }

    @Test
    void fingerprintIsIndependentOfListOrder() throws Exception {
        UpdateDocuments.TrustedKey a = key(PUB_1, "release", "revocation");
        UpdateDocuments.TrustedKey b = key(PUB_2, "release");
        assertEquals(
                KeySetFingerprint.of(List.of(a, b)),
                KeySetFingerprint.of(List.of(b, a)));
    }

    @Test
    void fingerprintChangesWhenRolesChange() throws Exception {
        String releaseOnly = KeySetFingerprint.of(List.of(key(PUB_1, "release")));
        String releaseAndRevocation =
                KeySetFingerprint.of(List.of(key(PUB_1, "release", "revocation")));
        assertNotEquals(releaseOnly, releaseAndRevocation);
    }

    @Test
    void duplicatePublicKeyIsRejected() throws Exception {
        List<UpdateDocuments.TrustedKey> keys = List.of(
                key(PUB_1, "release"),
                key(PUB_1, "revocation"));
        UpdateRejectedException e = assertThrows(
                UpdateRejectedException.class, () -> KeySetFingerprint.of(keys));
        assertEquals("duplicate key", e.getMessage());
    }

    @Test
    void uppercasePublicKeyIsRejected() throws Exception {
        UpdateDocuments.TrustedKey upper = new UpdateDocuments.TrustedKey(
                Hex.kid(PUB_1), PUB_1.toUpperCase(Locale.ROOT), List.of("release"));
        assertThrows(UpdateRejectedException.class,
                () -> KeySetFingerprint.of(List.of(upper)));
    }

    @Test
    void shortPublicKeyIsRejected() throws Exception {
        UpdateDocuments.TrustedKey short31 = new UpdateDocuments.TrustedKey(
                "0000000000000000", PUB_1.substring(0, 62), List.of("release"));
        assertThrows(UpdateRejectedException.class,
                () -> KeySetFingerprint.of(List.of(short31)));
    }

    @Test
    void nonHexPublicKeyIsRejected() throws Exception {
        UpdateDocuments.TrustedKey bad = new UpdateDocuments.TrustedKey(
                "0000000000000000", "g".repeat(64), List.of("release"));
        assertThrows(UpdateRejectedException.class,
                () -> KeySetFingerprint.of(List.of(bad)));
    }

    @Test
    void matchesIndependentlyAssembledKnownAnswer() throws Exception {
        // Sorted order: PUB_2 ("3d...") before PUB_1 ("d7..."). PUB_2 has role release (0x01);
        // PUB_1 has roles release and revocation (0x03).
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(Hex.decode(PUB_2, 32), 0, 32);
        bytes.write(0x01);
        bytes.write(Hex.decode(PUB_1, 32), 0, 32);
        bytes.write(0x03);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        String expected = Hex.encode(digest);

        String actual = KeySetFingerprint.of(List.of(
                key(PUB_1, "release", "revocation"),
                key(PUB_2, "release")));
        assertEquals(expected, actual);
        assertEquals(64, actual.length());
    }
}
