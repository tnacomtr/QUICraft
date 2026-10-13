// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the clock floor from persisted and trusted values only (docs/updater.md 12.5).
 *
 * <p>The floor is the maximum of the trust file's {@code built} time, the {@code issued} time of
 * the current revocations document, and the {@code issued} time of every accepted manifest whose
 * release key is not revoked. It never reads a clock, so a system clock that was briefly far ahead
 * cannot raise it. A revoked key's contributions are dropped together with its {@code seq}.
 */
public final class ClockFloor {

    private ClockFloor() {
    }

    /**
     * Returns the clock floor.
     *
     * @param trustBuilt the {@code built} timestamp of the trust file
     * @param current the current revocations state; its {@code issued} is ignored when null (seed
     *     case, before any revocations document was accepted)
     * @param seqByKid the last accepted sequence number and issue time per release key kid
     * @param revokedKids kids of revoked release keys; their entries do not contribute
     */
    public static Instant of(
            Instant trustBuilt,
            StateRecords.CurrentRevocations current,
            Map<String, StateRecords.KidSeq> seqByKid,
            Set<String> revokedKids) {
        Objects.requireNonNull(trustBuilt, "trustBuilt");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(seqByKid, "seqByKid");
        Objects.requireNonNull(revokedKids, "revokedKids");

        Instant floor = trustBuilt;
        if (current.issued() != null && current.issued().isAfter(floor)) {
            floor = current.issued();
        }
        for (Map.Entry<String, StateRecords.KidSeq> e : seqByKid.entrySet()) {
            if (revokedKids.contains(e.getKey())) {
                continue;
            }
            Instant issued = e.getValue().issued();
            if (issued.isAfter(floor)) {
                floor = issued;
            }
        }
        return floor;
    }
}
