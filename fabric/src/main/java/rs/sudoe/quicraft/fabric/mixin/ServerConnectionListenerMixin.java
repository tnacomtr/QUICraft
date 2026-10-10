// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import io.netty.channel.ChannelHandler;
import java.net.InetAddress;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/** Dedicated server: QUIC next to the TCP listener. Every hook fails safe (ServerQuic). */
@Mixin(ServerConnectionListener.class)
abstract class ServerConnectionListenerMixin {
    @ModifyArg(method = "startTcpServerListener", at = @At(value = "INVOKE", remap = false,
            target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;"))
    private ChannelHandler quicraft$wrapInitializer(ChannelHandler vanilla) {
        return ServerQuic.wrapInitializer(((ServerConnectionListener) (Object) this).getServer(), vanilla);
    }

    @Inject(method = "startTcpServerListener", at = @At("RETURN"))
    private void quicraft$startQuic(InetAddress address, int port, CallbackInfo ci) {
        ServerQuic.start(((ServerConnectionListener) (Object) this).getServer(), address, port);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void quicraft$stopQuic(CallbackInfo ci) {
        ServerQuic.stop();
    }
}
