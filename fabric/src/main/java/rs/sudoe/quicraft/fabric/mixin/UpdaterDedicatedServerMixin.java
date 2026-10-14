// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.update.ServerUpdater;

/** Dedicated server start and stop for the updater. Integrated servers never reach this class. */
@Mixin(DedicatedServer.class)
abstract class UpdaterDedicatedServerMixin {
    @Inject(method = "initServer", at = @At("RETURN"))
    private void quicraft$startUpdater(CallbackInfoReturnable<Boolean> cir) {
        try {
            Hooks.enter("updater start");
            if (Boolean.TRUE.equals(cir.getReturnValue())) {
                ServerUpdater.start((DedicatedServer) (Object) this);
            }
        } catch (Throwable t) {
            Hooks.failed("updater start", t);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void quicraft$stopUpdater(CallbackInfo ci) {
        try {
            Hooks.enter("updater stop");
            ServerUpdater.stop();
        } catch (Throwable t) {
            Hooks.failed("updater stop", t);
        }
    }
}
