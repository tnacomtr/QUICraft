// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.RegistrySynchronization.PackedRegistryEntry;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.KnownPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.server.JoinDiagScope;

/**
 * Join marks around the registry data build of {@code /3}: the exchange's thread has the marks of
 * the connection open in {@link JoinDiagScope}, and a TCP join or diagnostics-off join has none, so
 * this records nothing there. Fails safe: a missing mark never touches the build.
 */
@Mixin(RegistrySynchronization.class)
abstract class RegistrySynchronizationMixin {
    @Inject(method = "packRegistries", at = @At("HEAD"))
    private static void quicraft$registryBuildStart(DynamicOps<Tag> ops, RegistryAccess registries,
            Set<KnownPack> clientKnownPacks,
            BiConsumer<ResourceKey<? extends Registry<?>>, List<PackedRegistryEntry>> output, CallbackInfo ci) {
        try {
            JoinDiagScope.mark("registry data build start");
        } catch (Throwable ignored) {
            // diagnostics never touch the build (this runs for TCP joins too)
        }
    }

    @Inject(method = "packRegistries", at = @At("RETURN"))
    private static void quicraft$registryBuildEnd(DynamicOps<Tag> ops, RegistryAccess registries,
            Set<KnownPack> clientKnownPacks,
            BiConsumer<ResourceKey<? extends Registry<?>>, List<PackedRegistryEntry>> output, CallbackInfo ci) {
        try {
            JoinDiagScope.mark("registry data build end");
        } catch (Throwable ignored) {
            // diagnostics never touch the build (this runs for TCP joins too)
        }
    }
}
