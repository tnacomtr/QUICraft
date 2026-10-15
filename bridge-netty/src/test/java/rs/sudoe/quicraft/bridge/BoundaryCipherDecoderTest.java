// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoundaryCipherDecoderTest {
    private static final byte[] SECRET = new byte[16];
    private static ResourceLeakDetector.Level oldLevel;

    @BeforeAll
    static void paranoid() {
        new Random(1).nextBytes(SECRET);
        oldLevel = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    @AfterAll
    static void restore() {
        ResourceLeakDetector.setLevel(oldLevel);
    }

    /**
     * Allocator that remembers every buffer it hands out, so tests can assert real reference
     * counts (the PARANOID leak detector only logs) and how many bytes were requested in total.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        final List<ByteBuf> all = new ArrayList<>();
        long requestedBytes;

        TrackingAllocator(boolean direct) {
            super(direct);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private synchronized ByteBuf track(ByteBuf b, int requested) {
            all.add(b);
            requestedBytes += requested;
            return b;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return track(UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity), initialCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return track(UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity), initialCapacity);
        }

        void assertAllReleased(String what) {
            for (int i = 0; i < all.size(); i++) {
                assertEquals(0, all.get(i).refCnt(), what + ": buffer #" + i + " leaked");
            }
        }
    }

    private static EmbeddedChannel channel(ByteBufAllocator alloc, ChannelHandler... handlers) {
        EmbeddedChannel ch = new EmbeddedChannel(handlers);
        ch.config().setAllocator(alloc);
        return ch;
    }

    private static ByteBuf buf(ByteBufAllocator a, byte[] d, int off, int n) {
        return a.buffer(n).writeBytes(d, off, n);
    }

    private static ByteBuf buf(ByteBufAllocator a, byte[] d) {
        return buf(a, d, 0, d.length);
    }

    private static Cipher encryptCipher() throws Exception {
        Cipher c = Cipher.getInstance("AES/CFB8/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(SECRET, "AES"), new IvParameterSpec(SECRET));
        return c;
    }

    private static byte[] varInt(int v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7F) != 0) {
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write(v);
        return o.toByteArray();
    }

    private static byte[] frame(Random r, int len) {
        byte[] p = varInt(len);
        byte[] f = new byte[p.length + len];
        System.arraycopy(p, 0, f, 0, p.length);
        byte[] body = new byte[len];
        r.nextBytes(body);
        System.arraycopy(body, 0, f, p.length, len);
        return f;
    }

    private static byte[] concat(List<byte[]> l) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : l) {
            o.write(b, 0, b.length);
        }
        return o.toByteArray();
    }

    private static List<byte[]> frames(Random r, int[] sizes) {
        List<byte[]> l = new ArrayList<>();
        for (int s : sizes) {
            l.add(frame(r, s));
        }
        return l;
    }

    /** Collects every message read downstream, as bytes, and releases it like a real consumer. */
    private static final class Sink extends ChannelInboundHandlerAdapter {
        final List<byte[]> got = new ArrayList<>();
        final List<ByteBuf> bufs = new ArrayList<>();
        final List<Object> others = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completes;
        int stopAtCount = -1;
        BoundaryCipherDecoder decoder;
        boolean take;
        ByteBuf taken;
        /** Called after a ByteBuf was recorded and released; may throw. */
        Consumer<ChannelHandlerContext> onRead;
        /** When set, exceptions are recorded here instead of travelling to the pipeline tail. */
        boolean captureErrors;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                others.add(msg);
                return;
            }
            ByteBuf b = (ByteBuf) msg;
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            got.add(a);
            bufs.add(b);
            if (decoder != null && got.size() == stopAtCount) {
                if (take) {
                    taken = decoder.stopAndTakeRemainder();
                } else {
                    decoder.stop();
                }
            }
            if (onRead != null) {
                onRead.accept(ctx);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            completes++;
            super.channelReadComplete(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (captureErrors) {
                errors.add(cause);
            } else {
                super.exceptionCaught(ctx, cause);
            }
        }

        void assertAllEmittedReleased() {
            for (int i = 0; i < bufs.size(); i++) {
                assertEquals(0, bufs.get(i).refCnt(), "emitted buffer #" + i);
            }
        }
    }

    private static List<int[]> sizeSets() {
        Random r = new Random(7);
        List<int[]> l = new ArrayList<>();
        l.add(new int[] {0, 1, 127, 128, 16383, 16384, 2097151, 5, 0});
        int[] rnd = new int[60];
        for (int i = 0; i < rnd.length; i++) {
            rnd[i] = r.nextInt(5) == 0 ? r.nextInt(40000) : r.nextInt(300);
        }
        l.add(rnd);
        return l;
    }

    private static void feedSplits(EmbeddedChannel ch, ByteBufAllocator a, byte[] data, int mode, Random r) {
        if (mode == 0) {
            ch.writeInbound(buf(a, data));
        } else if (mode == 1) {
            for (int i = 0; i < data.length; i++) {
                ch.writeInbound(buf(a, data, i, 1));
            }
        } else {
            int i = 0;
            while (i < data.length) {
                int n = Math.min(data.length - i, 1 + r.nextInt(mode == 2 ? 50 : 70000));
                ch.writeInbound(buf(a, data, i, n));
                i += n;
            }
        }
    }

    @Test
    void equivalenceAllSplits() throws Exception {
        for (boolean direct : new boolean[] {false, true}) {
            for (int[] sizes : sizeSets()) {
                for (int mode = 0; mode < 4; mode++) {
                    // byte-by-byte on the 2 MiB frame is slow but still bounded (per-byte cumulation
                    // writes); skip that one combination.
                    if (mode == 1 && sizes.length == 9) {
                        continue;
                    }
                    Random r = new Random(100 + mode);
                    List<byte[]> fr = frames(r, sizes);
                    byte[] plain = concat(fr);
                    byte[] enc = encryptCipher().update(plain);
                    TrackingAllocator alloc = new TrackingAllocator(direct);
                    Sink sink = new Sink();
                    EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
                    feedSplits(ch, alloc, enc, mode, r);
                    String what = "direct " + direct + " mode " + mode;
                    assertEquals(fr.size(), sink.got.size(), what);
                    for (int i = 0; i < fr.size(); i++) {
                        assertArrayEquals(fr.get(i), sink.got.get(i), "frame " + i + " " + what);
                    }
                    sink.assertAllEmittedReleased();
                    assertNull(ch.readInbound());
                    ch.finishAndReleaseAll();
                    alloc.assertAllReleased(what);
                }
            }
        }
    }

    private static void boundaryCase(int mode, boolean take) throws Exception {
        Random r = new Random(500 + mode);
        List<byte[]> fr = frames(r, new int[] {3, 0, 200, 16384, 1});
        byte[] raw = new byte[1000];
        r.nextBytes(raw);
        byte[] plain = concat(fr);
        Cipher enc = encryptCipher();
        byte[] wire = new byte[plain.length + raw.length];
        System.arraycopy(enc.update(plain), 0, wire, 0, plain.length);
        System.arraycopy(raw, 0, wire, plain.length, raw.length);

        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = fr.size();
        sink.take = take;
        EmbeddedChannel ch = channel(alloc, dec, sink);
        feedSplits(ch, alloc, wire, mode, r);

        // Suffix = what stopAndTakeRemainder returned (if used) + whatever flowed through raw after
        // the handler removed itself (split feeds deliver later chunks after removal).
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (take) {
            assertEquals(1, sink.taken.refCnt(), "handed-out remainder has refCnt 1");
            byte[] t = new byte[sink.taken.readableBytes()];
            sink.taken.readBytes(t);
            assertTrue(sink.taken.release());
            o.write(t, 0, t.length);
        }
        for (int i = fr.size(); i < sink.got.size(); i++) {
            o.write(sink.got.get(i), 0, sink.got.get(i).length);
        }
        byte[] rest = o.toByteArray();
        for (int i = 0; i < fr.size(); i++) {
            assertArrayEquals(fr.get(i), sink.got.get(i));
        }
        assertArrayEquals(raw, rest, "suffix mode " + mode + " take " + take);
        assertEquals(plain.length, dec.decryptedByteCount(), "cipher not advanced past frame end");
        assertNull(ch.pipeline().get(BoundaryCipherDecoder.class), "removed");
        // later reads flow through raw, untouched
        int before = sink.got.size();
        ch.writeInbound(buf(alloc, new byte[] {9, 8, 7}));
        assertEquals(before + 1, sink.got.size());
        assertArrayEquals(new byte[] {9, 8, 7}, sink.got.get(before));
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("boundary mode " + mode + " take " + take);
    }

    @Test
    void boundaryForwardsRawSuffix() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            boundaryCase(mode, false);
        }
    }

    @Test
    void stopAndTakeRemainderReturnsSuffix() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            boundaryCase(mode, true);
        }
    }

    @Test
    void stopAndTakeRemainderOfWholeInputHandsOutRefCntOne() throws Exception {
        Random r = new Random(11);
        byte[] f = frame(r, 5);
        byte[] raw = {1, 2, 3, 4};
        byte[] enc = encryptCipher().update(f);
        byte[] wire = new byte[enc.length + raw.length];
        System.arraycopy(enc, 0, wire, 0, enc.length);
        System.arraycopy(raw, 0, wire, enc.length, raw.length);

        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        sink.take = true;
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ByteBuf in = buf(alloc, wire);
        ch.writeInbound(in);
        assertNotSame(null, sink.taken);
        assertEquals(1, sink.taken.refCnt());
        byte[] t = new byte[sink.taken.readableBytes()];
        sink.taken.readBytes(t);
        assertArrayEquals(raw, t);
        assertTrue(sink.taken.release());
        assertEquals(0, sink.taken.refCnt());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("whole input");
    }

    @Test
    void anAdoptedReadOnlyInputIsNeverCompacted() throws Exception {
        Random r = new Random(13);
        byte[] f1 = frame(r, 300);
        byte[] f2 = frame(r, 300);
        byte[] plain = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, plain, 0, f1.length);
        System.arraycopy(f2, 0, plain, f1.length, f2.length);
        byte[] enc = encryptCipher().update(plain);
        int cut = f1.length + 150;

        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ByteBuf parent = buf(alloc, Arrays.copyOf(enc, cut));
        parent.retain();
        ch.writeInbound(parent.asReadOnly());
        assertEquals(1, sink.got.size(), "the complete frame came out");
        byte[] after = new byte[cut];
        parent.getBytes(0, after);
        assertArrayEquals(Arrays.copyOf(enc, cut), after, "the shared input was not rewritten");

        ch.writeInbound(buf(alloc, Arrays.copyOfRange(enc, cut, enc.length)));
        assertTrue(parent.release(), "the decoder gave its reference back");
        assertEquals(2, sink.got.size());
        assertArrayEquals(f1, sink.got.get(0));
        assertArrayEquals(f2, sink.got.get(1));
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("read-only input");
    }

    @Test
    void stopAndTakeRemainderCopiesASharedSlice() throws Exception {
        Random r = new Random(12);
        byte[] f = frame(r, 5);
        byte[] raw = {5, 6, 7};
        byte[] enc = encryptCipher().update(f);
        byte[] wire = new byte[enc.length + raw.length];
        System.arraycopy(enc, 0, wire, 0, enc.length);
        System.arraycopy(raw, 0, wire, enc.length, raw.length);

        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        sink.take = true;
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ByteBuf parent = buf(alloc, wire);
        ByteBuf slice = parent.retainedSlice(0, wire.length);
        assertEquals(2, slice.refCnt(), "slice shares the parent's count");
        ch.writeInbound(slice);
        ByteBuf t = sink.taken;
        assertEquals(1, t.refCnt(), "caller owns exactly one reference");
        assertNotSame(slice, t, "a shared slice must be copied out");
        assertEquals(1, parent.refCnt(), "the slice's reference was given back");
        byte[] got = new byte[t.readableBytes()];
        t.readBytes(got);
        assertArrayEquals(raw, got);
        assertTrue(t.release());
        assertTrue(parent.release());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("shared slice");
    }

    @Test
    void stopAndTakeRemainderCanBeEmpty() throws Exception {
        Random r = new Random(13);
        byte[] enc = encryptCipher().update(frame(r, 5));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        sink.take = true;
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(0, sink.taken.readableBytes());
        assertEquals(1, sink.taken.refCnt());
        assertTrue(sink.taken.release());
        assertNull(ch.pipeline().get(BoundaryCipherDecoder.class));
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("empty remainder");
    }

    @Test
    void stopFiresTheRemainderAndReleasesEverything() throws Exception {
        Random r = new Random(14);
        byte[] f = frame(r, 9);
        byte[] raw = {42, 43, 44, 45, 46};
        byte[] enc = encryptCipher().update(f);
        byte[] wire = new byte[enc.length + raw.length];
        System.arraycopy(enc, 0, wire, 0, enc.length);
        System.arraycopy(raw, 0, wire, enc.length, raw.length);
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, wire));
        assertEquals(2, sink.got.size());
        assertArrayEquals(f, sink.got.get(0));
        assertArrayEquals(raw, sink.got.get(1));
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("stop()");
    }

    @Test
    void laterReadsPassThroughAfterStop() throws Exception {
        Random r = new Random(9);
        byte[] f = frame(r, 5);
        Cipher enc = encryptCipher();
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        EmbeddedChannel ch = new EmbeddedChannel(dec, sink);
        ch.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(enc.update(f)));
        ch.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {9, 8, 7}));
        assertEquals(2, sink.got.size());
        assertArrayEquals(new byte[] {9, 8, 7}, sink.got.get(1));
        ch.finishAndReleaseAll();
    }

    @Test
    void nonByteBufMessagesPassThrough() {
        Sink sink = new Sink();
        EmbeddedChannel ch = new EmbeddedChannel(BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ch.writeInbound("hello");
        assertEquals(1, sink.others.size());
        assertEquals("hello", sink.others.get(0));
        ch.finishAndReleaseAll();
    }

    @Test
    void malformedVarIntFailsWithoutLeak() throws Exception {
        byte[] bad = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1, 2};
        byte[] enc = encryptCipher().update(bad);
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ByteBuf b = buf(alloc, enc);
        assertThrows(BoundaryCipherDecoder.MalformedFrameException.class, () -> ch.writeInbound(b));
        assertEquals(0, b.refCnt());
        // further input is dropped, not crashed on
        ByteBuf more = buf(alloc, new byte[] {1, 2, 3});
        ch.writeInbound(more);
        assertEquals(0, more.refCnt());
        assertTrue(sink.got.isEmpty());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("malformed");
    }

    @Test
    void thirdVarIntByteWithContinuationBitIsRejectedImmediately() throws Exception {
        // Exactly three prefix bytes, the third still says "more": no fourth byte is needed to fail.
        byte[] enc = encryptCipher().update(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80});
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        sink.captureErrors = true;
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(1, sink.errors.size(), "exceptionCaught fired exactly once");
        assertTrue(sink.errors.get(0) instanceof BoundaryCipherDecoder.MalformedFrameException);
        // later input is dropped and does not raise it again
        ch.writeInbound(buf(alloc, new byte[] {1, 2}));
        assertEquals(1, sink.errors.size());
        assertTrue(sink.got.isEmpty());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("rejected 3rd byte");
    }

    @Test
    void closeAndRemovalReleaseCumulation() throws Exception {
        byte[] enc = encryptCipher().update(new byte[] {10, 1, 2});
        // close
        TrackingAllocator alloc = new TrackingAllocator(false);
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET));
        ByteBuf b = buf(alloc, enc);
        ch.writeInbound(b);
        assertEquals(1, b.refCnt(), "held as partial cumulation");
        ch.finishAndReleaseAll();
        assertEquals(0, b.refCnt());
        alloc.assertAllReleased("close");

        // removal
        TrackingAllocator alloc2 = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        EmbeddedChannel ch2 = channel(alloc2, dec);
        ByteBuf b2 = buf(alloc2, enc);
        ch2.writeInbound(b2);
        assertEquals(1, b2.refCnt(), "held as partial cumulation");
        ch2.pipeline().remove(dec);
        assertEquals(0, b2.refCnt(), "released on removal");
        ch2.finishAndReleaseAll();
        alloc2.assertAllReleased("removal");
    }

    @Test
    void mergePathReleasesInputsAndKeepsOneOwnedBuffer() throws Exception {
        Random r = new Random(15);
        byte[] f = frame(r, 100);
        byte[] enc = encryptCipher().update(f);
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ByteBuf a = buf(alloc, enc, 0, 40);
        ByteBuf b = buf(alloc, enc, 40, 40);
        ByteBuf c = buf(alloc, enc, 80, enc.length - 80);
        ch.writeInbound(a);
        assertEquals(1, a.refCnt(), "partial frame is held");
        ch.writeInbound(b);
        assertEquals(0, a.refCnt(), "adopted buffer released once merged");
        assertEquals(0, b.refCnt(), "input released once merged");
        assertTrue(sink.got.isEmpty());
        ch.writeInbound(c);
        assertEquals(0, c.refCnt());
        assertEquals(1, sink.got.size());
        assertArrayEquals(f, sink.got.get(0));
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("merge");
    }

    @Test
    void largeFrameInSmallReadsCostsLinearAllocationAndCopy() throws Exception {
        Random r = new Random(16);
        byte[] f = frame(r, 2097151);
        byte[] enc = encryptCipher().update(f);
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        for (int i = 0; i < enc.length; i += 1024) {
            ch.writeInbound(buf(alloc, enc, i, Math.min(1024, enc.length - i)));
        }
        assertEquals(1, sink.got.size());
        assertArrayEquals(f, sink.got.get(0));
        // The inputs themselves are n bytes and the output frame n bytes. An exact-size merge on every
        // read would request about n * reads / 2 (>1 GiB here); geometric growth stays near 3n.
        assertTrue(alloc.requestedBytes < 8L * enc.length,
                "requested " + alloc.requestedBytes + " bytes for a " + enc.length + " byte frame");
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("large frame");
    }

    /** Heap buffer that can be told to fail every read of its content. */
    private static final class ThrowingBuf extends UnpooledHeapByteBuf {
        boolean armed;

        ThrowingBuf(ByteBufAllocator alloc, int cap) {
            super(alloc, cap, Integer.MAX_VALUE);
        }

        private void check() {
            if (armed) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public boolean hasArray() {
            return !armed && super.hasArray();
        }

        @Override
        public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
            check();
            return super.getBytes(index, dst, dstIndex, length);
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuffer dst) {
            check();
            return super.getBytes(index, dst);
        }

        @Override
        public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
            check();
            return super.getBytes(index, dst, dstIndex, length);
        }

        @Override
        public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
            check();
            return super.getBytes(index, out, length);
        }
    }

    @Test
    void mergeFailureReleasesNewBufferAndFailsTheHandler() throws Exception {
        Random r = new Random(17);
        byte[] f = frame(r, 100);
        byte[] enc = encryptCipher().update(f);
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        sink.captureErrors = true;
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ByteBuf first = buf(alloc, enc, 0, 30);
        ch.writeInbound(first);
        ThrowingBuf bad = new ThrowingBuf(alloc, 30);
        bad.writeBytes(enc, 30, 30);
        bad.armed = true;
        ch.writeInbound(bad);
        assertEquals(0, bad.refCnt(), "input released");
        assertEquals(0, first.refCnt(), "cumulation released");
        assertEquals(1, sink.errors.size(), "exceptionCaught fired once");
        alloc.assertAllReleased("merge failure");
        ByteBuf more = buf(alloc, enc, 60, enc.length - 60);
        ch.writeInbound(more);
        assertEquals(0, more.refCnt(), "later input dropped");
        assertTrue(sink.got.isEmpty());
        assertEquals(1, sink.errors.size());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("merge failure end");
    }

    @Test
    void autoReadOffPartialFrameRequestsMoreInput() throws Exception {
        Random r = new Random(18);
        byte[] f = frame(r, 100);
        byte[] enc = encryptCipher().update(f);
        int[] reads = new int[1];
        ChannelOutboundHandlerAdapter counter = new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) throws Exception {
                reads[0]++;
                super.read(ctx);
            }
        };
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, counter, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ch.config().setAutoRead(false);
        reads[0] = 0;
        ch.writeInbound(buf(alloc, enc, 0, 50));
        assertEquals(1, reads[0], "nothing was fired, so the decoder must ask for the next read");
        assertTrue(sink.got.isEmpty());
        assertEquals(1, sink.completes, "channelReadComplete still forwarded");
        ch.writeInbound(buf(alloc, enc, 50, enc.length - 50));
        assertEquals(1, reads[0], "a fired frame leaves the next read to the consumer");
        assertEquals(1, sink.got.size());
        // A read cycle that delivered no data must not trigger a read loop.
        ch.pipeline().fireChannelReadComplete();
        assertEquals(1, reads[0]);
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("autoRead off");
    }

    @Test
    void autoReadOnAddsNoReadOfItsOwn() throws Exception {
        Random r = new Random(19);
        byte[] enc = encryptCipher().update(frame(r, 100));
        int[] reads = new int[1];
        ChannelOutboundHandlerAdapter counter = new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) throws Exception {
                reads[0]++;
                super.read(ctx);
            }
        };
        TrackingAllocator alloc = new TrackingAllocator(false);
        EmbeddedChannel ch = channel(alloc, counter, BoundaryCipherDecoder.forSharedSecret(SECRET), new Sink());
        reads[0] = 0;
        ch.writeInbound(buf(alloc, enc, 0, 50));
        // With autoRead on, the pipeline head itself reads once after channelReadComplete; the
        // decoder must not add a second one.
        assertEquals(1, reads[0]);
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("autoRead on");
    }

    @Test
    void channelReadCompleteIsForwarded() throws Exception {
        Random r = new Random(20);
        byte[] enc = encryptCipher().update(frame(r, 10));
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(1, sink.completes);
        ch.writeInbound(buf(alloc, new byte[] {0}));
        assertEquals(2, sink.completes);
        ch.finishAndReleaseAll();
    }

    @Test
    void downstreamCloseStopsFurtherFrames() throws Exception {
        Random r = new Random(21);
        List<byte[]> fr = frames(r, new int[] {5, 6, 7, 8});
        byte[] enc = encryptCipher().update(concat(fr));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.onRead = ctx -> ctx.channel().close();
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(1, sink.got.size(), "no frame after the channel was closed");
        assertEquals(enc.length > 0 ? 6 : 0, dec.decryptedByteCount());
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("downstream close");
    }

    @Test
    void externalRemovalMidReadStopsQuietly() throws Exception {
        Random r = new Random(22);
        List<byte[]> fr = frames(r, new int[] {5, 6, 7});
        byte[] enc = encryptCipher().update(concat(fr));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.onRead = ctx -> ctx.pipeline().remove(dec);
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(1, sink.got.size());
        assertNull(ch.pipeline().get(BoundaryCipherDecoder.class));
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("external removal");
    }

    @Test
    void externalRemovalThenStopDoesNotThrow() throws Exception {
        Random r = new Random(23);
        byte[] enc = encryptCipher().update(concat(frames(r, new int[] {5, 6})));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.captureErrors = true;
        sink.onRead = ctx -> {
            ctx.pipeline().remove(dec);
            dec.stop();
        };
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        assertTrue(sink.errors.isEmpty(), "stop after removal threw: " + sink.errors);
        assertEquals(1, sink.got.size());
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("removal then stop");
    }

    @Test
    void downstreamExceptionMidLoopDoesNotLoseOrLeakFrames() throws Exception {
        Random r = new Random(24);
        List<byte[]> fr = frames(r, new int[] {5, 6, 7});
        byte[] enc = encryptCipher().update(concat(fr));
        TrackingAllocator alloc = new TrackingAllocator(false);
        Sink sink = new Sink();
        sink.captureErrors = true;
        sink.onRead = ctx -> {
            if (sink.got.size() == 1) {
                throw new IllegalStateException("downstream");
            }
        };
        EmbeddedChannel ch = channel(alloc, BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ch.writeInbound(buf(alloc, enc));
        assertEquals(3, sink.got.size());
        assertEquals(1, sink.errors.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(fr.get(i), sink.got.get(i));
        }
        sink.assertAllEmittedReleased();
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("downstream exception");
    }

    @Test
    void doubleStopThrows() throws Exception {
        Random r = new Random(25);
        byte[] enc = encryptCipher().update(frame(r, 5));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        boolean[] threwInside = new boolean[1];
        sink.onRead = ctx -> {
            try {
                dec.stop();
            } catch (IllegalStateException expected) {
                threwInside[0] = true;
            }
        };
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        assertTrue(threwInside[0], "second stop inside the read threw");
        assertThrows(IllegalStateException.class, dec::stop, "stop after the handler finished");
        assertThrows(IllegalStateException.class, dec::stopAndTakeRemainder);
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("double stop");
    }

    @Test
    void stopInsideAHalfReadFrameThrows() throws Exception {
        Random r = new Random(26);
        byte[] f = frame(r, 100);
        byte[] enc = encryptCipher().update(f);
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc, 0, 50));
        assertThrows(IllegalStateException.class, dec::stop);
        assertThrows(IllegalStateException.class, dec::stopAndTakeRemainder);
        assertTrue(ch.pipeline().get(BoundaryCipherDecoder.class) == dec, "still installed");
        // the failed stop did not disturb the stream
        ch.writeInbound(buf(alloc, enc, 50, enc.length - 50));
        assertEquals(1, sink.got.size());
        assertArrayEquals(f, sink.got.get(0));
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("half-read stop");
    }

    @Test
    void threeByteVarIntSplitAcrossReadsWithContinuationBits() throws Exception {
        Random r = new Random(27);
        byte[] f = frame(r, 16384); // prefix 80 80 01
        assertEquals(3, varInt(16384).length);
        byte[] enc = encryptCipher().update(f);
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc, 0, 1));
        assertEquals(1, dec.decryptedByteCount());
        ch.writeInbound(buf(alloc, enc, 1, 1));
        assertEquals(2, dec.decryptedByteCount());
        ch.writeInbound(buf(alloc, enc, 2, 1));
        assertEquals(3, dec.decryptedByteCount());
        assertTrue(sink.got.isEmpty());
        ch.writeInbound(buf(alloc, enc, 3, enc.length - 3));
        assertEquals(1, sink.got.size());
        assertArrayEquals(f, sink.got.get(0));
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("3-byte prefix");
    }

    @Test
    void stopWithNothingBufferedRemovesHandler() throws Exception {
        // Outside a read there is never undecrypted data (the loop decrypts the next prefix as soon
        // as its byte arrives, and that makes a stop throw), so the remainder is always empty here.
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = new EmbeddedChannel(dec, sink);
        dec.stop();
        assertNull(ch.pipeline().get(BoundaryCipherDecoder.class));
        assertTrue(sink.got.isEmpty());
        ch.finishAndReleaseAll();
    }

    @Test
    void stopOutsideReadAfterAFrameReturnsEmptyRemainder() throws Exception {
        Random r = new Random(28);
        byte[] enc = encryptCipher().update(frame(r, 5));
        TrackingAllocator alloc = new TrackingAllocator(false);
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = channel(alloc, dec, sink);
        ch.writeInbound(buf(alloc, enc));
        ByteBuf rest = dec.stopAndTakeRemainder();
        assertEquals(0, rest.readableBytes());
        assertEquals(1, rest.refCnt());
        assertTrue(rest.release());
        assertFalse(ch.pipeline().names().contains("BoundaryCipherDecoder#0"));
        assertSame(null, ch.pipeline().get(BoundaryCipherDecoder.class));
        ch.finishAndReleaseAll();
        alloc.assertAllReleased("outside-read take");
    }
}
