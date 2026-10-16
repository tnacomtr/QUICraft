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
        if (!quic) {
            return "TCP";
        }
        if (v3 == null) {
            return "QUIC";
        }
        return v3 == V3Client.State.ACTIVE ? "QUIC /3 (3 streams)" : "QUIC /3 (single stream)";
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
                    label(quic != null, Transports.v3State(listener.getConnection())), mode));
        } catch (Throwable t) {
            Hooks.failed("debug line", t);
        }
    }
}
