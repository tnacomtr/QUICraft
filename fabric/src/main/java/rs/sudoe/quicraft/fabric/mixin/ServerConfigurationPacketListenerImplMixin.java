// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.JoinWorldTask;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.core.multistream.JoinDiag;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.JoinDiagScope;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * The binding exchange of a {@code quicraft/3} connection starts with the configuration stage and
 * holds Finish Configuration until it is over (docs/protocol.md 12.4). Any other connection has no
 * join and nothing happens.
 *
 * <p>The same hooks carry the join's own marks of the configuration stage's splits (select known
 * packs, registry and tag build, Finish Configuration), when {@code -Dquicraft.v3.joindiag=true}.
 * They only record; a TCP join records nothing, and nothing here changes what is sent or when.</p>
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
            // The connection first: a /3 join whose hook fails must still be found and closed (12.14).
            game = ((ServerCommonPacketListenerImplAccessor) (Object) this).quicraft$connection();
            Hooks.enter("binding configuration");
            V3Join join = V3Join.of(game);
            if (join != null) {
                // First in the queue: nothing else of the stage starts before activation.
                GameProfile profile = ((ServerConfigurationPacketListenerImpl) (Object) this).getOwner();
                ConfigurationTask hold = join.begin(profile);
                ((ServerConfigurationPacketListenerImplAccessor) (Object) this).quicraft$configurationTasks().add(hold);
            }
        } catch (Throwable t) {
            Hooks.failed("binding configuration", t);
            V3Join.failOf(game, "binding configuration: " + t, t);
        }
    }

    /** The client's answer to Select Known Packs arrived: the registry data follows. */
    @Inject(method = "handleSelectKnownPacks", at = @At("HEAD"))
    private void quicraft$knownPacksReceived(ServerboundSelectKnownPacks packet, CallbackInfo ci) {
        JoinDiag.mark(quicraft$joinDiag(), "known-pack response received");
    }

    /**
     * The build of this connection's registry data and tags runs inside {@code handleResponse} on
     * this thread: those two helpers find the connection's marks there.
     */
    @WrapOperation(method = "handleSelectKnownPacks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/config/SynchronizeRegistriesTask;handleResponse"
                    + "(Ljava/util/List;Ljava/util/function/Consumer;)V"))
    private void quicraft$registryBuildScope(SynchronizeRegistriesTask task, List<KnownPack> packs,
            Consumer<Packet<?>> sender, Operation<Void> original) {
        JoinDiagScope.Scope scope = null;
        try {
            scope = JoinDiagScope.open(quicraft$joinDiag());
        } catch (Throwable ignored) {
            // diagnostics never touch the join (the build below runs for TCP joins too)
        }
        try {
            original.call(task, packs, sender);
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (Throwable ignored) {
                    // see above
                }
            }
        }
    }

    /** Marks the packets a task start actually sends on this connection's join. */
    @WrapOperation(method = "startNextTask", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ConfigurationTask;start(Ljava/util/function/Consumer;)V"))
    private void quicraft$taskStartMark(ConfigurationTask task, Consumer<Packet<?>> sender,
            Operation<Void> original) {
        original.call(task, sender);
        try {
            ConfigurationTask.Type type = task.type();
            if (SynchronizeRegistriesTask.TYPE.equals(type)) {
                JoinDiag.mark(quicraft$joinDiag(), "Select Known Packs sent");
            } else if (JoinWorldTask.TYPE.equals(type)) {
                JoinDiag.mark(quicraft$joinDiag(), "Finish Configuration sent");
            }
        } catch (Throwable ignored) {
            // The marks stay out of the configuration stage's way.
        }
    }

    /** The marks of this listener's connection, or null for a TCP/{@code /2} join or when they are off. */
    @Unique
    private @Nullable JoinDiag quicraft$joinDiag() {
        try {
            V3Join join = V3Join.of(((ServerCommonPacketListenerImplAccessor) (Object) this).quicraft$connection());
            return join == null ? null : join.joinDiag();
        } catch (Throwable t) {
            return null;
        }
    }
}
