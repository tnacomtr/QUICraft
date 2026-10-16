// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.Identifier;

/**
 * Not a packet on any wire: a task that rides the game's own queue of packet handlers so it runs on
 * the main thread right after the handlers queued before it (docs/protocol.md 13.10). It is how a
 * delivery's acknowledgement is kept behind the processing of the packets it acknowledges.
 */
final class LaneAckPacket implements Packet<PacketListener> {
    private static final PacketType<LaneAckPacket> TYPE =
            new PacketType<>(PacketFlow.SERVERBOUND, Identifier.parse("quicraft:lane_ack"));

    private final Runnable task;

    LaneAckPacket(Runnable task) {
        this.task = task;
    }

    @Override
    public PacketType<? extends Packet<PacketListener>> type() {
        return TYPE;
    }

    @Override
    public void handle(PacketListener listener) {
        task.run();
    }
}
