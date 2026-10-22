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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.server.JoinDiagScope;

/**
 * Join marks around the tag build of {@code /3}, on the exchange's own thread and only while the
 * connection's marks are open there (see {@link JoinDiagScope}). Fails safe: TCP joins and
 * diagnostics-off joins record nothing, and a missing mark never touches the build.
 */
@Mixin(TagNetworkSerialization.class)
abstract class TagNetworkSerializationMixin {
    @Inject(method = "serializeTagsToNetwork", at = @At("HEAD"))
    private static void quicraft$tagsBuildStart(LayeredRegistryAccess<RegistryLayer> registries, CallbackInfo ci) {
        JoinDiagScope.mark("tags build start");
    }

    @Inject(method = "serializeTagsToNetwork", at = @At("RETURN"))
    private static void quicraft$tagsBuildEnd(LayeredRegistryAccess<RegistryLayer> registries, CallbackInfo ci) {
        JoinDiagScope.mark("tags build end");
    }
}
