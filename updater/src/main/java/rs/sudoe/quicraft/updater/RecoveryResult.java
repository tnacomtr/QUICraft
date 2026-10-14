// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@link StartupRecovery#run}. {@code messages} are fixed strings, plus paths of this
 * updater's own files (the backup and the manual restore command) where the spec allows them.
 * The caller logs every start that reports {@link RecoveryStatus#RECOVERY_REQUIRED}.
 */
public record RecoveryResult(RecoveryStatus status, Optional<RecoveryTicket> ticket,
        Optional<LiveJar> live, List<String> messages) {

    public RecoveryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(live, "live");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if ((status == RecoveryStatus.HEALTH_PENDING) != ticket.isPresent()) {
            throw new IllegalArgumentException("a ticket belongs to HEALTH_PENDING only");
        }
    }
}
