// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingFormatException;
import rs.sudoe.quicraft.core.binding.BindingMessage;
import rs.sudoe.quicraft.fabric.client.DecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingPayload;

/** The shared binding codec keeps bytes and the size rule; the connection context, not a flag, selects it. */
class BindingPayloadTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void off() {
        BindingDecodeContext.enter(null);
    }

    @Test
    void aPayloadWithinTheGamesLimitKeepsItsBytes() {
        byte[] data = new byte[BindingMessage.ENCODED_LENGTH];
        data[5] = 42;

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            BindingPayload decoded = BindingPayload.<FriendlyByteBuf>codec().decode(buf);
            assertArrayEquals(data, decoded.data());
        } finally {
            assertEquals(1, buf.refCnt(), "the decode left the buffer for its owner");
            buf.release();
        }
    }

    /** Sizes below and above the exact 104 keep their bytes, and the validator rejects them by length. */
    @Test
    void shorterAndLongerPayloadsKeepTheirBytesForTheValidatorToReject() {
        for (int length : new int[] {0, 103, 105}) {
            byte[] data = new byte[length];
            if (length > 5) { data[5] = 42; }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            try {
                BindingPayload decoded = BindingPayload.<FriendlyByteBuf>codec().decode(buf);
                assertEquals(length, decoded.data().length, "size " + length + ": the payload lost its bytes");
                assertArrayEquals(data, decoded.data(), "size " + length + ": the payload changed its bytes");
                assertThrows(BindingFormatException.class, () -> BindingMessage.decode(decoded.data()),
                        "length " + length + ": the validator must reject a non-104-byte message");
            } finally {
                assertEquals(1, buf.refCnt(), "size " + length + ": the decode left the buffer for its owner");
                buf.release();
            }
        }
    }

    /** One byte past the exact length fails the shared codec with its exact message. */
    @Test
    void moreThanOneBytePastTheExactLengthFailsTheSharedCodecExactly() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[106]));
        try {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> BindingPayload.<FriendlyByteBuf>codec().decode(buf));
            assertEquals("quicraft:binding payload too large: 106", thrown.getMessage());
        } finally {
            assertEquals(1, buf.refCnt(), "the failed decode left the buffer for its owner");
            buf.release();
        }
    }

    @Test
    void theConnectionContextAndNotAnyFlagSelectsTheSharedCodec() {
        assertFalse(BindingDecodeContext.bindingExpected(), "no channel entered yet");
        assertFalse(DecodeContext.bindingExpected(), "the client facade reads the same empty context");

        EmbeddedChannel expecting = new EmbeddedChannel(new Gate());
        DecodeContext.enter(expecting);
        assertSame(expecting, BindingDecodeContext.current());
        assertTrue(DecodeContext.bindingExpected(), "the pipeline gate's channel is entered");
        assertTrue(BindingDecodeContext.bindingExpected(), "the common context reads the same selection");
        DecodeContext.enter(null);
        assertFalse(BindingDecodeContext.bindingExpected(), "the entry is removed again");

        EmbeddedChannel plain = new EmbeddedChannel();
        BindingDecodeContext.enter(plain);
        assertFalse(BindingDecodeContext.bindingExpected(), "a channel without any gate");
        BindingDecodeContext.enter(null);
    }

    /** The stand-in pipeline handler that says a /3 exchange is live on its connection. */
    private static final class Gate extends ChannelInboundHandlerAdapter implements DecodeContext.Gate {
        @Override public boolean expectsBinding() { return true; }
    }
}
