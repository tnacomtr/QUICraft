// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import rs.sudoe.quicraft.core.connect.FallbackReason;
import rs.sudoe.quicraft.core.connect.FallbackReport;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;

/** After a fallback, one {@code quicraft:fallback} message in configuration (docs/protocol.md §6). */
public final class FallbackReports {
    private FallbackReports() {}

    /** Hook: login finished, the connection is in configuration now. */
    public static void loginFinished(Connection connection) {
        try {
            Hooks.enter("fallback report");
            FallbackReason reason = Transports.takeFallback(connection);
            if (reason != null) {
                connection.send(new ServerboundCustomPayloadPacket(new FallbackPayload(FallbackReport.encode(reason))));
            }
        } catch (Throwable t) {
            Hooks.failed("fallback report", t);
        }
    }
}
