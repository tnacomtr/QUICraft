// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.gametest.CausalSendProbe;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;
import rs.sudoe.quicraft.fabric.server.CausalSendEnvelope;

/** Observes only the return of the actual production envelope allocator. */
@Mixin(value = CausalSendContext.class, remap = false)
public abstract class CausalSendAllocationObserverMixin {
    @Inject(method = "newEnvelope(Ljava/lang/Object;Lrs/sudoe/quicraft/fabric/server/CausalSendEnvelope$ProducerSnapshot;Lrs/sudoe/quicraft/fabric/server/CausalSendEnvelope$BundleMetadata;)Lrs/sudoe/quicraft/fabric/server/CausalSendEnvelope;",
            at = @At("RETURN"), remap = false)
    private void quicraft$observeAllocation(Object owner, CausalSendEnvelope.ProducerSnapshot producer,
            CausalSendEnvelope.BundleMetadata bundle, CallbackInfoReturnable<CausalSendEnvelope> cir) {
        CausalSendProbe.allocated((CausalSendContext) (Object) this, cir.getReturnValue());
    }
}
