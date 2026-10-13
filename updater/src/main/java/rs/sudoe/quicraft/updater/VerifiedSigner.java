// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Set;

/**
 * One signer whose signature verified over an exact document under a usable trusted key.
 *
 * <p>The roles are the roles that the trust snapshot grants to {@code pub} at verification time.
 */
public record VerifiedSigner(String kid, String pub, Set<KeyRole> roles) {

    public VerifiedSigner {
        roles = Set.copyOf(roles);
    }
}
