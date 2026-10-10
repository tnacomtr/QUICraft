// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    @Nullable Channel quicraft$channel();

    @Accessor("bandwidthDebugMonitor")
    @Nullable BandwidthDebugMonitor quicraft$bandwidthDebugMonitor();
}
