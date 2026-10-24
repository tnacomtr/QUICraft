// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.fabric.Hooks;

/** The F3 line showing the active transport. */
public final class DebugLine {
    private DebugLine() {}

    /** The transport as the line shows it: {@code /3} with its stream count only once the lanes are active. */
    static String label(boolean quic, V3Client.State v3) {
        return label(quic, v3, 0);
    }

    /** As above; {@code shards} is the negotiated CHUNK shard count N (0 if unknown), shown as N + 3 streams. */
    static String label(boolean quic, V3Client.State v3, int shards) {
        if (!quic) {
            return "TCP";
        }
        if (v3 == null) {
            return "QUIC";
        }
        if (v3 != V3Client.State.ACTIVE) {
            return "QUIC /3 (single stream)";
        }
        return shards > 0 ? "QUIC /3 (" + (shards + 3) + " streams, " + shards + " chunk)" : "QUIC /3 (multi-stream)";
    }

    /** Hook: the debug screen's server line was added; add ours under it. */
    public static void add(Consumer<String> addLine) {
        try {
            Hooks.enter("debug line");
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null || listener.getConnection().isMemoryConnection()) {
                return;
            }
            QuicByteStream quic = Transports.quic(listener.getConnection());
            QuicraftClient client = QuicraftClient.get();
            String mode = client != null ? ClientSettings.name(client.mode()) : "off";
            addLine.accept(String.format(Locale.ROOT, "QUICraft: %s (%s)",
                    label(quic != null, Transports.v3State(listener.getConnection()),
                            Transports.v3Shards(listener.getConnection())), mode));
        } catch (Throwable t) {
            Hooks.failed("debug line", t);
        }
    }
}
