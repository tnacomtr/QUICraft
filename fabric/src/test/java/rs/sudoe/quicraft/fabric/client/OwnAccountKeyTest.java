// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** docs/protocol.md 13.12 account key on the client, by the same rule as the server (ADJUST 3). */
class OwnAccountKeyTest {
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void anAuthenticatedSessionKeysByUuid() {
        assertEquals(ID.toString(), V3Client.accountKey("Alice", ID, true));
    }

    @Test
    void anOfflineSessionKeysByName() {
        assertEquals("Alice", V3Client.accountKey("Alice", ID, false));
    }

    @Test
    void noUsableIdentityGivesNoKey() {
        assertNull(V3Client.accountKey("", null, false));
        assertNull(V3Client.accountKey(null, null, true));
    }
}
