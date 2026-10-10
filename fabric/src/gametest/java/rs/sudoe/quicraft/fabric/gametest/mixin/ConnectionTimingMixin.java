// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.gametest.JoinClock;

/** Test only: timestamps on the Netty thread, as packets arrive. */
@Mixin(Connection.class)
abstract class ConnectionTimingMixin {
    @Inject(method = "channelActive", at = @At("HEAD"))
    private void quicraftTest$active(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (((Connection) (Object) this).getReceiving() == net.minecraft.network.protocol.PacketFlow.CLIENTBOUND) {
            JoinClock.active();
        }
    }

    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void quicraftTest$time(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundLoginPacket) {
            JoinClock.login();
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            JoinClock.chunk();
        }
    }
}
