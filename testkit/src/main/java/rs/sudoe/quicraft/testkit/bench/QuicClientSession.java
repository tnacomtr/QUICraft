// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import java.net.InetSocketAddress;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * MCProtocolLib's client session over an already-connected QUIC stream: its Bootstrap gets a
 * {@link QuicBridgeChannel} instead of a TCP socket, and everything else (codec, encryption,
 * compression, game protocol) runs unchanged, as it would in the mod.
 */
final class QuicClientSession extends ClientNetworkSession {
    private final QuicByteStream stream;

    QuicClientSession(InetSocketAddress address, MinecraftProtocol protocol, QuicByteStream stream) {
        super(address, protocol, Runnable::run, null, null);
        this.stream = stream;
    }

    @Override
    protected ChannelFactory<? extends Channel> getChannelFactory() {
        return () -> new QuicBridgeChannel(stream);
    }
}
