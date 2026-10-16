// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import rs.sudoe.quicraft.core.binding.BindingMessage;

/**
 * The {@code quicraft:binding} configuration payload of docs/protocol.md 12.8 as raw bytes; core's
 * {@code BindingMessage} validates the exact 104-byte layout. It exists only so the bytes survive
 * decoding (vanilla drops the bytes of unknown payloads): an adapter pipeline handler takes it
 * off the wire on the connection's event loop, so no packet listener, mod or plugin ever sees one.
 */
public record BindingPayload(byte[] data) implements CustomPacketPayload {
    public static final String CHANNEL = "quicraft:binding";
    public static final Type<BindingPayload> TYPE = new Type<>(Identifier.parse(CHANNEL));

    public static <B extends FriendlyByteBuf> StreamCodec<B, BindingPayload> codec() {
        return CustomPacketPayload.codec((payload, buf) -> buf.writeBytes(payload.data), buf -> {
            int length = buf.readableBytes();
            // One byte more than the exact length, so a longer payload still reaches the
            // validator's length check and fails there rather than allocating for a large one.
            if (length > BindingMessage.ENCODED_LENGTH + 1) {
                throw new IllegalArgumentException("quicraft:binding payload too large: " + length);
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
