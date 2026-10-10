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
            addLine.accept(String.format(Locale.ROOT, "QUICraft: %s (%s)", quic != null ? "QUIC" : "TCP", mode));
        } catch (Throwable t) {
            Hooks.failed("debug line", t);
        }
    }
}
