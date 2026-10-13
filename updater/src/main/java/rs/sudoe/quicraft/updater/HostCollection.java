// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The evidence gathered from the release hosts: at most one entry per host key. */
public record HostCollection(List<HostEvidence> responses) {

    public HostCollection {
        responses = List.copyOf(responses);
        Set<String> keys = new HashSet<>();
        for (HostEvidence evidence : responses) {
            if (!keys.add(evidence.host().key())) {
                throw new IllegalArgumentException("duplicate host evidence");
            }
        }
    }
}
