// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The last updater notice and which admins have already been shown it (docs/updater.md 6, Notifications).
 * Replacing the notice makes it deliverable to every admin again. Plain logic, no game classes.
 *
 * @param <K> the admin key, the player UUID in production
 */
final class AdminNotices<K> {
    private String current;
    private final Set<K> delivered = new HashSet<>();

    /** Makes {@code message} the current notice and forgets who was shown the old one. */
    synchronized void replace(String message) {
        current = message;
        delivered.clear();
    }

    /**
     * @return the current notice when {@code key} has not been shown it yet; marks it as shown
     */
    synchronized Optional<String> take(K key) {
        if (current == null || !delivered.add(key)) {
            return Optional.empty();
        }
        return Optional.of(current);
    }
}
