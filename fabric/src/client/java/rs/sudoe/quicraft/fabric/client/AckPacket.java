// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;

/**
 * A marker the game's main thread runs after the packets before it (docs/protocol.md 13.10): the
 * game applies packets from a queue ({@code PacketProcessor}) that {@code Minecraft.execute} does
 * not share, so an acknowledgement scheduled with {@code execute} could run before the packets it
 * covers. This is queued on the same queue, right behind them, and never leaves the process: it is
 * not in any protocol and is never encoded.
 */
public final class AckPacket implements Packet<PacketListener> {
    static final PacketType<AckPacket> TYPE = new PacketType<>(PacketFlow.CLIENTBOUND,
            Identifier.parse("quicraft:ack"));

    private final Runnable action;

    public AckPacket(Runnable action) {
        this.action = action;
    }

    @Override
    public PacketType<? extends Packet<PacketListener>> type() {
        return TYPE;
    }

    @Override
    public void handle(PacketListener listener) {
        action.run();
    }
}
