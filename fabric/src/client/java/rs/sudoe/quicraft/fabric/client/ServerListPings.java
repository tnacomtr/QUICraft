// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import rs.sudoe.quicraft.bridge.status.StatusSniffer;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/** Server list pings refresh the advertisement cache (docs/protocol.md §4). */
public final class ServerListPings {
    private ServerListPings() {}

    /** Hook: a server-list ping connection is open; its status response is read as it passes. */
    public static void sniff(Connection connection) {
        try {
            Hooks.enter("server list ping");
            QuicraftClient client = QuicraftClient.get();
            Channel channel = ((ConnectionAccessor) connection).quicraft$channel();
            if (client == null || channel == null || !(channel.remoteAddress() instanceof InetSocketAddress remote)) {
                return;
            }
            InetSocketAddress key = ClientConnector.key(remote);
            StatusSniffer.install(channel.pipeline(), "splitter",
                    ad -> client.connector().advertisements().record(key, ad));
        } catch (Throwable t) {
            Hooks.failed("server list ping", t);
        }
    }
}
