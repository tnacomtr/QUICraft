// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import java.util.Map;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.server.JoinDiagScope;

/**
 * Join marks around the tag build of {@code /3}, on the exchange's own thread and only while the
 * connection's marks are open there (see {@link JoinDiagScope}). Fails safe: TCP joins and
 * diagnostics-off joins record nothing, and a missing mark never touches the build.
 *
 * <p>{@code serializeTagsToNetwork} returns the tag map (Mixin needs
 * {@link CallbackInfoReturnable} for a non-void target, checked by
 * {@code TagNetworkSerializationMixinTest} against the game class).</p>
 */
@Mixin(TagNetworkSerialization.class)
abstract class TagNetworkSerializationMixin {
    @Inject(method = "serializeTagsToNetwork", at = @At("HEAD"))
    private static void quicraft$tagsBuildStart(LayeredRegistryAccess<RegistryLayer> registries,
            CallbackInfoReturnable<Map<ResourceKey<? extends Registry<?>>,
                    TagNetworkSerialization.NetworkPayload>> cir) {
        try {
            JoinDiagScope.mark("tags build start");
        } catch (Throwable ignored) {
            // diagnostics never touch the build (this runs for TCP joins too)
        }
    }

    @Inject(method = "serializeTagsToNetwork", at = @At("RETURN"))
    private static void quicraft$tagsBuildEnd(LayeredRegistryAccess<RegistryLayer> registries,
            CallbackInfoReturnable<Map<ResourceKey<? extends Registry<?>>,
                    TagNetworkSerialization.NetworkPayload>> cir) {
        try {
            JoinDiagScope.mark("tags build end");
        } catch (Throwable ignored) {
            // diagnostics never touch the build (this runs for TCP joins too)
        }
    }
}
