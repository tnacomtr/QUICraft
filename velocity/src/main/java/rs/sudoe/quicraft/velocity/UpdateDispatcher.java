// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import java.util.function.Consumer;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/**
 * The one thing {@link UpdateCommand} needs from the updater: {@link UpdaterRuntime#dispatch}.
 *
 * <p>It is a seam, so the command grammar and the permission checks are testable against a recording
 * fake instead of a disabled runtime, which answers before it ever parses its arguments.
 */
@FunctionalInterface
interface UpdateDispatcher {

    /**
     * @param args the words after {@code update}; an empty array is {@code /quicraft update}
     * @param console true when the command came from the server console
     * @param out receives every line of the answer
     * @return how the command was handled
     */
    UpdaterRuntime.Reply dispatch(String[] args, boolean console, Consumer<String> out);
}
