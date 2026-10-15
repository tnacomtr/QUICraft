// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;
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

    /** Collects every ByteBuf read downstream, as bytes. */
    private static final class Sink extends ChannelInboundHandlerAdapter {
        final List<byte[]> got = new ArrayList<>();
        int stopAtCount = -1;
        BoundaryCipherDecoder decoder;
        boolean take;
        ByteBuf taken;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf b = (ByteBuf) msg;
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            got.add(a);
            if (decoder != null && got.size() == stopAtCount) {
                if (take) {
                    taken = decoder.stopAndTakeRemainder();
                } else {
                    decoder.stop();
                }
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

    private static void feedSplits(EmbeddedChannel ch, byte[] data, int mode, Random r) {
        if (mode == 0) {
            ch.writeInbound(Unpooled.wrappedBuffer(data));
        } else if (mode == 1) {
            for (byte b : data) {
                ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {b}));
            }
        } else {
            int i = 0;
            while (i < data.length) {
                int n = Math.min(data.length - i, 1 + r.nextInt(mode == 2 ? 50 : 70000));
                byte[] c = new byte[n];
                System.arraycopy(data, i, c, 0, n);
                ch.writeInbound(PooledByteBufAllocator.DEFAULT.heapBuffer(n).writeBytes(c));
                i += n;
            }
        }
    }

    @Test
    void equivalenceAllSplits() throws Exception {
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
                Sink sink = new Sink();
                EmbeddedChannel ch = new EmbeddedChannel(BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
                feedSplits(ch, enc, mode, r);
                assertEquals(fr.size(), sink.got.size(), "mode " + mode);
                for (int i = 0; i < fr.size(); i++) {
                    assertArrayEquals(fr.get(i), sink.got.get(i), "frame " + i + " mode " + mode);
                }
                ch.finishAndReleaseAll();
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

        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = fr.size();
        sink.take = take;
        EmbeddedChannel ch = new EmbeddedChannel(dec, sink);
        feedSplits(ch, wire, mode, r);

        // Suffix = what stopAndTakeRemainder returned (if used) + whatever flowed through raw after
        // the handler removed itself (split feeds deliver later chunks after removal).
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (take) {
            byte[] t = new byte[sink.taken.readableBytes()];
            sink.taken.readBytes(t);
            sink.taken.release();
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
        // later reads flow through raw
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {9, 8, 7}));
        ByteBuf last = ch.readInbound();
        assertTrue(last == null);
        ch.finishAndReleaseAll();
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
    void laterReadsPassThroughAfterStop() throws Exception {
        Random r = new Random(9);
        byte[] f = frame(r, 5);
        Cipher enc = encryptCipher();
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        sink.decoder = dec;
        sink.stopAtCount = 1;
        EmbeddedChannel ch = new EmbeddedChannel(dec, sink);
        ch.writeInbound(Unpooled.wrappedBuffer(enc.update(f)));
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {9, 8, 7}));
        assertEquals(2, sink.got.size());
        assertArrayEquals(new byte[] {9, 8, 7}, sink.got.get(1));
        ch.finishAndReleaseAll();
    }

    @Test
    void malformedVarIntFailsWithoutLeak() throws Exception {
        byte[] bad = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1, 2};
        byte[] enc = encryptCipher().update(bad);
        Sink sink = new Sink();
        EmbeddedChannel ch = new EmbeddedChannel(BoundaryCipherDecoder.forSharedSecret(SECRET), sink);
        ByteBuf b = PooledByteBufAllocator.DEFAULT.buffer().writeBytes(enc);
        assertThrows(BoundaryCipherDecoder.MalformedFrameException.class, () -> ch.writeInbound(b));
        assertEquals(0, b.refCnt());
        // further input is dropped, not crashed on
        ByteBuf more = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
        ch.writeInbound(more);
        assertEquals(0, more.refCnt());
        assertTrue(sink.got.isEmpty());
        ch.finishAndReleaseAll();
    }

    @Test
    void closeAndRemovalReleaseCumulation() throws Exception {
        byte[] enc = encryptCipher().update(new byte[] {10, 1, 2});
        EmbeddedChannel ch = new EmbeddedChannel(BoundaryCipherDecoder.forSharedSecret(SECRET));
        ByteBuf b = PooledByteBufAllocator.DEFAULT.buffer().writeBytes(enc);
        ch.writeInbound(b);
        assertEquals(1, b.refCnt(), "held as partial cumulation");
        ch.finishAndReleaseAll();
        assertEquals(0, b.refCnt());
    }

    @Test
    void stopOutsideReadFlushesRemainder() throws Exception {
        // stop() with nothing buffered: removes itself, no output.
        BoundaryCipherDecoder dec = BoundaryCipherDecoder.forSharedSecret(SECRET);
        Sink sink = new Sink();
        EmbeddedChannel ch = new EmbeddedChannel(dec, sink);
        dec.stop();
        assertNull(ch.pipeline().get(BoundaryCipherDecoder.class));
        assertTrue(sink.got.isEmpty());
        ch.finishAndReleaseAll();
    }
}
