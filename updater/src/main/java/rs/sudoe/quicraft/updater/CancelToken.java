// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** A read-only view of a cancellation request. Checked between steps and between download chunks. */
public interface CancelToken {

    /**
     * Reports whether cancellation was requested.
     *
     * @return {@code true} once cancellation has been requested; never reverts
     */
    boolean isCancelled();
}
