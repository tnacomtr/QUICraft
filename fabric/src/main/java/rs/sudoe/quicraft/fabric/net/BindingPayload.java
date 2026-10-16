// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

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
public record BindingPayload(byte[] data) implements CustomPacketPayload {
    /** The channel of 12.8. */
    public static final String CHANNEL = "quicraft:binding";
    public static final Type<BindingPayload> TYPE = new Type<>(Identifier.parse(CHANNEL));
    /** 12.8: exactly 103 bytes; anything longer is refused while it is read. */
    public static final int LENGTH = 103;

    public static <B extends FriendlyByteBuf> StreamCodec<B, BindingPayload> codec(int maxPayloadSize) {
        int max = Math.min(maxPayloadSize, LENGTH);
        return CustomPacketPayload.codec((payload, buf) -> buf.writeBytes(payload.data), buf -> {
            int length = buf.readableBytes();
            if (length > max) {
                // 12.8: no optional fields or trailing bytes. Not an error here: outside /3 a stray
                // payload must be inert, and on /3 the empty array fails the exchange as malformed.
                buf.skipBytes(length);
                return new BindingPayload(new byte[0]);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BindingPayload(data);
        });
    }

    @Override
    public Type<BindingPayload> type() {
        return TYPE;
    }
}
