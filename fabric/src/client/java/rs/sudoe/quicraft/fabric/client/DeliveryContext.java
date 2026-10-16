// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * Where decoded lane packets enter the game's pipeline: the slot of the packet decoder.
 *
 * <p>Vanilla replaces that handler when the protocol changes, and the old handler's context keeps
 * pointing at whatever followed it at the time, so a context taken once would skip the bundler the
 * next protocol inserts (docs/protocol.md 13.15: a bundle is repacked by the game's own handler).
 * This context looks the slot up on every call instead: {@code decoder} while a protocol is set,
 * {@code inbound_config} in the instant after a terminal packet swapped it out. Both fire to the
 * same next handler, which is what the packet decoded in that slot would have been fired to.</p>
 */
final class DeliveryContext {
    private DeliveryContext() {}

    static ChannelHandlerContext of(ChannelPipeline pipeline) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return "DeliveryContext";
                }
            }
            try {
                return method.invoke(slot(pipeline), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (ChannelHandlerContext) Proxy.newProxyInstance(ChannelHandlerContext.class.getClassLoader(),
                new Class<?>[] {ChannelHandlerContext.class}, handler);
    }

    /** The context a decoded packet is fired from right now. */
    static ChannelHandlerContext slot(ChannelPipeline pipeline) {
        ChannelHandlerContext slot = pipeline.context("decoder");
        if (slot == null) {
            slot = pipeline.context("inbound_config");
        }
        if (slot == null) {
            throw new IllegalStateException("the game pipeline has no decoder slot (13.16)");
        }
        return slot;
    }
}
