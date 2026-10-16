// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.channel.Channel;
import org.jspecify.annotations.Nullable;

/**
 * Which connection the packet decoder on this thread is working for. Payload codecs are built once
 * for the whole game and see only bytes, so a codec that must behave differently on a multi-stream
 * connection than on every other one (docs/protocol.md 12.8: {@code quicraft:binding} is inert
 * outside {@code /3}) asks here. Set by {@code PacketDecoderContextMixin} at the start of each
 * decode; a stale value only survives until the next decode on that thread overwrites it.
 */
public final class DecodeContext {
    private static final ThreadLocal<Channel> CURRENT = new ThreadLocal<>();

    private DecodeContext() {}

    public static void enter(@Nullable Channel channel) {
        CURRENT.set(channel);
    }

    /**
     * Whether a {@code quicraft:binding} payload decoded now belongs to a {@code /3} exchange: only
     * a connection with the multi-stream handler that has not yet activated or failed. Everything
     * else decodes exactly as vanilla does (the bytes are discarded).
     */
    public static boolean bindingExpected() {
        Channel channel = CURRENT.get();
        if (channel == null) {
            return false;
        }
        for (java.util.Map.Entry<String, io.netty.channel.ChannelHandler> entry : channel.pipeline()) {
            if (entry.getValue() instanceof Gate && ((Gate) entry.getValue()).expectsBinding()) {
                return true;
            }
        }
        return false;
    }

    /** A pipeline handler that knows whether a binding payload is part of an exchange right now. */
    public interface Gate {
        boolean expectsBinding();
    }
}
