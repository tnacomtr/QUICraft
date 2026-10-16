// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * The binding exchange of a {@code quicraft/3} connection starts with the configuration stage and
 * holds Finish Configuration until it is over (docs/protocol.md 12.4). Any other connection has no
 * join and nothing happens.
 */
@Mixin(value = ServerConfigurationPacketListenerImpl.class, priority = 2000)
abstract class ServerConfigurationPacketListenerImplMixin {
    @Inject(method = "startConfiguration", at = @At("HEAD"), cancellable = true)
    private void quicraft$bindingStart(CallbackInfo ci) {
        Connection game = null;
        if (ci.isCancelled()) {
            // Fabric API holds the stage back until the client's channel registration arrived and
            // calls startConfiguration again; the exchange begins on that call, not this one.
            return;
        }
        try {
            Hooks.enter("binding configuration");
            game = ((ServerCommonPacketListenerImplAccessor) (Object) this).quicraft$connection();
            V3Join join = V3Join.of(game);
            if (join != null) {
                // First in the queue: nothing else of the stage starts before activation.
                ConfigurationTask hold = join.begin();
                ((ServerConfigurationPacketListenerImplAccessor) (Object) this).quicraft$configurationTasks().add(hold);
            }
        } catch (Throwable t) {
            Hooks.failed("binding configuration", t);
            V3Join.failOf(game, "binding configuration: " + t, t);
        }
    }
}
