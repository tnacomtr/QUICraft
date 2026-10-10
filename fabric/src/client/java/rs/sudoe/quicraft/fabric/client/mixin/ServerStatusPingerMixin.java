// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import rs.sudoe.quicraft.fabric.client.ServerListPings;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @ModifyExpressionValue(method = "pingServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;connectToServer(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/util/debugchart/LocalSampleLogger;)Lnet/minecraft/network/Connection;"))
    private Connection quicraft$sniff(Connection connection) {
        ServerListPings.sniff(connection);
        return connection;
    }
}
