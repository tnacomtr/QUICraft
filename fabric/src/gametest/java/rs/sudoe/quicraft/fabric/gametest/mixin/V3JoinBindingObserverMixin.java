// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.gametest.ServerMultistreamOnlineGameTest;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * Gametest only: the instrument behind the online binding-N-mismatch case. It injects at the head
 * of the production server's {@code V3Join.fail(String, Throwable)}, before that method tears the
 * join down, and hands the failing join and its reason to the test that armed it.
 *
 * <p>The test then checks, on the channel's own event loop, what the real pipeline still holds:
 * the {@code encrypt} handler is installed, {@code decrypt} is the cutover
 * {@code BoundaryCipherDecoder}, and that decoder has neither stopped nor finished. That is the
 * only way to prove 12.14's server-side failure path is cipher-retaining: the peer's view of the
 * exchange cannot see the server's pipeline.</p>
 *
 * <p>This lives in the gametest source set and never ships. All state and verification live in
 * {@link ServerMultistreamOnlineGameTest}: Mixin merges a mixin's own statics into the target, so
 * state held here would diverge from what the test could reach.</p>
 */
@Mixin(value = V3Join.class, remap = false)
abstract class V3JoinBindingObserverMixin {
    @Inject(method = "fail(Ljava/lang/String;Ljava/lang/Throwable;)V", at = @At("HEAD"), remap = false)
    private void quicraftTest$observeBindingFailure(String reason, Throwable cause, CallbackInfo ci) {
        ServerMultistreamOnlineGameTest.bindingFailed((V3Join) (Object) this, reason);
    }
}
