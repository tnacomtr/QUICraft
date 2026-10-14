// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import org.jspecify.annotations.Nullable;

/**
 * Holds the one command source the dedicated server's console-input drain is running right now. Being the
 * server source is not proof of console input: a datapack function runs with the server as its source too, so
 * {@code reset-state} needs the drain's mark as well (docs/updater.md section 3, 12.8).
 *
 * <p>What is marked is the exact {@code CommandSourceStack} instance the drain hands to the command, never the
 * thread that happens to run it. The server thread can run unrelated queued work while a drained command is
 * still running (a console {@code /reload} waits on the task queue inside the command), and none of that work
 * is console input. Identity is therefore the whole test: a stack the drain did not hand over is not console
 * input. An {@code /execute} modifier that copies the stack ({@code as}, {@code at}, ...) makes a new one and
 * so loses console status; a plain {@code execute run} typed at the console keeps the same stack, which is
 * correct because it is the console.
 *
 * <p>Plain logic with no game classes: the marked source is kept as an {@link Object} and compared by
 * identity, so it is easy to unit test later. Fail closed: the mark starts unset and only the dedicated
 * server's own console-input drain ever sets it, so a missing or failing hook leaves the console unproven and
 * {@code reset-state} refused.
 */
public final class ConsoleInputFlag {
    private static volatile @Nullable Object markedSource;

    private ConsoleInputFlag() {
    }

    /**
     * Marks {@code source} as the console source running now. Only the drain on the server thread writes the
     * field. Other threads may read it (a {@code requires} check can run anywhere), which is harmless: only the
     * console's own stack ever matches, and nothing else holds a reference to it.
     *
     * @return the mark this one replaced, for the caller to restore when the command returns
     */
    public static @Nullable Object mark(Object source) {
        Object previous = markedSource;
        markedSource = source;
        return previous;
    }

    /** Puts {@code previous} back as the mark; {@code null} means nothing is marked. */
    public static void restore(@Nullable Object previous) {
        markedSource = previous;
    }

    /**
     * @return true only for the exact object this holder marked; anything else, {@code null} included, is not
     *     console input
     */
    public static boolean isMarked(@Nullable Object source) {
        return source != null && source == markedSource;
    }
}
