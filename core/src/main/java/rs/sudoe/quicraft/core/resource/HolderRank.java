// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

/**
 * Current pressure score of a pool holder; higher means shed first (protocol.md 13.12 pressure
 * ranking). Read at the moment a victim is chosen, so scores never go stale between grants.
 */
public interface HolderRank {
    /** Returns the holder's worst-of pressure score; {@code 0} for a holder without history. */
    double score(Object owner);
}
