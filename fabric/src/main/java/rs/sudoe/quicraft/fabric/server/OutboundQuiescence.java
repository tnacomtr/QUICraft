// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import rs.sudoe.quicraft.core.binding.BindingDriver;

/** The binding exchange's quiescence queue (docs/protocol.md 12.10), charged at real encoded sizes. */
final class OutboundQuiescence {
    private OutboundQuiescence() {}

    /**
     * Offers one write's encoded output to the driver, counting its actual size against the 8 MiB bound
     * (not a per-packet guess).
     */
    static BindingDriver.QueueResult offer(BindingDriver driver, OutboundUnit unit) {
        long bytes = Math.min(unit.bytes(), Integer.MAX_VALUE);
        return driver.queueOrdinary(unit, (int) bytes);
    }
}
