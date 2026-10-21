// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import io.netty.channel.EventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.Transports;
import rs.sudoe.quicraft.fabric.mixin.ServerCommonPacketListenerImplAccessor;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * Login and respawn are {@code /3} epoch transitions (docs/protocol.md 13.18). This joins the
 * in-process server over a real {@code quicraft/3} session and, on that one connection, dies and
 * respawns, travels to the nether and back, and respawns once more right after a client block
 * break (so a prediction acknowledgement is around the transition). After every step the session
 * must still be the same open {@code /3} connection with no fallback, the client must have reached
 * the expected level and loaded its chunk, the server must still hold the same player connection,
 * and the client's receive epoch must have advanced by exactly one.
 */
public final class RespawnLiveGameTest implements FabricClientGameTest {
    private static final String OFFLINE = "quicraft.multistream.offlineTest";
    private static final int SETTLE_TICKS = 600;

    @Override
    public void runTest(ClientGameTestContext context) {
        RoutingTable table = MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion());
        if (!QuicSupport.hasKeyingMaterialExport() || !QuicSupport.hasMaxFlowControlWindows()
                || !MultistreamSwitch.serves(table)) {
            QuicraftFabricTestLog.info("skipping the live /3 respawn test: no /3 on this native or protocol");
            return;
        }
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        Mode originalMode = client.mode();
        boolean originalMultistream = client.multistream();
        String originalOffline = System.getProperty(OFFLINE);
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        try {
            byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
            try {
                Files.createDirectories(settings.getParent());
                Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
                System.setProperty(OFFLINE, "true");
                client.setMode(Mode.QUIC_ONLY);
                client.setMultistream(true);
                run(context);
            } finally {
                if (before != null) {
                    Files.write(settings, before);
                } else {
                    Files.deleteIfExists(settings);
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            client.setMode(originalMode);
            client.setMultistream(originalMultistream);
            if (originalOffline == null) {
                System.clearProperty(OFFLINE);
            } else {
                System.setProperty(OFFLINE, originalOffline);
            }
        }
    }

    private static void run(ClientGameTestContext context) {
        Map<String, Set<Object>> baseline = failureKeys(); // earlier tests may have left failures
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
            check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
            try (var join = server.connect()) {
                GameTests.waitForChunks(join);
                Rig rig = new Rig(context, server, baseline);
                rig.capture();
                long epoch = rig.receiveEpoch();
                QuicraftFabricTestLog.info("respawn-live: joined over /3, receive epoch " + epoch + ", level "
                        + rig.clientDimension() + ", position " + rig.clientPosition());
                rig.assertLive("after join", Level.OVERWORLD, epoch);

                // 0. The scripted play session over /3, ending with a far teleport that forgets the
                // chunks around the start (more than the 256-entry forget ring), then a teleport back.
                BlockPos home = rig.clientBlockPos();
                PlaySession.run(context, server, join);
                rig.assertLive("after the play session", Level.OVERWORLD, epoch);
                server.runCommand("tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
                context.waitTicks(20);
                GameTests.waitForChunks(join);
                rig.assertLive("after the far teleport back near spawn", Level.OVERWORLD, epoch);
                PlaySession.compareBlocks(context, server, home.offset(-8, -3, -8), home.offset(8, 3, 8));
                QuicraftFabricTestLog.info("respawn-live: blocks around spawn agree after the ring overflow");

                // 0b. Overflow the held-chunk forget ring (256 entries): each far hop forgets about 81 chunks
                // (view distance 4), so four hops on top of the play session pass it. Then return to the
                // play session's far target, whose chunks are reinstalled after the overflow.
                Boolean untrustedBefore = rig.heldChunksUntrusted();
                QuicraftFabricTestLog.info("respawn-live: server HeldChunkSet untrusted before the hops: "
                        + untrustedBefore);
                check(Boolean.FALSE.equals(untrustedBefore),
                        "the held set is still trusted before the hops: " + untrustedBefore);
                int[][] hops = {{-3000, 0}, {0, 3000}, {4000, 4000}, {-4000, -4000}};
                for (int[] hop : hops) {
                    server.runCommand("tp @a " + (home.getX() + hop[0]) + " " + (home.getY() + 1) + " "
                            + (home.getZ() + hop[1]));
                    context.waitTicks(20);
                    GameTests.waitForChunks(join);
                }
                rig.assertLive("after four far hops", Level.OVERWORLD, epoch);
                Boolean untrustedAfter = rig.heldChunksUntrusted();
                QuicraftFabricTestLog.info("respawn-live: server HeldChunkSet untrusted after the hops: "
                        + untrustedAfter);
                check(Boolean.TRUE.equals(untrustedAfter),
                        "the forget ring overflowed and the held set is untrusted: " + untrustedAfter);
                int fx = home.getX() + 2000;
                int fz = home.getZ() + 3 - 1500; // PlaySession's far target
                server.runCommand("tp @a " + fx + " " + (home.getY() + 1) + " " + fz);
                context.waitTicks(20);
                GameTests.waitForChunks(join);
                rig.assertLive("after returning to the play session's far area", Level.OVERWORLD, epoch);
                PlaySession.compareBlocks(context, server, new BlockPos(fx - 8, home.getY() - 3, fz - 8),
                        new BlockPos(fx + 8, home.getY() + 3, fz + 8));
                QuicraftFabricTestLog.info("respawn-live: blocks agree in the reinstalled far area");
                server.runCommand("tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
                context.waitTicks(20);
                GameTests.waitForChunks(join);
                rig.assertLive("back at spawn after the overflow", Level.OVERWORLD, epoch);

                // 1. Death respawn: same dimension, chunks held.
                server.runCommand("kill @a");
                context.waitForScreen(DeathScreen.class);
                context.waitTicks(30); // the respawn button is inactive for the first moments
                context.clickScreenButton("deathScreen.respawn");
                epoch = rig.settle("death respawn", Level.OVERWORLD, epoch + 1);
                BlockPos spawn = rig.clientBlockPos();

                // 2. Dimension change and back (both are respawn 82).
                server.runCommand("gamemode creative @a");
                server.runCommand("execute in minecraft:the_nether run tp @a 0 129 0");
                epoch = rig.settle("to the nether", Level.NETHER, epoch + 1);
                server.runCommand("execute in minecraft:overworld run tp @a " + spawn.getX() + " " + spawn.getY()
                        + " " + spawn.getZ());
                epoch = rig.settle("back to the overworld", Level.OVERWORLD, epoch + 1);

                // 3. A client block break that the server accepts, then a respawn. The exact ordering of a
                // block_changed_ack around the respawn is covered by the parity tests; this only proves the
                // live connection survives a respawn right after a client-driven world edit.
                BlockPos target = rig.clientBlockPos().east(2);
                server.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + " minecraft:stone");
                context.waitTicks(10);
                check(context.computeOnClient(c -> !c.level.getBlockState(target).isAir()),
                        "the target block reached the client before the break");
                context.runOnClient(c -> c.gameMode.startDestroyBlock(target, Direction.UP));
                boolean brokenOnServer = false;
                for (int tick = 0; tick < 100 && !brokenOnServer; tick++) {
                    context.waitTick();
                    brokenOnServer = server.computeOnServer(s -> s.overworld().getBlockState(target).isAir());
                }
                check(brokenOnServer, "the server accepted the client's block break within 100 ticks");
                server.runCommand("kill @a");
                context.waitForScreen(DeathScreen.class);
                context.waitTicks(30);
                context.clickScreenButton("deathScreen.respawn");
                epoch = rig.settle("respawn after a block break", Level.OVERWORLD, epoch + 1);
                boolean serverAir = server.computeOnServer(s -> s.overworld().getBlockState(target).isAir());
                boolean clientAir = context.computeOnClient(c -> c.level.getBlockState(target).isAir());
                QuicraftFabricTestLog.info("respawn-live: block break accepted by the server; after the respawn "
                        + "server air=" + serverAir + ", client air=" + clientAir);
                check(serverAir && clientAir, "the broken block is air on both sides after the respawn: server "
                        + serverAir + ", client " + clientAir);

                // Leave the world as the later tests expect it.
                server.runCommand("gamemode survival @a");
                context.waitTicks(10);
                QuicraftFabricTestLog.info("respawn-live: phase 1 PASS, final receive epoch " + epoch);
            }

            // Phase 2, fresh connection: a respawn while the held set is trusted and holds chunks.
            context.waitTicks(20);
            try (var join = server.connect()) {
                GameTests.waitForChunks(join);
                Rig rig = new Rig(context, server, baseline);
                rig.capture();
                long epoch = rig.receiveEpoch();
                Boolean untrustedAtJoin = rig.heldChunksUntrusted();
                QuicraftFabricTestLog.info("respawn-live: phase 2 joined again over /3, receive epoch " + epoch
                        + ", HeldChunkSet untrusted " + untrustedAtJoin);
                check(Boolean.FALSE.equals(untrustedAtJoin),
                        "login must keep the first-install exemption: untrusted=" + untrustedAtJoin);
                rig.assertLive("phase 2 after join", Level.OVERWORLD, epoch);
                server.runCommand("kill @a");
                context.waitForScreen(DeathScreen.class);
                context.waitTicks(30);
                context.clickScreenButton("deathScreen.respawn");
                epoch = rig.settle("phase 2 respawn with held chunks", Level.OVERWORLD, epoch + 1);
                Boolean untrustedAfter = rig.heldChunksUntrusted();
                QuicraftFabricTestLog.info("respawn-live: phase 2 HeldChunkSet untrusted after the respawn: "
                        + untrustedAfter);
                check(Boolean.TRUE.equals(untrustedAfter),
                        "a respawn over held chunks must mark the set unknown: untrusted=" + untrustedAfter);
                BlockPos here = rig.clientBlockPos();
                server.runCommand("fill " + (here.getX() - 3) + " " + here.getY() + " " + (here.getZ() + 3) + " "
                        + (here.getX() + 3) + " " + (here.getY() + 2) + " " + (here.getZ() + 5)
                        + " minecraft:bricks");
                context.waitTicks(20);
                PlaySession.compareBlocks(context, server, here.offset(-8, -3, -8), here.offset(8, 3, 8));
                QuicraftFabricTestLog.info("respawn-live: PASS, phase 2 final receive epoch " + epoch);
            }
        }
    }

    /** The connection under test and the checks that run after every step. */
    private static final class Rig {
        final ClientGameTestContext context;
        final TestDedicatedServerContext server;
        final Map<String, Set<Object>> failureBaseline;
        Connection connection;
        Object serverListener;
        Connection serverConnection;
        V3Connection transport;
        Object controller;

        Rig(ClientGameTestContext context, TestDedicatedServerContext server,
                Map<String, Set<Object>> failureBaseline) {
            this.context = context;
            this.server = server;
            this.failureBaseline = failureBaseline;
        }

        void capture() {
            connection = context.computeOnClient(c -> c.getConnection().getConnection());
            Object[] pair = server.computeOnServer(s -> {
                check(s.getPlayerList().getPlayers().size() == 1, "one player on the server");
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                return new Object[] {p.connection, ((ServerCommonPacketListenerImplAccessor) p.connection).quicraft$connection()};
            });
            serverListener = pair[0];
            serverConnection = (Connection) pair[1];
            Object stream = transportEntry("quic", connection);
            check(stream instanceof V3Connection, "the join runs on a /3 transport: " + stream);
            transport = (V3Connection) stream;
            controller = transportEntry("v3", connection);
            check(controller != null && "ACTIVE".equals(String.valueOf(transportEntry("v3State", connection))),
                    "the /3 controller is ACTIVE: " + transportEntry("v3State", connection));
        }

        /** Server-side HeldChunkSet.untrusted() by reflection, or null when the path is not reachable. */
        Boolean heldChunksUntrusted() {
            return server.computeOnServer(s -> {
                try {
                    Object join = rs.sudoe.quicraft.fabric.server.V3Join.of(serverConnection);
                    V3Connection v3 = (V3Connection) read(join, "connection");
                    Object session = v3.session();
                    Method driverGetter = session.getClass().getDeclaredMethod("sendDriver");
                    driverGetter.setAccessible(true);
                    Object driver = driverGetter.invoke(session);
                    Object held = read(driver, "heldChunks");
                    Method untrusted = held.getClass().getDeclaredMethod("untrusted");
                    untrusted.setAccessible(true);
                    // The send driver mutates the set under its own monitor.
                    synchronized (driver) {
                        return (Boolean) untrusted.invoke(held);
                    }
                } catch (Throwable t) {
                    QuicraftFabricTestLog.info("respawn-live: HeldChunkSet not reachable: " + t);
                    return null;
                }
            });
        }

        ResourceKey<Level> clientDimension() {
            return context.computeOnClient(c -> c.level.dimension());
        }

        Vec3 clientPosition() {
            return context.computeOnClient(c -> c.player.position());
        }

        BlockPos clientBlockPos() {
            return context.computeOnClient(c -> c.player.blockPosition());
        }

        /** Waits for the transition to land, then checks every invariant. Returns the receive epoch. */
        long settle(String label, ResourceKey<Level> dimension, long expectedEpoch) {
            boolean reached = false;
            for (int tick = 0; tick < SETTLE_TICKS && !reached; tick++) {
                context.waitTick();
                reached = context.computeOnClient(c -> c.level != null && c.player != null && c.screen == null
                        && c.level.dimension() == dimension && c.player.isAlive()
                        && c.level.getChunkSource().hasChunk(c.player.chunkPosition().x(),
                                c.player.chunkPosition().z()))
                        && receiveEpoch() >= expectedEpoch;
            }
            check(reached, label + ": the client did not finish loading " + dimension.identifier() + " with epoch "
                    + expectedEpoch + " in " + SETTLE_TICKS + " ticks (level "
                    + context.computeOnClient(c -> c.level == null ? "none" : c.level.dimension().identifier().toString())
                    + ", epoch " + receiveEpoch() + ", screen "
                    + context.computeOnClient(c -> c.screen == null ? "none" : c.screen.getClass().getSimpleName())
                    + ")");
            context.waitTicks(20); // a stray second advance or a close would show up here
            long epoch = assertLive(label, dimension, expectedEpoch);
            return epoch;
        }

        long assertLive(String label, ResourceKey<Level> dimension, long expectedEpoch) {
            Connection now = context.computeOnClient(c -> c.getConnection() == null ? null : c.getConnection().getConnection());
            check(now == connection, label + ": the connection object changed: " + now + " vs " + connection);
            check(connection.isConnected(), label + ": the connection closed");
            check(Transports.isQuic(connection), label + ": no longer on QUIC");
            check(transportEntry("quic", connection) == transport, label + ": the /3 transport changed");
            check(transportEntry("v3", connection) == controller, label + ": the /3 controller changed");
            check("ACTIVE".equals(String.valueOf(transportEntry("v3State", connection))),
                    label + ": /3 left ACTIVE: " + transportEntry("v3State", connection));
            check(transportEntry("v3Failure", connection) == null,
                    label + ": a /3 failure was recorded: " + transportEntry("v3Failure", connection));
            check(!Boolean.TRUE.equals(transportEntry("takeRetryOverTcp", connection)),
                    label + ": the next join would use TCP");
            checkNoNewFailure(label);
            check(context.computeOnClient(c -> c.level != null && c.level.dimension() == dimension),
                    label + ": client not in " + dimension.identifier());
            boolean chunk = context.computeOnClient(c -> c.level.getChunkSource()
                    .hasChunk(c.player.chunkPosition().x(), c.player.chunkPosition().z()));
            check(chunk, label + ": the player's own chunk is not loaded on the client");
            check(context.computeOnClient(c -> c.player.isAlive() && c.screen == null),
                    label + ": player dead or a screen is still up");
            Vec3 client = clientPosition();
            Object[] onServer = server.computeOnServer(s -> {
                check(s.getPlayerList().getPlayers().size() == 1, label + ": server player count");
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                return new Object[] {p.connection,
                        ((ServerCommonPacketListenerImplAccessor) p.connection).quicraft$connection(),
                        p.level().dimension(), p.position()};
            });
            check(onServer[0] == serverListener && onServer[1] == serverConnection,
                    label + ": the server's player connection changed");
            check(onServer[2] == dimension, label + ": server player in " + onServer[2]);
            double drift = client.distanceTo((Vec3) onServer[3]);
            check(drift < 1.0, label + ": client " + client + " vs server " + onServer[3]);
            long epoch = receiveEpoch();
            check(epoch == expectedEpoch, label + ": receive epoch " + epoch + ", expected exactly " + expectedEpoch);
            QuicraftFabricTestLog.info("respawn-live: " + label + " ok: dimension " + dimension.identifier()
                    + ", position " + client + ", receive epoch " + epoch);
            return epoch;
        }

        /** NStreamSession.receiveEpoch() is package-private and must run on the QUIC owner loop. */
        long receiveEpoch() {
            try {
                Object session = transport.session();
                Method method = session.getClass().getDeclaredMethod("receiveEpoch");
                method.setAccessible(true);
                EventLoop loop = (EventLoop) read(controller, "loop");
                return loop.submit(() -> (Long) method.invoke(session)).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("could not read the receive epoch", e);
            }
        }

        /** This test must add no failure; earlier tests of the default run may have left some. */
        void checkNoNewFailure(String label) {
            Map<String, Set<Object>> now = failureKeys();
            for (Map.Entry<String, Set<Object>> entry : now.entrySet()) {
                Set<Object> added = new HashSet<>(entry.getValue());
                added.removeAll(failureBaseline.get(entry.getKey()));
                check(added.isEmpty(), label + ": this test added a QUIC failure to the failure cache ("
                        + entry.getKey() + "): " + added);
            }
        }
    }

    /** The keys of the shared failure cache's three maps, copied. */
    private static Map<String, Set<Object>> failureKeys() {
        Object cache = read(QuicraftClient.get(), "failures");
        Map<String, Set<Object>> keys = new LinkedHashMap<>();
        synchronized (cache) {
            for (String name : new String[] {"entries", "activationEntries", "unseen"}) {
                Object map = read(cache, name);
                check(map instanceof Map<?, ?>, "failure cache field " + name + " is a map");
                keys.put(name, new HashSet<Object>(((Map<?, ?>) map).keySet()));
            }
        }
        return keys;
    }

    private static Object transportEntry(String name, Connection connection) {
        try {
            for (Method m : Transports.class.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Connection.class) {
                    m.setAccessible(true);
                    return m.invoke(null, connection);
                }
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
