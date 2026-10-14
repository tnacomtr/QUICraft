// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the source a command runs from, so the updater can tell the server console apart. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source")
    @Nullable CommandSource quicraft$source();
}
