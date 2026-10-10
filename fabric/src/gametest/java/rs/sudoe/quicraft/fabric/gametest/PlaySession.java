// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A scripted play session that fails on any desync between server and client: world edits in a
 * burst, entities appearing and disappearing, player movement in both directions (client
 * input and server teleports), inventory changes, weather, and a far teleport that streams in
 * freshly generated chunks. Each step compares the client's view with the server's.
 */
final class PlaySession {
    private PlaySession() {}

    static void run(ClientGameTestContext context, TestDedicatedServerContext server, Object connection) {
        BlockPos base = context.computeOnClient(c -> c.player.blockPosition());

        // 1. A burst of block changes, in a known order, must arrive complete and in order.
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ() + 3;
        for (int round = 0; round < 3; round++) {
            String block = round % 2 == 0 ? "minecraft:stone" : "minecraft:oak_planks";
            server.runCommand("fill " + (x - 4) + " " + y + " " + z + " " + (x + 4) + " " + (y + 3) + " " + (z + 2)
                    + " " + block);
        }
        for (int i = 0; i < 16; i++) {
            server.runCommand("setblock " + (x - 8 + i) + " " + (y + 5) + " " + (z - 6)
                    + (i % 3 == 0 ? " minecraft:glass" : " minecraft:gold_block"));
        }
        context.waitTicks(20);
        compareBlocks(context, server, new BlockPos(x - 8, y, z - 6), new BlockPos(x + 8, y + 5, z + 2));

        // 2. Entities spawned and removed by the server.
        for (int i = 0; i < 10; i++) {
            server.runCommand("summon minecraft:armor_stand " + (x - 3 + i % 5) + " " + y + " " + (z - 3 - i / 5)
                    + " {NoGravity:1b,Tags:[\"quicraft\"]}");
        }
        context.waitTicks(20);
        AABB area = new AABB(x - 10, y - 2, z - 10, x + 10, y + 10, z + 10);
        int serverStands = server.computeOnServer(s -> s.overworld()
                .getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, area).size());
        int clientStands = context.computeOnClient(c -> c.level
                .getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, area).size());
        check(serverStands == 10 && clientStands == 10,
                "armor stands: server " + serverStands + ", client " + clientStands);
        server.runCommand("kill @e[type=minecraft:armor_stand,tag=quicraft]");
        context.waitTicks(20);
        int left = context.computeOnClient(c -> c.level
                .getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, area).size());
        check(left == 0, left + " armor stands still on the client");

        // 3. Client-driven movement: the server must end up where the client is.
        context.getInput().holdKeyFor(o -> o.keyUp, 30);
        context.getInput().holdKeyFor(o -> o.keyLeft, 15);
        context.waitTicks(20);
        comparePositions(context, server, "after walking");

        // 4. Server-driven movement.
        server.runCommand("tp @a " + (x + 20) + " " + (y + 1) + " " + (z + 20));
        context.waitTicks(20);
        comparePositions(context, server, "after a teleport");
        Vec3 there = context.computeOnClient(c -> c.player.position());
        check(Math.abs(there.x - (x + 20.5)) < 1 && Math.abs(there.z - (z + 20.5)) < 1,
                "client not at the teleport target: " + there);

        // 5. Inventory changes made by the server.
        server.runCommand("give @a minecraft:diamond 37");
        server.runCommand("give @a minecraft:oak_log 64");
        server.runCommand("give @a minecraft:torch 5");
        server.runCommand("clear @a minecraft:oak_log 10");
        context.waitTicks(20);
        compareInventories(context, server);

        // 6. Weather.
        server.runCommand("weather rain");
        context.waitTicks(40);
        check(context.computeOnClient(c -> c.level.isRaining()), "client sees the rain");
        server.runCommand("weather clear");
        context.waitTicks(40);
        check(!context.computeOnClient(c -> c.level.isRaining()), "client sees the rain stop");

        // 7. Far away: chunks generated and streamed while connected.
        int fx = x + 2000;
        int fz = z - 1500;
        server.runCommand("tp @a " + fx + " " + (y + 1) + " " + fz);
        context.waitTicks(20);
        GameTests.waitForChunks(connection);
        comparePositions(context, server, "after a far teleport");
        server.runCommand("fill " + (fx - 3) + " " + y + " " + (fz + 2) + " " + (fx + 3) + " " + (y + 2) + " "
                + (fz + 4) + " minecraft:bricks");
        context.waitTicks(20);
        compareBlocks(context, server, new BlockPos(fx - 8, y - 3, fz - 8), new BlockPos(fx + 8, y + 3, fz + 8));
    }

    private static void compareInventories(ClientGameTestContext context, TestDedicatedServerContext server) {
        int size = context.computeOnClient(c -> c.player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            int s = slot;
            net.minecraft.world.item.ItemStack onClient = context.computeOnClient(c -> c.player.getInventory().getItem(s).copy());
            net.minecraft.world.item.ItemStack onServer = server.computeOnServer(
                    m -> m.getPlayerList().getPlayers().get(0).getInventory().getItem(s).copy());
            check(net.minecraft.world.item.ItemStack.matches(onClient, onServer),
                    "slot " + slot + ": client " + onClient + ", server " + onServer);
        }
    }

    private static void compareBlocks(ClientGameTestContext context, TestDedicatedServerContext server, BlockPos from,
            BlockPos to) {
        int mismatches = 0;
        String first = null;
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockPos p = pos.immutable();
            BlockState onServer = server.computeOnServer(s -> s.overworld().getBlockState(p));
            BlockState onClient = context.computeOnClient(c -> c.level.getBlockState(p));
            if (!onServer.equals(onClient)) {
                mismatches++;
                if (first == null) {
                    first = p + ": server " + onServer + ", client " + onClient;
                }
            }
        }
        check(mismatches == 0, mismatches + " blocks differ, first " + first);
    }

    private static void comparePositions(ClientGameTestContext context, TestDedicatedServerContext server,
            String when) {
        Vec3 client = context.computeOnClient(c -> c.player.position());
        Vec3 onServer = server.computeOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().get(0);
            return ((Entity) player).position();
        });
        check(client.distanceTo(onServer) < 0.01, "positions differ " + when + ": client " + client
                + ", server " + onServer);
    }
}
