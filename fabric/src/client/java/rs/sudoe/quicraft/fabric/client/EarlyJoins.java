// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.net.InetSocketAddress;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/**
 * The client's 0-RTT joins (docs/protocol.md §8): what the game's first flight depends on, and
 * the TCP retry after a first-flight mismatch.
 */
public final class EarlyJoins {
    /** The connect screen's request, captured as it starts: the join in progress. */
    record Request(Screen parent, ServerAddress address, ServerData data, boolean quickPlay,
            @Nullable TransferState transferState) {}

    private static volatile @Nullable Request current;
    /** One join to this server (TCP address key) goes vanilla TCP: the retry after a mismatch. */
    private static volatile @Nullable InetSocketAddress tcpOnce;

    private EarlyJoins() {}

    public static void starting(Screen parent, ServerAddress address, ServerData data, boolean quickPlay,
            @Nullable TransferState transferState) {
        current = new Request(parent, address, data, quickPlay, transferState);
    }

    /**
     * Everything the game's handshake and Login Start depend on (ConnectScreen: the resolved
     * address's host name and port, login or transfer, the profile's name and UUID, the protocol
     * version), or null if not known: then no 0-RTT and nothing recorded.
     */
    static @Nullable String joinInputs(InetSocketAddress address) {
        Request request = current;
        User user = Minecraft.getInstance().getUser();
        if (request == null || user == null) {
            return null;
        }
        return String.join("\n", address.getHostName(), Integer.toString(address.getPort()),
                request.transferState() != null ? "transfer" : "login", user.getName(),
                String.valueOf(user.getProfileId()),
                Integer.toString(SharedConstants.getCurrentVersion().protocolVersion()));
    }

    /** Whether this join must go vanilla TCP (consumed). */
    static boolean takeTcpOnce(InetSocketAddress address) {
        InetSocketAddress forced = tcpOnce;
        if (forced != null && forced.equals(ClientConnector.key(address))) {
            tcpOnce = null;
            return true;
        }
        return false;
    }

    /**
     * The game's first flight differed from the one sent as 0-RTT data, and the QUIC connection
     * was closed. Instead of the disconnect screen, the same join starts again over TCP.
     * Render thread; true if the retry took over.
     */
    public static boolean retryOverTcp(Connection connection) {
        if (!Transports.takeRetryOverTcp(connection)) {
            return false;
        }
        Request request = current;
        if (request == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        QuicraftFabric.LOG.info("QUICraft: joining {} again over TCP (the 0-RTT data didn't match the game's)",
                request.address());
        tcpOnce = ClientConnector.key(connection.getRemoteAddress() instanceof InetSocketAddress isa ? isa
                : new InetSocketAddress(request.address().getHost(), request.address().getPort()));
        minecraft.setScreen(request.parent()); // startConnecting refuses while a ConnectScreen shows
        ConnectScreen.startConnecting(request.parent(), minecraft, request.address(), request.data(),
                request.quickPlay(), request.transferState());
        return true;
    }
}
