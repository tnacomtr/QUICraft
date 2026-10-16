// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.crypto.Cipher;

/**
 * The cipher and framing cutover of docs/protocol.md 12.10 to 12.13 for one game pipeline: the
 * three pipeline changes the switch between {@code /3}'s records and Minecraft's AES/CFB8 stream
 * needs, in the order the spec gives them.
 *
 * <ol>
 *   <li><b>Encryption start</b> ({@link #install}): vanilla's cipher decoder decrypts whatever it
 *       reads, which is not safe at the switch-packet boundary (12.13). This replaces it with
 *       {@link BoundaryCipherDecoder} at the same pipeline position and with the same
 *       {@link Cipher} instance, so the cipher position still matches the peer's; the first byte it
 *       sees must be the first encrypted byte, exactly as with vanilla's handler.</li>
 *   <li><b>Inbound cutover</b> ({@link #stopAndTakeRemainder}): called by the handler that sees the
 *       peer's final switch packet, synchronously inside that frame's {@code fireChannelRead}, or
 *       when nothing is buffered at all. It hands out the ciphertext after that frame untouched,
 *       which is the first byte of the peer's record stream (12.11 to 12.13).</li>
 *   <li><b>Outbound cutover</b> ({@link #outboundCutover}): removes the game's cipher encoder, and
 *       only once the last encrypted frame was really written. Removing it earlier would leave
 *       earlier writes unencrypted, which the peer cannot decrypt.</li>
 * </ol>
 *
 * <p>Handler names are the platform's, because which handler holds the cipher differs per game
 * version. The cipher state is taken out of the game's own handler: a version whose handler keeps
 * it in a field of type {@link Cipher} (under any name) or behind a no-argument accessor needs no
 * further help, and a version that keeps it anywhere else can be handed the cipher directly with
 * the four-argument {@link #install}.</p>
 *
 * <p>Threading: all three cutover steps run on the channel's event loop. Nothing here is safe to
 * call afterwards except {@link #pipeline()} and {@link #boundaryDecoder()}.</p>
 */
public final class CipherCutover {
    private final ChannelPipeline pipeline;
    private final String decryptHandlerName;
    private final String encryptHandlerName;
    private final BoundaryCipherDecoder decoder;

    private CipherCutover(ChannelPipeline pipeline, String decryptHandlerName, String encryptHandlerName,
            BoundaryCipherDecoder decoder) {
        this.pipeline = pipeline;
        this.decryptHandlerName = decryptHandlerName;
        this.encryptHandlerName = encryptHandlerName;
        this.decoder = decoder;
    }

    /**
     * Step 1 of 12.13: installs the boundary-aware decoder in place of the game's decrypt handler,
     * taking the cipher out of that handler.
     *
     * @param pipeline the game's pipeline, on the channel's event loop
     * @param decryptHandlerName the name of the game's decryption handler, e.g. what vanilla installs
     *     where it starts encryption; it must hold its {@link Cipher}
     * @param encryptHandlerName the name of the game's encryption handler, removed at the outbound
     *     cutover; null when the platform removes it itself
     * @throws IllegalStateException when there is no such handler, or no cipher to take from it
     */
    public static CipherCutover install(ChannelPipeline pipeline, String decryptHandlerName,
            String encryptHandlerName) {
        return install(pipeline, decryptHandlerName, encryptHandlerName, null);
    }

    /**
     * As {@link #install(ChannelPipeline, String, String)}, with the cipher supplied instead of
     * taken from the handler.
     *
     * @param decryptCipher the cipher the game's decrypt handler is using, in DECRYPT mode and at
     *     the peer's current position; null means "take it out of the handler". The decoder takes
     *     ownership of it, exactly as with vanilla's handler
     */
    public static CipherCutover install(ChannelPipeline pipeline, String decryptHandlerName,
            String encryptHandlerName, Cipher decryptCipher) {
        if (pipeline == null || decryptHandlerName == null) {
            throw new IllegalArgumentException("pipeline/decryptHandlerName");
        }
        ChannelHandler decryptHandler = pipeline.get(decryptHandlerName);
        if (decryptHandler == null) {
            throw new IllegalStateException("no pipeline handler named " + decryptHandlerName);
        }
        Cipher cipher = decryptCipher != null ? decryptCipher : cipherOf(decryptHandler);
        BoundaryCipherDecoder decoder = new BoundaryCipherDecoder(cipher);
        // Same name, same position: downstream handlers (the frame splitter) keep working unchanged.
        pipeline.replace(decryptHandlerName, decryptHandlerName, decoder);
        return new CipherCutover(pipeline, decryptHandlerName, encryptHandlerName, decoder);
    }

    /**
     * The {@link Cipher} an encryption handler holds, wherever it keeps it: a field of that type
     * (private, any name, any superclass) or a no-argument accessor returning one. Never the game's
     * classes themselves: only the object it was handed.
     *
     * @throws IllegalStateException when the handler holds no cipher this can find
     */
    public static Cipher cipherOf(Object handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Cipher.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(handler);
                    if (value instanceof Cipher) {
                        return (Cipher) value;
                    }
                } catch (Throwable ignored) {
                    // Try the rest: an unreadable field is not a reason to give up.
                }
            }
        }
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0
                        || !Cipher.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(handler);
                    if (value instanceof Cipher) {
                        return (Cipher) value;
                    }
                } catch (Throwable ignored) {
                    // As above.
                }
            }
        }
        throw new IllegalStateException("no javax.crypto.Cipher in " + handler.getClass().getName()
                + "; pass the cipher to install(...) instead");
    }

    /** The handler the boundary decoder was installed as. */
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    /** The name of the game's decrypt handler, which the boundary decoder replaced. */
    public String decryptHandlerName() {
        return decryptHandlerName;
    }

    /** The installed decoder, for the platform's own diagnostics. */
    public BoundaryCipherDecoder boundaryDecoder() {
        return decoder;
    }

    /**
     * Step 2 of 12.13: the inbound cutover. Call it on the channel's event loop, synchronously
     * inside the {@code fireChannelRead} of the peer's final encrypted frame, or at any time when
     * the decoder holds no partial frame.
     *
     * <p>It stops the decoder after the frame it is delivering and returns the ciphertext after it,
     * untouched and undecrypted: on the wire those bytes are the peer's first record (12.11, 12.12).
     * The caller owns the returned buffer (refCnt 1, possibly empty) and must release it. Nothing is
     * fired downstream. A second call, a call while a frame is half-read, or a call after a decoder
     * failure throws {@link IllegalStateException}, exactly as {@link BoundaryCipherDecoder} says.
     *
     * @throws IllegalStateException off the channel's event loop
     */
    public ByteBuf stopAndTakeRemainder() {
        if (!pipeline.channel().eventLoop().inEventLoop()) {
            throw new IllegalStateException("the inbound cutover runs on the channel's event loop (12.10)");
        }
        return decoder.stopAndTakeRemainder();
    }

    /**
     * Step 3 of 12.13: the outbound cutover. Removes the game's encrypt handler, but only after
     * {@code lastEncryptedFrame} succeeded: every write before it was encrypted by that handler, and
     * removing it earlier would put earlier frames on the wire unencrypted. The removal runs on the
     * channel's event loop, right after the write completed there.
     *
     * <p>A failed last write fires {@code exceptionCaught} with its cause and removes nothing: at
     * that point the connection cannot continue half-switched, and the pipeline's exception handler
     * closes it (12.14).</p>
     *
     * @param lastEncryptedFrame the write of the peer's final encrypted frame (the switch packet);
     *     its completion is what releases the handler
     */
    public void outboundCutover(ChannelFuture lastEncryptedFrame) {
        if (lastEncryptedFrame == null) {
            throw new IllegalArgumentException("lastEncryptedFrame");
        }
        lastEncryptedFrame.addListener(future -> {
            if (!future.isSuccess()) {
                pipeline.fireExceptionCaught(future.cause() != null ? future.cause()
                        : new IllegalStateException("the last encrypted frame was not written"));
                return;
            }
            removeEncryptHandler();
        });
    }

    /** Removes the game's encrypt handler now, on the event loop (13.3's "same task" case). */
    public void removeEncryptHandler() {
        if (encryptHandlerName == null || pipeline.get(encryptHandlerName) == null) {
            return;
        }
        if (pipeline.channel().eventLoop().inEventLoop()) {
            pipeline.remove(encryptHandlerName);
        } else {
            pipeline.channel().eventLoop().execute(() -> {
                if (pipeline.get(encryptHandlerName) != null) {
                    pipeline.remove(encryptHandlerName);
                }
            });
        }
    }

    /** The handler this cutover removes outbound, or null when the platform does it itself. */
    public String encryptHandlerName() {
        return encryptHandlerName;
    }
}
