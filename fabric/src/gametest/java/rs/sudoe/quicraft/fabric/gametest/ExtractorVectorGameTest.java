// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * Captures the review evidence for the reviewed extractor layouts (13.7) from the live game:
 * real uncompressed frames plus the position or list the game itself reports for them. Opt-in,
 * because it exists to regenerate a checked-in file:
 * {@code -Pquicraft.emitExtractorVectors=<file>} (see docs/platforms/fabric.md).
 *
 * <p>The capture runs in three phases so every position layout sees both coordinate signs: at
 * spawn, after a far teleport into freshly generated chunks around x=-800, z=-1200, and after a
 * teleport back to another fresh area around x=+800, z=+1200. A phase is positive at spawn, but
 * chunk installs only exist after a teleport, hence the third one.
 * {@link ExtractorVectorCapture#assertCoverage} fails the run if any claimed packet produced no
 * sample, or if a position layout produced no negative or no nonnegative sample, so a layout
 * whose mapping cannot be captured never ships as an assumption.
 *
 * <p>The live packet inventory is read by reflection (as RoutingTableGameTest does): this source
 * set is shared with the 1.21.11 jar, where the capture has nothing to do, and the test returns
 * early unless the live protocol is the 775 the vectors are tagged with.
 */
public class ExtractorVectorGameTest implements FabricClientGameTest {
    /** Makes the test capture and write vectors: -Pquicraft.emitExtractorVectors=<file>. */
    private static final String EMIT_PROPERTY = "quicraft.gametest.emitExtractorVectors";

    /** The shipped 26.1.2 protocol number the vectors are tagged with. */
    private static final int PROTOCOL = 775;

    /** Used when the property is set to {@code true} instead of a path. */
    private static final String DEFAULT_FILE = "captured-" + PROTOCOL + "-vectors.txt";

    /** Where the far phase lands: negative chunk coordinates, chunks generated on the spot. */
    private static final int FAR_X = -800;
    private static final int FAR_Z = -1200;

    /**
     * Where the near phase lands: positive chunk coordinates in another fresh area. The spawn
     * chunks were loaded before the capture was installed, so their chunk installs are invisible;
     * a teleport is what gives chunk_xz a nonnegative sample.
     */
    private static final int NEAR_X = 800;
    private static final int NEAR_Z = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        String target = System.getProperty(EMIT_PROPERTY);
        if (target == null) {
            return;
        }
        if (SharedConstants.getProtocolVersion() != PROTOCOL) {
            QuicraftFabricTestLog.info("[QUICraft] extractor capture skipped: protocol "
                    + SharedConstants.getProtocolVersion() + " is not " + PROTOCOL);
            return;
        }
        Path path = Paths.get(target.isEmpty() || target.equals("true") ? DEFAULT_FILE : target);
        // The vectors are the game's own clientbound bytes on the vanilla pipeline, so the
        // capture joins over TCP; a QUIC join would ride the mod's own lane codec instead.
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        client.setMode(Mode.TCP_ONLY);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(!GameTests.connectedOverQuic(context), "the capture runs on the vanilla TCP pipeline");
                ExtractorVectorCapture.install(context);
                check(ExtractorVectorCapture.installed(), "the capture handlers are installed");
                server.runCommand("gamemode creative @a");
                context.waitTicks(20);

                ExtractorVectorCapture.phase("spawn");
                trigger(context, server, context.computeOnClient(c -> c.player.blockPosition()));
                ExtractorVectorCapture.emit(path, PROTOCOL, false);

                int y = context.computeOnClient(c -> c.player.blockPosition().getY());
                // The phase label follows where the client is going, so packets that arrive while
                // the new chunks stream in are labelled with the phase that caused them.
                ExtractorVectorCapture.phase("far");
                server.runCommand("tp @a " + FAR_X + " " + y + " " + FAR_Z);
                context.waitTicks(20);
                GameTests.waitForChunks(connection);
                trigger(context, server, context.computeOnClient(c -> c.player.blockPosition()));
                ExtractorVectorCapture.emit(path, PROTOCOL, false);

                // Back at positive coordinates in another fresh area: the chunk installs it forces
                // give chunk_xz its nonnegative sample, and the edits repeat the other layouts.
                ExtractorVectorCapture.phase("near");
                server.runCommand("tp @a " + NEAR_X + " " + y + " " + NEAR_Z);
                context.waitTicks(20);
                GameTests.waitForChunks(connection);
                trigger(context, server, context.computeOnClient(c -> c.player.blockPosition()));
                ExtractorVectorCapture.emit(path, PROTOCOL, false);

                ExtractorVectorCapture.assertCoverage(path);
                ExtractorVectorCapture.emit(path, PROTOCOL, true);
            }
        } finally {
            client.setMode(Mode.AUTO);
        }
    }

    /**
     * One round of every trigger at {@code base}: a multi-block section update, a single block
     * update, a block entity, blocks that change light, a biome change and one client-side block
     * action. Chunk installs and chunk batch acks come with the chunk loads themselves.
     */
    private static void trigger(ClientGameTestContext context, TestDedicatedServerContext server, BlockPos base) {
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();
        // A 3x3x3 fill stays inside one 16-block section: one section_blocks_update.
        server.runCommand("fill " + (x + 4) + " " + y + " " + (z + 4) + " " + (x + 6) + " " + (y + 2) + " "
                + (z + 6) + " minecraft:bricks");
        // block_update, and a sign whose text is block entity data the server pushes to clients:
        // block_entity_data. (A chest's custom name does not make the server broadcast its data.)
        server.runCommand("setblock " + (x - 4) + " " + (y + 6) + " " + (z - 4) + " minecraft:gold_block");
        server.runCommand("setblock " + (x - 6) + " " + (y + 6) + " " + (z + 6) + " minecraft:oak_sign");
        server.runCommand("data merge block " + (x - 6) + " " + (y + 6) + " " + (z + 6)
                + " {front_text:{messages:['{\"text\":\"QUICraft\"}','','','']}}");
        // Light-changing edits. They are kept as realistic play traffic, and they are also the
        // evidence that varint_xz has no production identifier: a surface break (sky light), a
        // stone roof over a whole area (sky light), a torch and glowstone (block light) never made
        // 26.1.2 send a ClientboundLightUpdatePacket, so no layout is claimed for light_update.
        server.runCommand("setblock " + (x - 3) + " " + (y - 1) + " " + (z - 3) + " minecraft:air");
        server.runCommand("fill " + (x + 8) + " " + (y + 3) + " " + (z - 8) + " " + (x + 16) + " " + (y + 3) + " "
                + (z + 8) + " minecraft:stone");
        server.runCommand("setblock " + (x - 8) + " " + y + " " + (z - 8) + " minecraft:torch");
        server.runCommand("setblock " + (x + 8) + " " + y + " " + (z - 8) + " minecraft:glowstone");
        // chunks_biomes carries the whole chunk list of the changed region; spanning two chunks on
        // each axis makes the captured sample a multi-entry list.
        server.runCommand("fillbiome " + (x + 8) + " " + y + " " + (z + 8) + " " + (x + 24) + " " + (y + 2)
                + " " + (z + 24) + " minecraft:desert");
        context.waitTicks(40);
        // A client block action makes the server acknowledge its sequence.
        BlockPos at = context.computeOnClient(c -> c.player.blockPosition().below(1));
        context.runOnClient(c -> c.gameMode.startDestroyBlock(at, Direction.DOWN));
        context.waitTicks(40);
    }
}
