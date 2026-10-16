// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cipher and framing cutover of docs/protocol.md 12.10 to 12.13, with a real AES/CFB8 cipher
 * pair: the decoder is installed with the game handler's own cipher instance, stopped exactly at
 * the last encrypted frame's boundary, and the game's encrypt handler is removed only after the
 * last encrypted frame was written.
 */
class CipherCutoverTest {
    private static EventLoopGroup loop;

    @BeforeAll
    static void setUp() {
        loop = new NioEventLoopGroup(1);
    }

    @AfterAll
    static void tearDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void installsTheBoundaryDecoderWithTheGameHandlersOwnCipher() throws Exception {
        byte[] secret = secret(1);
        Cipher decrypt = cipher(Cipher.DECRYPT_MODE, secret);
        GameDecryptHandler gameHandler = new GameDecryptHandler(decrypt);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("decrypt", gameHandler);

        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt");

        assertTrue(channel.pipeline().get("decrypt") instanceof BoundaryCipherDecoder,
                "12.13: vanilla's decrypt handler is replaced, at its own position");
        assertEquals("decrypt", cutover.decryptHandlerName());
        assertEquals("encrypt", cutover.encryptHandlerName());
        assertSame(decrypt, CipherCutover.cipherOf(gameHandler),
                "the decoder was given the handler's own cipher, so the position matches the peer's");
        channel.finishAndReleaseAll();
    }

    @Test
    void stopAtTheFrameBoundaryKeepsTheRecordBytesUntouched() throws Exception {
        byte[] secret = secret(2);
        Cipher encrypt = cipher(Cipher.ENCRYPT_MODE, secret);
        Cipher decrypt = cipher(Cipher.DECRYPT_MODE, secret);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("decrypt", new GameDecryptHandler(decrypt));
        Stopper stopper = new Stopper(2);
        channel.pipeline().addLast(stopper);
        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt");
        stopper.cutover = cutover;

        // 12.13's coalesced read: the two encrypted frames and, right behind them, the first bytes
        // of the peer's record stream in the very same read.
        byte[] frame1 = frame(new byte[] {1, 2, 3});
        byte[] frame2 = frame(new byte[] {4, 5});
        byte[] records = new byte[] {0x77, 0x66, 0x55};
        byte[] ciphertext = encrypt.doFinal(concat(frame1, frame2));
        channel.writeInbound(Unpooled.wrappedBuffer(concat(ciphertext, records)));

        assertNull(stopper.error.get(), String.valueOf(stopper.error.get()));
        assertEquals(2, stopper.frames.size());
        assertArrayEquals(frame1, stopper.frames.get(0), "each frame arrives whole and decrypted");
        assertArrayEquals(frame2, stopper.frames.get(1));
        ByteBuf remainder = stopper.remainder.get();
        assertNotNull(remainder, "the cutover hands out what follows the last encrypted frame");
        try {
            assertEquals(1, remainder.refCnt());
            assertArrayEquals(records, bytes(remainder),
                    "12.13: the record bytes stay untouched and undecrypted");
        } finally {
            remainder.release();
        }
        assertNull(channel.pipeline().get("decrypt"), "the decoder removed itself after the stop");

        // The very next byte is the record stream: with the decoder gone, it arrives as it is.
        channel.writeInbound(Unpooled.wrappedBuffer(records));
        assertArrayEquals(records, stopper.frames.get(2));

        assertThrows(IllegalStateException.class, cutover::stopAndTakeRemainder,
                "a second stop is a programming error, exactly as BoundaryCipherDecoder says");
        channel.checkException();
    }

    @Test
    void stopWhenNothingIsBufferedReturnsAnEmptyOwnedBuffer() throws Exception {
        byte[] secret = secret(3);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("decrypt", new GameDecryptHandler(cipher(Cipher.DECRYPT_MODE, secret)));
        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt");

        ByteBuf remainder = cutover.stopAndTakeRemainder();

        try {
            assertEquals(1, remainder.refCnt(), "the caller owns it");
            assertEquals(0, remainder.readableBytes());
        } finally {
            remainder.release();
        }
        channel.checkException();
    }

    @Test
    void aStopMidFrameIsRefused() throws Exception {
        byte[] secret = secret(4);
        Cipher encrypt = cipher(Cipher.ENCRYPT_MODE, secret);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("decrypt", new GameDecryptHandler(cipher(Cipher.DECRYPT_MODE, secret)));
        channel.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter());
        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt");

        // A frame header promising ten bytes, with only three of them sent.
        channel.writeInbound(Unpooled.wrappedBuffer(encrypt.update(new byte[] {10, 1, 2, 3})));

        assertThrows(IllegalStateException.class, cutover::stopAndTakeRemainder,
                "12.13: a stop inside a frame cannot split it");
        channel.checkException();
    }

    @Test
    void stopOffTheEventLoopIsRefused() throws Exception {
        QuicBridgeChannel channel =
                new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        channel.eventLoop().submit(() -> channel.pipeline().addLast("decrypt",
                new GameDecryptHandler(cipher(Cipher.DECRYPT_MODE, secret(5))))).sync();
        AtomicReference<CipherCutover> installed = new AtomicReference<>();
        channel.eventLoop().submit(() -> installed.set(
                CipherCutover.install(channel.pipeline(), "decrypt", "encrypt"))).sync();

        assertThrows(IllegalStateException.class, installed.get()::stopAndTakeRemainder,
                "12.10: the cutover runs on the connection's event loop");
        channel.close().sync();
    }

    @Test
    void outboundCutoverRemovesTheEncryptHandlerOnlyAfterTheLastEncryptedFrameIsWritten()
            throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        EncryptHandler encrypt = new EncryptHandler();
        channel.pipeline().addLast("encrypt", encrypt);
        channel.pipeline().addLast("decrypt", new GameDecryptHandler(cipher(Cipher.DECRYPT_MODE, secret(6))));
        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt",
                cipher(Cipher.DECRYPT_MODE, secret(6)));

        ChannelPromise lastEncryptedFrame = channel.newPromise();
        cutover.outboundCutover(lastEncryptedFrame);

        assertTrue(channel.pipeline().get("encrypt") instanceof EncryptHandler,
                "12.11/12.12: the handler stays until the last encrypted frame is written");
        lastEncryptedFrame.setSuccess();
        assertNull(channel.pipeline().get("encrypt"),
                "and goes as soon as that write completed");

        channel.checkException();
    }

    @Test
    void aFailedLastEncryptedFrameRemovesNothingAndReportsTheCause() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("encrypt", new EncryptHandler());
        channel.pipeline().addLast("decrypt", new GameDecryptHandler(cipher(Cipher.DECRYPT_MODE, secret(7))));
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });
        CipherCutover cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt",
                cipher(Cipher.DECRYPT_MODE, secret(7)));

        ChannelPromise lastEncryptedFrame = channel.newPromise();
        cutover.outboundCutover(lastEncryptedFrame);
        IllegalStateException failure = new IllegalStateException("the peer went away");
        lastEncryptedFrame.setFailure(failure);

        assertNotNull(channel.pipeline().get("encrypt"),
                "12.12/12.14: never continue half-switched");
        assertSame(failure, seen.get());
        channel.checkException();
    }

    @Test
    void anUnknownHandlerOrACipherlessHandlerIsRefused() {
        EmbeddedChannel channel = new EmbeddedChannel();
        assertThrows(IllegalStateException.class,
                () -> CipherCutover.install(channel.pipeline(), "decrypt", "encrypt"));
        channel.pipeline().addLast("decrypt", new ChannelInboundHandlerAdapter());
        assertThrows(IllegalStateException.class,
                () -> CipherCutover.install(channel.pipeline(), "decrypt", "encrypt"),
                "a handler that holds no cipher needs the explicit-cipher install(...)");
        channel.finishAndReleaseAll();
    }

    /** The game's decryption handler, holding its cipher exactly as the game's own does. */
    static final class GameDecryptHandler extends ChannelInboundHandlerAdapter {
        private final Cipher decryptCipher;

        GameDecryptHandler(Cipher decryptCipher) {
            this.decryptCipher = decryptCipher;
        }
    }

    /** The game's encryption handler, which the outbound cutover removes. */
    static final class EncryptHandler extends ChannelOutboundHandlerAdapter {}

    /** Collects the frames the decoder emitted and stops the cutover after the last one. */
    static final class Stopper extends ChannelInboundHandlerAdapter {
        final List<byte[]> frames = new ArrayList<>();
        final AtomicReference<ByteBuf> remainder = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile CipherCutover cutover;
        private final int stopAfter;

        Stopper(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                byte[] out = new byte[buffer.readableBytes()];
                buffer.readBytes(out);
                frames.add(out);
                if (cutover != null && frames.size() == stopAfter) {
                    try {
                        // Exactly the documented call site: synchronously inside the fireChannelRead
                        // of the last encrypted frame (12.11 to 12.13).
                        remainder.set(cutover.stopAndTakeRemainder());
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            } finally {
                buffer.release();
            }
        }
    }

    private static byte[] secret(int seed) {
        byte[] secret = new byte[16];
        new Random(seed).nextBytes(secret);
        return secret;
    }

    private static Cipher cipher(int mode, byte[] secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        return cipher;
    }

    /** One Minecraft-style frame: VarInt length, then the body. */
    private static byte[] frame(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = body.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] out = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), out);
        return out;
    }
}
