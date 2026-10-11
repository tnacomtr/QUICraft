// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

/**
 * The current screen, for one jar across 26.x: 26.1–26.1.2 keep it on {@code Minecraft}
 * ({@code setScreen}, {@code screen}), 26.2 and later on {@code Minecraft.gui} ({@code setScreen},
 * {@code screen()}). Resolved once at runtime, which works by name because 26.x is unobfuscated.
 * The 1.21.11 module has its own {@code Screens} with direct calls.
 */
public final class Screens {
    /** (Minecraft, Screen) void */
    private static final MethodHandle SET;
    /** (Minecraft) Screen */
    private static final MethodHandle GET;

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle set;
        MethodHandle get;
        try {
            set = lookup.findVirtual(Minecraft.class, "setScreen", MethodType.methodType(void.class, Screen.class));
            get = lookup.findGetter(Minecraft.class, "screen", Screen.class);
        } catch (ReflectiveOperationException onMinecraft) {
            try {
                MethodHandle gui = lookup.findGetter(Minecraft.class, "gui", Gui.class);
                set = MethodHandles.filterArguments(
                        lookup.findVirtual(Gui.class, "setScreen", MethodType.methodType(void.class, Screen.class)),
                        0, gui);
                get = MethodHandles.filterArguments(
                        lookup.findVirtual(Gui.class, "screen", MethodType.methodType(Screen.class)), 0, gui);
            } catch (ReflectiveOperationException onGui) {
                onGui.addSuppressed(onMinecraft);
                throw new ExceptionInInitializerError(onGui);
            }
        }
        SET = set;
        GET = get;
    }

    private Screens() {}

    public static void set(Minecraft minecraft, Screen screen) {
        try {
            SET.invokeExact(minecraft, screen);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static Screen current(Minecraft minecraft) {
        try {
            return (Screen) GET.invokeExact(minecraft);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
