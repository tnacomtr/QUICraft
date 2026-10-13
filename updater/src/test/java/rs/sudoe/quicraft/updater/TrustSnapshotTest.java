// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link TrustSnapshot}: revocation, usability, kid mapping, immutability. */
class TrustSnapshotTest {
    private static final String PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String PUB_3 =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String UNKNOWN = "ff".repeat(32);

    /** PUB_1 is the revocation key (also release); PUB_2 and PUB_3 are release-only. */
    private static UpdateDocuments.Trust trust() throws Exception {
        return new UpdateDocuments.Trust(
                1,
                Instant.parse("2026-10-01T00:00:00Z"),
                0L,
                0L,
                List.of(
                        new UpdateDocuments.TrustedKey(
                                Hex.kid(PUB_1), PUB_1, List.of("release", "revocation")),
                        new UpdateDocuments.TrustedKey(
                                Hex.kid(PUB_2), PUB_2, List.of("release")),
                        new UpdateDocuments.TrustedKey(
                                Hex.kid(PUB_3), PUB_3, List.of("release"))));
    }

    @Test
    void revokedKeyIsNeverUsableAndLeavesRoleSets() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of(PUB_2));

        assertFalse(snapshot.usable(PUB_2));
        assertFalse(snapshot.releasePubs().contains(PUB_2));
        assertFalse(snapshot.revocationPubs().contains(PUB_2));
        assertTrue(snapshot.usable(PUB_3));
        assertEquals(Set.of(PUB_1, PUB_3), snapshot.releasePubs());
        assertEquals(Set.of(PUB_1), snapshot.revocationPubs());
    }

    @Test
    void revokedRevocationKeyDropsOutOfRevocationPubs() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of(PUB_1));
        assertFalse(snapshot.usable(PUB_1));
        assertTrue(snapshot.revocationPubs().isEmpty());
        assertFalse(snapshot.releasePubs().contains(PUB_1));
    }

    @Test
    void unknownKeyIsNotUsable() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of());
        assertFalse(snapshot.usable(UNKNOWN));
        assertFalse(snapshot.usable("00".repeat(32)));
        assertTrue(snapshot.usable(PUB_1));
    }

    @Test
    void pubByKidMapsParserStyleKids() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of());
        assertEquals(PUB_1, snapshot.pubByKid().get(Hex.kid(PUB_1)));
        assertEquals(PUB_2, snapshot.pubByKid().get(Hex.kid(PUB_2)));
        assertEquals(PUB_3, snapshot.pubByKid().get(Hex.kid(PUB_3)));
        assertEquals(3, snapshot.pubByKid().size());
    }

    @Test
    void epochAndFingerprintArePopulated() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of());
        assertEquals(1, snapshot.epoch());
        assertEquals(64, snapshot.keySetFingerprint().length());
        assertEquals(KeySetFingerprint.of(trust().keys()), snapshot.keySetFingerprint());
    }

    @Test
    void collectionsAreUnmodifiable() throws Exception {
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), Set.of(PUB_2));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.revokedPubs().add(UNKNOWN));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.pubByKid().put("x", UNKNOWN));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.rolesByPub().put(UNKNOWN, Set.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.rolesByPub().get(PUB_1).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.releasePubs().add(UNKNOWN));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.revocationPubs().add(UNKNOWN));
    }

    @Test
    void persistedRevocationSetIsCopiedDefensively() throws Exception {
        Set<String> persisted = new HashSet<>();
        TrustSnapshot snapshot = TrustSnapshot.of(trust(), persisted);
        persisted.add(PUB_3);
        assertTrue(snapshot.usable(PUB_3));
        assertFalse(snapshot.revokedPubs().contains(PUB_3));
    }

    @Test
    void duplicateKidIsRejected() throws Exception {
        UpdateDocuments.Trust bad = new UpdateDocuments.Trust(
                1,
                Instant.parse("2026-10-01T00:00:00Z"),
                0L,
                0L,
                List.of(
                        new UpdateDocuments.TrustedKey(Hex.kid(PUB_1), PUB_1, List.of("release")),
                        new UpdateDocuments.TrustedKey(Hex.kid(PUB_1), PUB_2, List.of("release"))));
        assertThrows(UpdateRejectedException.class, () -> TrustSnapshot.of(bad, Set.of()));
    }
}
