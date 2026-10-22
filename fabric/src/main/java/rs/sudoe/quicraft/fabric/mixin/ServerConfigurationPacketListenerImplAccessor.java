// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import java.util.Queue;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ServerConfigurationPacketListenerImplAccessor {
    @Accessor("configurationTasks")
    Queue<ConfigurationTask> quicraft$configurationTasks();

    /** The task the listener waits on, or null between tasks (used by the early wake-up). */
    @Accessor("currentTask")
    ConfigurationTask quicraft$currentTask();

    /** Finishes a task exactly as the listener's own tick does. */
    @Invoker("finishCurrentTask")
    void quicraft$finishCurrentTask(ConfigurationTask.Type type);
}
