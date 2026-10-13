// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** Reports whether the server has finished initialization, so staging may start. */
public interface InitBarrier {

    /** @return {@code true} once initialization has completed and staging is allowed */
    boolean readyForStaging();
}
