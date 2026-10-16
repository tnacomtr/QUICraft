// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import rs.sudoe.quicraft.fabric.Hooks;

/** Shows what {@link V3Notices} collected once the join reaches play (a hook, so it fails safe). */
public final class PlayNotices {
    private PlayNotices() {}

    /** Hook: the play login was handled, so the chat exists. Render thread. */
    public static void joined() {
        try {
            Hooks.enter("join notices");
            if (V3Notices.isEmpty()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            for (V3Notices.Notice notice : V3Notices.drain()) {
                Component line = notice.literal() != null ? Component.literal(notice.literal())
                        : Component.translatable(notice.key(), (Object[]) notice.args());
                ChatNotices.show(minecraft, line);
            }
        } catch (Throwable t) {
            Hooks.failed("join notices", t);
        }
    }
}
