// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Inbound handler that decrypts Minecraft's AES/CFB8 stream only up to the end of each complete
 * length-prefixed frame and can be stopped exactly at a frame boundary (docs/protocol.md 12.13).
 *
 * <p>Wire format: frames of {@code VarInt(length) || body(length bytes)} (VarInt at most 3 bytes),
 * the whole byte stream encrypted continuously. Output: one {@code fireChannelRead} per complete
 * frame carrying the decrypted bytes <em>including</em> the VarInt prefix, i.e. what vanilla's
 * cipher decoder would produce, so vanilla's frame splitter keeps working downstream. A partial
 * frame is never emitted. Ciphertext of later frames stays undecrypted in the cumulation; the
 * cipher is never advanced past the end of the current frame.
 *
 * <p>{@link #stop()} / {@link #stopAndTakeRemainder()} are called by a downstream handler,
 * synchronously inside the {@code fireChannelRead} of the last encrypted frame, on the channel's
 * event loop (all methods are event-loop only). A partial frame at stop time is impossible by
 * construction (stop happens only after a complete frame has been emitted); a stop request
 * while a frame is half-read is a programming error and throws {@link IllegalStateException}.
 *
 * <p>Buffer ownership: inbound {@code ByteBuf}s are consumed (released) by this handler. Emitted
 * frames are owned by the downstream receiver. With {@code stop()} the untouched remainder is
 * fired downstream as one raw {@code ByteBuf} (owned by the receiver) and the handler removes
 * itself. With {@code stopAndTakeRemainder()} the caller owns the returned buffer (refCnt 1, must
 * be released; may be empty), nothing is fired and the handler removes itself. Un-stopped
 * removal or channel close releases the cumulation.
 */
public final class BoundaryCipherDecoder extends ChannelInboundHandlerAdapter {
    /** Malformed framing (VarInt longer than 3 bytes). */
    public static final class MalformedFrameException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MalformedFrameException(String message) {
            super(message);
        }
    }

    private static final int MAX_VARINT_BYTES = 3;

    private final Cipher cipher;
    private ChannelHandlerContext ctx;
    private ByteBuf cumulation;
    private final byte[] prefix = new byte[MAX_VARINT_BYTES];
    private int prefixLen;
    private int prefixValue;
    private long decryptedBytes;
    private boolean inLoop;
    private boolean stopRequested;
    private boolean takeRemainder;
    private ByteBuf taken;
    private boolean finished;
    private boolean failed;

    /** @param decryptCipher an initialised AES/CFB8/NoPadding cipher in DECRYPT mode; owned by this handler */
    public BoundaryCipherDecoder(Cipher decryptCipher) {
        this.cipher = decryptCipher;
    }

    /** Builds the vanilla cipher: AES/CFB8/NoPadding, key = IV = the 16-byte shared secret (cloned). */
    public static BoundaryCipherDecoder forSharedSecret(byte[] secret) {
        byte[] s = secret.clone();
        try {
            Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(s, "AES"), new IvParameterSpec(s));
            return new BoundaryCipherDecoder(c);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Number of ciphertext bytes passed through the cipher so far (test hook). */
    long decryptedByteCount() {
        return decryptedBytes;
    }

    /**
     * Stop after the frame currently being delivered: the undecrypted remainder is fired
     * downstream raw and this handler removes itself.
     */
    public void stop() {
        requestStop(false);
    }

    /**
     * Stop after the frame currently being delivered and return the undecrypted remainder
     * (possibly empty) to the caller, who owns and must release it. Nothing is fired.
     * This handler removes itself. Call only once.
     */
    public ByteBuf stopAndTakeRemainder() {
        requestStop(true);
        if (inLoop) {
            // The loop finishes after the in-flight fireChannelRead returns; hand out now.
            ByteBuf r = cumulation == null ? ctx.alloc().buffer(0) : cumulation;
            cumulation = null;
            taken = r;
            return r;
        }
        return taken;
    }

    private void requestStop(boolean take) {
        if (ctx == null) {
            throw new IllegalStateException("not added to a pipeline");
        }
        if (stopRequested || finished) {
            throw new IllegalStateException("already stopped");
        }
        if (prefixLen != 0 && !inLoop) {
            throw new IllegalStateException("stop inside a frame");
        }
        stopRequested = true;
        takeRemainder = take;
        if (!inLoop) {
            finishStop();
        }
    }

    private void finishStop() {
        finished = true;
        ByteBuf rest = cumulation;
        cumulation = null;
        if (takeRemainder) {
            if (taken == null) {
                taken = rest == null ? ctx.alloc().buffer(0) : rest;
            } else if (rest != null) {
                rest.release();
            }
        } else if (rest != null) {
            if (rest.isReadable()) {
                ctx.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }
        ctx.pipeline().remove(this);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext c) {
        this.ctx = c;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext c) {
        release();
    }

    @Override
    public void channelInactive(ChannelHandlerContext c) throws Exception {
        release();
        super.channelInactive(c);
    }

    private void release() {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext c, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            c.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (finished) {
            c.fireChannelRead(in);
            return;
        }
        if (failed) {
            in.release();
            return;
        }
        if (cumulation == null) {
            cumulation = in;
        } else {
            try {
                if (cumulation.isWritable(in.readableBytes()) && cumulation.refCnt() == 1) {
                    cumulation.writeBytes(in);
                } else {
                    // Adopted buffers (e.g. wrapped ones) may not grow: copy into our own.
                    ByteBuf merged = c.alloc().buffer(cumulation.readableBytes() + in.readableBytes());
                    merged.writeBytes(cumulation);
                    merged.writeBytes(in);
                    cumulation.release();
                    cumulation = merged;
                }
            } finally {
                in.release();
            }
        }
        inLoop = true;
        try {
            while (!stopRequested) {
                ByteBuf frame = nextFrame();
                if (frame == null) {
                    break;
                }
                c.fireChannelRead(frame);
            }
        } catch (RuntimeException e) {
            failed = true;
            release();
            throw e;
        } finally {
            inLoop = false;
        }
        if (stopRequested) {
            assert prefixLen == 0 : "stop inside a frame";
            finishStop();
        } else if (cumulation != null) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                cumulation = null;
            } else {
                cumulation.discardSomeReadBytes();
            }
        }
    }

    /** Returns the next complete decrypted frame, or null if more ciphertext is needed. */
    private ByteBuf nextFrame() {
        ByteBuf cum = cumulation;
        if (cum == null) {
            return null;
        }
        // VarInt prefix, one byte per cipher call, at most 3.
        while (true) {
            if (prefixLen > 0 && (prefix[prefixLen - 1] & 0x80) == 0) {
                break;
            }
            if (!cum.isReadable()) {
                return null;
            }
            if (prefixLen == MAX_VARINT_BYTES) {
                throw new MalformedFrameException("VarInt longer than 3 bytes");
            }
            byte[] one = new byte[] {cum.readByte()};
            decrypt(one, one.length);
            prefix[prefixLen] = one[0];
            prefixValue |= (one[0] & 0x7F) << (7 * prefixLen);
            prefixLen++;
        }
        int length = prefixValue;
        if (cum.readableBytes() < length) {
            return null;
        }
        ByteBuf out = ctx.alloc().buffer(prefixLen + length);
        try {
            out.writeBytes(prefix, 0, prefixLen);
            if (length > 0) {
                byte[] body = new byte[length];
                cum.readBytes(body);
                decrypt(body, length);
                out.writeBytes(body);
            }
        } catch (RuntimeException e) {
            out.release();
            throw e;
        }
        prefixLen = 0;
        prefixValue = 0;
        return out;
    }

    private void decrypt(byte[] buf, int n) {
        try {
            int done = cipher.update(buf, 0, n, buf, 0);
            if (done != n) {
                throw new IllegalStateException("CFB8 produced " + done + " of " + n + " bytes");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        decryptedBytes += n;
    }
}
