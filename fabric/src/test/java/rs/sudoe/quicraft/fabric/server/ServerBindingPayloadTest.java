// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The binding codec stays out of the game's way when multi-stream is off, and keeps its size rule. */
class ServerBindingPayloadTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void off() {
        ServerBindingPayload.setEnabled(false);
    }

    @Test
    void withMultistreamOffTheCodecIsNeverSelected() {
        assertFalse(ServerBindingPayload.handles(ServerBindingPayload.TYPE.id()),
                "flag off: the game's own codec decodes quicraft:binding");
        ServerBindingPayload.setEnabled(true);
        assertTrue(ServerBindingPayload.handles(ServerBindingPayload.TYPE.id()));
        assertFalse(ServerBindingPayload.handles(Identifier.parse("minecraft:brand")), "no other id, ever");
    }

    @Test
    void anOversizePayloadFailsExactlyAsTheGamesOwnCodecDoes() {
        int max = 16;
        byte[] big = new byte[max + 1];
        IllegalArgumentException vanilla = assertThrows(IllegalArgumentException.class,
                () -> DiscardedPayload.<FriendlyByteBuf>codec(ServerBindingPayload.TYPE.id(), max)
                        .decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(big))));

        IllegalArgumentException ours = assertThrows(IllegalArgumentException.class,
                () -> ServerBindingPayload.<FriendlyByteBuf>codec(max)
                        .decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(big))));

        assertEquals(vanilla.getMessage(), ours.getMessage());
    }

    @Test
    void aPayloadWithinTheGamesLimitKeepsItsBytes() {
        byte[] data = new byte[ServerBindingPayload.LENGTH];
        data[5] = 42;

        ServerBindingPayload decoded = ServerBindingPayload.<FriendlyByteBuf>codec(32767)
                .decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));

        assertArrayEquals(data, decoded.data());
    }
}
