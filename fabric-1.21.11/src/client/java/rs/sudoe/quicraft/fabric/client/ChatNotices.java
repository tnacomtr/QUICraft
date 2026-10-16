// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Puts a line in the player's chat on 1.21.11 ({@code displayClientMessage}; 26.x has
 * {@code sendSystemMessage} instead, see the 26.x module's {@code ChatNotices}).
 */
final class ChatNotices {
    private ChatNotices() {}

    static void show(Minecraft minecraft, Component line) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(line, false);
        }
    }
}
