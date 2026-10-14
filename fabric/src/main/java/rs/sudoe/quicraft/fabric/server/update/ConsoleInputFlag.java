// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

/**
 * Marks the server thread while the dedicated server drains its queued console commands. The command
 * source alone cannot prove console input: a datapack function runs with the server as its source too, so
 * {@code reset-state} needs this mark as well (docs/updater.md section 3, 12.8).
 *
 * <p>Plain logic with no game classes, so it is easy to unit test later. Fail closed: the mark starts
 * unset and only the dedicated server's own console-input drain ever sets it, so a missing or failing hook
 * leaves the console unproven and {@code reset-state} refused.
 */
public final class ConsoleInputFlag {
    private static final ThreadLocal<Boolean> INSIDE_CONSOLE_INPUT = new ThreadLocal<>();

    private ConsoleInputFlag() {
    }

    /** Marks the current thread as running inside a console-input drain. */
    public static void enter() {
        INSIDE_CONSOLE_INPUT.set(Boolean.TRUE);
    }

    /** Clears the mark: a source seen after this on this thread is not console input. */
    public static void exit() {
        INSIDE_CONSOLE_INPUT.remove();
    }

    /** @return true only while this thread is inside a console-input drain */
    public static boolean isSet() {
        return Boolean.TRUE.equals(INSIDE_CONSOLE_INPUT.get());
    }
}
