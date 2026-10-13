// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Maps the wire role names of a {@link UpdateDocuments.TrustedKey} to {@link KeyRole} values. */
public final class TrustedKeyRoles {

    private TrustedKeyRoles() {
    }

    /**
     * Returns the unmodifiable set of roles named by {@code key.roles()}.
     *
     * @throws IllegalArgumentException if a role name is unknown (the parser already rejects these)
     */
    public static Set<KeyRole> of(UpdateDocuments.TrustedKey key) {
        EnumSet<KeyRole> roles = EnumSet.noneOf(KeyRole.class);
        for (String name : key.roles()) {
            roles.add(fromWireName(name));
        }
        return Collections.unmodifiableSet(roles);
    }

    private static KeyRole fromWireName(String name) {
        for (KeyRole role : KeyRole.values()) {
            if (role.wireName().equals(name)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown role");
    }
}
