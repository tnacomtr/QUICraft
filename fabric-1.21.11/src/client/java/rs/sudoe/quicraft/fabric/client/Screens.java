// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The current screen on 1.21.11: direct calls, remapped by Loom. The 26.x module has its own
 * {@code Screens}, since the screen moved to {@code Minecraft.gui} in 26.2.
 */
public final class Screens {
    private Screens() {}

    public static void set(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static Screen current(Minecraft minecraft) {
        return minecraft.screen;
    }
}
