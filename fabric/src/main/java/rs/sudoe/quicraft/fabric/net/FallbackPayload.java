// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import rs.sudoe.quicraft.core.Protocol;

/**
 * The {@code quicraft:fallback} plugin message (docs/protocol.md §6) as raw bytes; core's
 * {@code FallbackReport} encodes and decodes them. Vanilla drops the bytes of unknown payloads,
 * so {@code DiscardedPayloadMixin} hands out this codec for our channel instead.
 */
public record FallbackPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<FallbackPayload> TYPE = new Type<>(Identifier.parse(Protocol.FALLBACK_CHANNEL));

    public static <B extends FriendlyByteBuf> StreamCodec<B, FallbackPayload> codec(int maxPayloadSize) {
        return CustomPacketPayload.codec((payload, buf) -> buf.writeBytes(payload.data), buf -> {
            int length = buf.readableBytes();
            if (length > maxPayloadSize) {
                throw new IllegalArgumentException("Payload may not be larger than " + maxPayloadSize + " bytes");
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new FallbackPayload(data);
        });
    }

    @Override
    public Type<FallbackPayload> type() {
        return TYPE;
    }
}
