// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.List;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;

/**
 * The binding exchange's quiescence queue (docs/protocol.md 12.10), charged at real encoded sizes and
 * reserved against the node's budget (13.12) while units wait in it.
 */
final class OutboundQuiescence {
    private OutboundQuiescence() {}

    /**
     * Offers one write's encoded output to the driver. The unit's real size counts against the 8 MiB
     * bound and is reserved against the node budget for as long as it is queued; anything but
     * {@code QUEUED} leaves nothing reserved.
     */
    static BindingDriver.QueueResult offer(BindingDriver driver, OutboundUnit unit, ReceiveBudget node) {
        if (!unit.reserve(node)) {
            throw new IllegalStateException("the node budget refused " + unit.bytes() + " queued bytes (13.12)");
        }
        BindingDriver.QueueResult result = driver.queueOrdinary(unit, (int) Math.min(unit.bytes(), Integer.MAX_VALUE));
        if (result != BindingDriver.QueueResult.QUEUED) {
            unit.release();
        }
        return result;
    }

    /** Gives back the reservation of every unit still queued (teardown and failure paths). */
    static void discard(List<Object> queued) {
        for (Object unit : queued) {
            ((OutboundUnit) unit).release();
        }
    }
}
