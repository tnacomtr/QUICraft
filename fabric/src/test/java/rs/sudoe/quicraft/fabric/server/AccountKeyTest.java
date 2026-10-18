// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * docs/protocol.md 13.12 account key on the server (ADJUST 3): the authenticated profile UUID for an
 * authenticated login, the player name for an offline one. The key is spoofable offline by design of the spec.
 */
class AccountKeyTest {
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void anAuthenticatedLoginKeysByUuid() {
        assertEquals(ID.toString(), V3Join.accountKey(new GameProfile(ID, "Alice"), true));
    }

    @Test
    void anOfflineLoginKeysByName() {
        assertEquals("Alice", V3Join.accountKey(new GameProfile(ID, "Alice"), false));
    }

    @Test
    void noProfileOrNoUsableNameGivesNoKey() {
        assertNull(V3Join.accountKey(null, true));
        assertNull(V3Join.accountKey(null, false));
        assertNull(V3Join.accountKey(new GameProfile(ID, ""), false), "an empty name is no identity offline");
    }
}
