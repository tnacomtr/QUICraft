// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.jspecify.annotations.Nullable;

/** Connection-local binding selection for codecs shared and cached by the game. */
public final class BindingDecodeContext {
    private static final ThreadLocal<Channel> CURRENT = new ThreadLocal<>();

    /** Published only by an attached /3 server join, for the lifetime of that join. */
    public static final AttributeKey<Gate> SERVER_GATE = AttributeKey.valueOf("quicraft.binding.serverGate");

    private BindingDecodeContext() {}

    public static @Nullable Channel current() {
        return CURRENT.get();
    }

    /** The decoder must restore its previous value in finally, including for nested decodes. */
    public static void enter(@Nullable Channel channel) {
        if (channel == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(channel);
        }
    }

    /** Evaluated at decode time, never when constructing a cached payload codec. */
    public static boolean bindingExpected() {
        Channel channel = current();
        if (channel == null) {
            return false;
        }
        Gate server = channel.attr(SERVER_GATE).get();
        if (server != null && server.expectsBinding()) {
            return true;
        }
        for (java.util.Map.Entry<String, io.netty.channel.ChannelHandler> entry : channel.pipeline()) {
            if (entry.getValue() instanceof Gate gate && gate.expectsBinding()) {
                return true;
            }
        }
        return false;
    }

    /** A connection owner that decides whether binding bytes must reach its validator. */
    public interface Gate {
        boolean expectsBinding();
    }
}
