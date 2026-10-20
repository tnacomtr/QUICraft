// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.channel.Channel;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;

/**
 * Client compatibility facade for the common, finally-scoped decoder context. This class owns no
 * thread-local state; both endpoints consult the same connection-local selection.
 */
public final class DecodeContext {
    private DecodeContext() {}

    public static void enter(@Nullable Channel channel) {
        BindingDecodeContext.enter(channel);
    }

    /**
     * Whether a {@code quicraft:binding} payload decoded now belongs to a {@code /3} exchange: only
     * a connection with the multi-stream handler that has not yet activated or failed. Everything
     * else decodes exactly as vanilla does (the bytes are discarded).
     */
    public static boolean bindingExpected() {
        return BindingDecodeContext.bindingExpected();
    }

    /** A pipeline handler that knows whether a binding payload is part of an exchange right now. */
    public interface Gate extends BindingDecodeContext.Gate {}
}
