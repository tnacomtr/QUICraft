// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.ByteBuffer;
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
 * <p>Install this handler at the moment encryption starts (where vanilla installs its cipher
 * decoder), so the cipher position matches the peer's: the first byte it sees must be the first
 * encrypted byte.
 *
 * <p>Threading: all methods run on the channel's event loop. {@link #stop()} /
 * {@link #stopAndTakeRemainder()} are called by a downstream handler, synchronously inside the
 * {@code fireChannelRead} of the last encrypted frame. A partial frame at stop time is impossible
 * by construction (stop happens only after a complete frame has been emitted); a stop request
 * while a frame is half-read, a second stop, or a stop after a failure is a programming error and
 * throws {@link IllegalStateException}. A stop after the handler was removed from the pipeline by
 * someone else is accepted and hands out nothing (the removal released the buffered data).
 *
 * <p>Buffer ownership: inbound {@code ByteBuf}s are consumed (released) by this handler. Emitted
 * frames are owned by the downstream receiver. With {@code stop()} the untouched remainder is
 * fired downstream as one raw {@code ByteBuf} (owned by the receiver) and the handler removes
 * itself. With {@code stopAndTakeRemainder()} the caller owns the returned buffer (refCnt 1, must
 * be released; may be empty), nothing is fired and the handler removes itself; the caller must
 * then request the next read itself when auto-read is off. Un-stopped removal or channel close
 * releases the cumulation.
 *
 * <p>Failure: on malformed framing, a cipher error or an allocation failure the handler releases
 * its buffers, fires {@code exceptionCaught} exactly once, and drops all later input. It does not
 * close the channel; the pipeline's exception handler must close it.
 *
 * <p>Auto-read: when the channel's auto-read is off and a read delivered only a partial frame, so
 * nothing reached the consumer, the handler requests the next read itself; otherwise the stream
 * would stall.
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
    /** True once the cumulation is a buffer this handler allocated and may grow in place. */
    private boolean cumulationOwned;
    private final byte[] prefix = new byte[MAX_VARINT_BYTES];
    /** Scratch for single prefix bytes, so no array is allocated per byte. */
    private final byte[] oneByte = new byte[1];
    private int prefixLen;
    private int prefixValue;
    private long decryptedBytes;
    private boolean inLoop;
    private boolean stopRequested;
    private boolean takeRemainder;
    private ByteBuf taken;
    private boolean finished;
    private boolean failed;
    /** A ByteBuf arrived in the current read cycle. */
    private boolean receivedThisRead;
    /** Something was fired downstream in the current read cycle. */
    private boolean firedThisRead;

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
     * (possibly empty) to the caller, who owns it (refCnt 1) and must release it. Nothing is
     * fired. This handler removes itself. Call only once.
     */
    public ByteBuf stopAndTakeRemainder() {
        requestStop(true);
        // Inside the read loop the remainder is handed out now (the loop finishes after the
        // in-flight fireChannelRead returns); outside it finishStop() already produced it.
        if (inLoop) {
            // Clear the field first: detach() releases its argument when it fails.
            ByteBuf rest = cumulation;
            cumulation = null;
            ByteBuf r = detach(rest);
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
        if (failed) {
            throw new IllegalStateException("decoder failed");
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

    /** Returns a buffer holding {@code rest} with refCnt exactly 1 that the caller can own. */
    private ByteBuf detach(ByteBuf rest) {
        if (rest == null) {
            return ctx.alloc().buffer(0);
        }
        if (rest.refCnt() == 1) {
            return rest;
        }
        // A slice or duplicate of a buffer someone else also holds: give our reference back and
        // hand out an independent copy, so the caller's single release() frees it.
        try {
            ByteBuf copy = ctx.alloc().buffer(rest.readableBytes());
            copy.writeBytes(rest);
            return copy;
        } finally {
            rest.release();
        }
    }

    private void finishStop() {
        finished = true;
        ByteBuf rest = cumulation;
        cumulation = null;
        if (takeRemainder) {
            if (taken == null) {
                taken = detach(rest);
            } else if (rest != null) {
                rest.release();
            }
        } else if (rest != null) {
            if (rest.isReadable()) {
                firedThisRead = true;
                ctx.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }
        if (!ctx.isRemoved()) {
            ctx.pipeline().remove(this);
        }
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

    private void fail(ChannelHandlerContext c, Throwable t) {
        failed = true;
        release();
        c.fireExceptionCaught(t);
    }

    @Override
    public void channelRead(ChannelHandlerContext c, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            // Only ByteBufs carry ciphertext; anything else (user messages from other handlers) is
            // not ours to interpret and travels on unchanged.
            firedThisRead = true;
            c.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (finished) {
            firedThisRead = true;
            c.fireChannelRead(in);
            return;
        }
        if (failed) {
            in.release();
            return;
        }
        receivedThisRead = true;
        if (cumulation == null) {
            cumulation = in;
            cumulationOwned = false;
        } else {
            ByteBuf merged = null;
            try {
                if (cumulationOwned) {
                    // Our own buffer: Netty grows it geometrically, so appending is amortised O(1).
                    cumulation.writeBytes(in);
                } else {
                    // The adopted input may be wrapped, read-only or shared: copy once into a buffer
                    // we own. Later reads append to it without further copies of the old bytes.
                    merged = c.alloc().buffer(cumulation.readableBytes() + in.readableBytes());
                    merged.writeBytes(cumulation);
                    merged.writeBytes(in);
                    cumulation.release();
                    cumulation = merged;
                    cumulationOwned = true;
                    merged = null;
                }
            } catch (Throwable t) {
                if (merged != null) {
                    merged.release();
                }
                fail(c, t);
                return;
            } finally {
                in.release();
            }
        }
        inLoop = true;
        try {
            while (!stopRequested && cumulation != null && !c.isRemoved() && c.channel().isOpen()) {
                ByteBuf frame = nextFrame();
                if (frame == null) {
                    break;
                }
                firedThisRead = true;
                c.fireChannelRead(frame);
            }
        } catch (Throwable t) {
            inLoop = false;
            fail(c, t);
            return;
        } finally {
            inLoop = false;
        }
        if (stopRequested) {
            assert prefixLen == 0 : "stop inside a frame";
            finishStop();
        } else if (c.isRemoved() || !c.channel().isOpen()) {
            release();
        } else if (cumulation != null) {
            if (!cumulation.isReadable()) {
                cumulation.release();
                cumulation = null;
            } else if (cumulationOwned) {
                // Only compact a buffer we own: an adopted input may be read-only or shared.
                cumulation.discardSomeReadBytes();
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext c) {
        boolean stalled = receivedThisRead && !firedThisRead && !failed && !finished;
        receivedThisRead = false;
        firedThisRead = false;
        if (stalled && !c.channel().config().isAutoRead()) {
            // Only a partial frame arrived, so no consumer will ask for more: do it ourselves.
            c.read();
        }
        c.fireChannelReadComplete();
    }

    /** Returns the next complete decrypted frame, or null if more ciphertext is needed. */
    private ByteBuf nextFrame() {
        ByteBuf cum = cumulation;
        // VarInt prefix, one byte per cipher call, at most 3.
        while (prefixLen == 0 || (prefix[prefixLen - 1] & 0x80) != 0) {
            if (!cum.isReadable()) {
                return null;
            }
            oneByte[0] = cum.readByte();
            decrypt(oneByte, 1);
            byte b = oneByte[0];
            prefix[prefixLen] = b;
            prefixValue |= (b & 0x7F) << (7 * prefixLen);
            prefixLen++;
            if (prefixLen == MAX_VARINT_BYTES && (b & 0x80) != 0) {
                throw new MalformedFrameException("VarInt longer than 3 bytes");
            }
        }
        int length = prefixValue;
        if (cum.readableBytes() < length) {
            return null;
        }
        ByteBuf out = ctx.alloc().buffer(prefixLen + length);
        try {
            out.writeBytes(prefix, 0, prefixLen);
            if (length > 0) {
                decryptBody(cum, out, length);
            }
        } catch (Throwable t) {
            out.release();
            throw t;
        }
        prefixLen = 0;
        prefixValue = 0;
        return out;
    }

    /** Decrypts {@code length} bytes from {@code in} straight into the writable area of {@code out}. */
    private void decryptBody(ByteBuf in, ByteBuf out, int length) {
        try {
            ByteBuffer dst = out.nioBuffer(out.writerIndex(), length);
            for (ByteBuffer src : in.nioBuffers(in.readerIndex(), length)) {
                int n = src.remaining();
                int done = cipher.update(src, dst);
                if (done != n) {
                    throw new IllegalStateException("CFB8 produced " + done + " of " + n + " bytes");
                }
            }
            if (dst.hasRemaining()) {
                throw new IllegalStateException("CFB8 produced fewer bytes than the frame body");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        out.writerIndex(out.writerIndex() + length);
        in.skipBytes(length);
        decryptedBytes += length;
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
