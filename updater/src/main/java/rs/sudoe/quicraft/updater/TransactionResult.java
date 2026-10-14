// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one transaction. The messages carry fixed text plus this process's own paths and,
 * when a backup exists, the manual rollback command.
 */
public record TransactionResult(
        TransactionOutcome outcome,
        Optional<String> txid,
        Optional<Path> stagedJar,
        Optional<Path> backup,
        List<String> messages) {

    public TransactionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(txid, "txid");
        Objects.requireNonNull(stagedJar, "stagedJar");
        Objects.requireNonNull(backup, "backup");
        messages = List.copyOf(messages);
    }

    static TransactionResult of(TransactionOutcome outcome, String... messages) {
        return new TransactionResult(outcome, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(messages));
    }
}
