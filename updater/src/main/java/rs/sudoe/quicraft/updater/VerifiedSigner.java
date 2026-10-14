// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;
import java.util.Set;

/**
 * One signer whose signature verified over an exact document under a usable trusted key.
 *
 * <p>The roles are the roles that the trust snapshot grants to {@code pub} at verification time.
 * Only AuthenticatedDocuments and SignatureVerifier create instances.
 */
public final class VerifiedSigner {

    private final String kid;
    private final String pub;
    private final Set<KeyRole> roles;

    VerifiedSigner(String kid, String pub, Set<KeyRole> roles) {
        this.kid = Objects.requireNonNull(kid, "kid");
        this.pub = Objects.requireNonNull(pub, "pub");
        this.roles = Set.copyOf(roles);
    }

    /** The key id of the signer. */
    public String kid() {
        return kid;
    }

    /** The raw public key (64 lowercase hex). */
    public String pub() {
        return pub;
    }

    /** The roles granted to {@link #pub()} at verification time. */
    public Set<KeyRole> roles() {
        return roles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VerifiedSigner other)) {
            return false;
        }
        return kid.equals(other.kid) && pub.equals(other.pub) && roles.equals(other.roles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kid, pub, roles);
    }

    @Override
    public String toString() {
        return "VerifiedSigner[kid=" + kid + ", pub=" + pub + ", roles=" + roles + "]";
    }
}
