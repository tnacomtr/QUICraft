// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/** Helpers for transformed Connections; never manufactures, attaches, or enters production context. */
public final class CausalSendRig {
    private CausalSendRig() {}

    static Synthetic syntheticConnection(Connection connection) {
        if (connection.isConnected()) throw new AssertionError("pending-send connection was connected before serializer installation");
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection.configureSerialization(channel.pipeline(), PacketFlow.SERVERBOUND, false, null);
        if (channel.pipeline().get("outbound_config") != null)
            channel.pipeline().replace("outbound_config", "encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        else if (channel.pipeline().get("encoder") != null)
            channel.pipeline().replace("encoder", "encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        connection.configurePacketHandler(channel.pipeline());
        return new Synthetic(connection, channel);
    }

    static Channel channel(Connection connection) {
        Channel channel = ((ConnectionAccessor) connection).quicraft$channel();
        if (channel == null) throw new AssertionError("connection has no channel");
        return channel;
    }

    static void barrier(Channel channel) {
        if (channel.eventLoop().inEventLoop()) throw new AssertionError("cannot wait on own event loop");
        try { channel.eventLoop().submit(() -> {}).get(10, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("event-loop barrier failed", e); }
    }

    record Synthetic(Connection connection, EmbeddedChannel channel) {}
}
