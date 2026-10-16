// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Puts a line in the player's chat, for one jar across 26.x. The 1.21.11 module has its own
 * {@code ChatNotices} with the call that version has.
 */
final class ChatNotices {
    private ChatNotices() {}

    static void show(Minecraft minecraft, Component line) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(line);
        }
    }
}
