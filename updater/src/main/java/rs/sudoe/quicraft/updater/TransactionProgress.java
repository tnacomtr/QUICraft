// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Receives lines while an update transaction runs, before its {@link TransactionResult} exists. Use
 * it for information that must reach the operator even if the process dies before the result is
 * printed, such as the manual rollback command logged before the swap (docs/updater.md 12.10 step 5).
 *
 * <p>A line starting with {@code WARN:} is a warning. The rollback line is mandatory: if the sink
 * throws while it is emitted, the transaction installs nothing. The transaction ignores failures of
 * any other line.
 */
@FunctionalInterface
public interface TransactionProgress {

    /**
     * Receives one line.
     *
     * @param message the line, without a trailing newline
     */
    void line(String message);
}
