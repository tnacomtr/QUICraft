// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The platform's log sink. Adapters map each level to their own logger. */
public interface UpdaterLog {

    /** @param message a fixed or already-sanitised line */
    void debug(String message);

    /** @param message a fixed or already-sanitised line */
    void info(String message);

    /** @param message a fixed or already-sanitised line */
    void warn(String message);
}
