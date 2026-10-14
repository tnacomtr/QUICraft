// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Receives lines while an update transaction runs, before its {@link TransactionResult} exists. Use
 * it for information that must reach the operator even if the process dies before the result is
 * printed, such as the manual rollback command logged before the swap (docs/updater.md 12.10 step 5).
 *
 * <p>A line starting with {@code WARN:} is a warning. The transaction ignores any exception the sink
 * throws, so a broken sink never changes the outcome.
 */
@FunctionalInterface
public interface TransactionProgress {

    /** A sink that drops every line. */
    TransactionProgress NONE = message -> { };

    /**
     * Receives one line.
     *
     * @param message the line, without a trailing newline
     */
    void line(String message);
}
