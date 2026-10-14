// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The evidence gathered from the release hosts: at most one entry per host key and at most one per
 * normalized origin.
 *
 * <p>Acceptance must require that each evidence host equals (key and origin) a configured
 * {@link ReleaseHosts} entry, checked with {@link ReleaseHosts#contains(ReleaseHost)}. Coverage
 * counts configured keys only. A redirect target is never a coverage vote.
 */
public record HostCollection(List<HostEvidence> responses) {

    public HostCollection {
        responses = List.copyOf(responses);
        Set<String> keys = new HashSet<>();
        Set<String> origins = new HashSet<>();
        for (HostEvidence evidence : responses) {
            if (!keys.add(evidence.host().key())) {
                throw new IllegalArgumentException("duplicate host evidence");
            }
            if (!origins.add(evidence.host().origin().toString())) {
                throw new IllegalArgumentException("duplicate host origin evidence");
            }
        }
    }
}
