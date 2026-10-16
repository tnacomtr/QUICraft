// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The {@code quicraft:binding} configuration payload (docs/protocol.md 12.8) as raw bytes; core's
 * binding session builds and checks them. Vanilla drops the bytes of unknown payloads, so
 * {@code DiscardedPayloadMixin} hands out this codec for our channel. The server's packet handler
 * takes every one before any other mod or plugin can see it.
 */
public record ServerBindingPayload(byte[] data) implements CustomPacketPayload {
    /** The channel of 12.8. */
    public static final String CHANNEL = "quicraft:binding";
    public static final Type<ServerBindingPayload> TYPE = new Type<>(Identifier.parse(CHANNEL));
    /** 12.8: exactly 103 bytes. */
    public static final int LENGTH = 103;

    /**
     * Whether {@link #codec} stands in for the game's discarding codec. Off until a {@code /3}
     * listener (or client) is actually running: with multi-stream off, and for every other payload
     * id, the game's own codec is untouched and behaves exactly as vanilla.
     */
    private static volatile boolean enabled;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** True when this class's codec must be used for {@code id}. */
    public static boolean handles(Identifier id) {
        return enabled && TYPE.id().equals(id);
    }

    /**
     * The codec for {@code quicraft:binding}. It keeps the payload's bytes, with vanilla's own size
     * rule: more than {@code maxPayloadSize} is the very exception the game's discarding codec throws.
     * A payload that is not the 103 bytes of 12.8 is kept as it is and fails the exchange as malformed.
     */
    public static <B extends FriendlyByteBuf> StreamCodec<B, ServerBindingPayload> codec(int maxPayloadSize) {
        return CustomPacketPayload.codec((payload, buf) -> buf.writeBytes(payload.data), buf -> {
            int length = buf.readableBytes();
            if (length < 0 || length > maxPayloadSize) {
                throw new IllegalArgumentException("Payload may not be larger than " + maxPayloadSize + " bytes");
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ServerBindingPayload(data);
        });
    }

    @Override
    public Type<ServerBindingPayload> type() {
        return TYPE;
    }
}
