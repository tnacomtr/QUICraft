// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.components.debug.DebugEntryTps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.EventLoopGroupHolder;
import rs.sudoe.quicraft.fabric.client.Transports;

/** Shared pieces of the client gametests. */
final class GameTests {
    private GameTests() {}

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** A free port number for both TCP and UDP on loopback. */
    static int freePort() {
        for (int i = 0; i < 50; i++) {
            try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                int port = tcp.getLocalPort();
                try (DatagramSocket udp = new DatagramSocket(new InetSocketAddress(port))) {
                    udp.getLocalPort();
                    return port;
                } catch (IOException taken) {
                    // try another
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no free port");
    }

    static TestDedicatedServerContext server(ClientGameTestContext context) {
        Properties p = new Properties();
        p.setProperty("server-port", Integer.toString(freePort()));
        p.setProperty("online-mode", "false");
        p.setProperty("level-type", "minecraft:flat");
        p.setProperty("generate-structures", "false");
        p.setProperty("spawn-protection", "0");
        p.setProperty("view-distance", "4");
        p.setProperty("simulation-distance", "4");
        return context.worldBuilder().createServer(p);
    }

    /**
     * Waits until the joined world has rendered. Where that method lives differs between Fabric
     * API versions: on the connection (6.x, 26.2+), on getClientLevel()'s context (5.x, 26.1.x) or
     * on getClientWorld()'s (4.x, 1.21.11). Hence reflection.
     */
    static void waitForChunks(Object connection) {
        try {
            Object target = connection;
            if (find(connection, "waitForChunksRender") == null) {
                java.lang.reflect.Method getter = find(connection, "getClientLevel");
                if (getter == null) {
                    getter = find(connection, "getClientWorld");
                }
                target = getter.invoke(connection);
            }
            find(target, "waitForChunksRender").invoke(target);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException | NullPointerException e) {
            throw new AssertionError(e);
        }
    }

    private static java.lang.reflect.Method find(Object target, String name) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name);
            m.setAccessible(true); // implementation classes may be package-private
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static boolean connectedOverQuic(ClientGameTestContext context) {
        return context.computeOnClient(c -> c.getConnection() != null
                && Transports.isQuic(c.getConnection().getConnection()));
    }

    /** The F3 server lines, including the one QUICraft adds (proves the debug mixin applied). */
    static List<String> debugServerLines(ClientGameTestContext context) {
        return context.computeOnClient(c -> {
            List<String> lines = new ArrayList<>();
            DebugScreenDisplayer displayer = new DebugScreenDisplayer() {
                @Override
                public void addPriorityLine(String line) {
                    lines.add(line);
                }

                @Override
                public void addLine(String line) {
                    lines.add(line);
                }

                @Override
                public void addToGroup(Identifier group, java.util.Collection<String> more) {
                    lines.addAll(more);
                }

                @Override
                public void addToGroup(Identifier group, String line) {
                    lines.add(line);
                }
            };
            new DebugEntryTps().display(displayer, c.level, null, null);
            return lines;
        });
    }

    /**
     * Pings {@code address} as the server list does and waits for the pong. Returns whether the
     * ping completed.
     */
    static boolean serverListPing(ClientGameTestContext context, String address) {
        AtomicBoolean pong = new AtomicBoolean();
        ServerStatusPinger pinger = new ServerStatusPinger();
        ServerData data = new ServerData("test", address, ServerData.Type.OTHER);
        context.runOnClient(c -> {
            try {
                pinger.pingServer(data, () -> {}, () -> pong.set(true),
                        EventLoopGroupHolder.remote(c.options.useNativeTransport()));
            } catch (java.net.UnknownHostException e) {
                throw new AssertionError(e);
            }
        });
        for (int i = 0; i < 200 && !pong.get(); i++) {
            context.runOnClient(c -> pinger.tick());
            context.waitTick();
        }
        context.runOnClient(c -> pinger.removeAll());
        return pong.get();
    }

    static String address(TestDedicatedServerContext server) {
        return "localhost:" + server.computeOnServer(s -> s.getPort());
    }

}
