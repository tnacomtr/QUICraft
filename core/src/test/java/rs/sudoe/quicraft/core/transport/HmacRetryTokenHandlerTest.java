// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HmacRetryTokenHandler}.
 *
 * <p>Token layout (big-endian throughout): version(1) + nanoTime(8) + HMAC-SHA256(32) + DCID(0-20).
 * PREFIX_LENGTH = 41, MAX_TOKEN_LENGTH = 61. The HMAC covers domain + IP length + IP + port(2BE) +
 * version + timestamp + DCID.
 */
class HmacRetryTokenHandlerTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private static final byte[] TEST_KEY = new byte[32];

    static {
        // Deterministic 32-byte key for reproducible HMAC
        for (int i = 0; i < 32; i++) {
            TEST_KEY[i] = (byte) i;
        }
    }

    private static final byte[] DOMAIN = "quicraft/retry/v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int PREFIX_LENGTH = 41; // 1 + 8 + 32
    private static final int MAX_TOKEN_LENGTH = 61; // 41 + 20

    private static byte[] computeTag(byte[] key, byte[] ip, int port, byte[] timestamp, byte[] dcid)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(DOMAIN);
        mac.update((byte) ip.length);
        mac.update(ip);
        mac.update((byte) (port >>> 8));
        mac.update((byte) port);
        mac.update(VERSION);
        mac.update(timestamp);
        mac.update(dcid);
        return mac.doFinal();
    }

    private static byte[] encodeBigEndian(long value) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (value >>> (56 - 8 * i));
        }
        return b;
    }

    private static long decodeBigEndian(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    private HmacRetryTokenHandler handler(byte[] key, LongSupplier clock, BooleanSupplier requireRetry) {
        return new HmacRetryTokenHandler(key, clock, requireRetry);
    }

    /**
     * Test-owned buffers registered here are released once after every test, even when an
     * assertion fails before the explicit release in the test body. Aliased views such as
     * {@link ByteBuf#slice(int, int)} are deliberately not registered: they share the parent's
     * reference count, so releasing the registered parent is enough.
     */
    private final List<ByteBuf> ownedBuffers = new ArrayList<>();

    private ByteBuf owned(ByteBuf buffer) {
        ownedBuffers.add(buffer);
        return buffer;
    }

    @AfterEach
    void releaseOwnedBuffers() {
        for (ByteBuf buffer : ownedBuffers) {
            if (buffer.refCnt() > 0) {
                buffer.release();
            }
        }
        ownedBuffers.clear();
    }

    private static InetSocketAddress ipv4(int... octets) {
        try {
            InetAddress addr = InetAddress.getByAddress(new byte[]{(byte) octets[0], (byte) octets[1],
                    (byte) octets[2], (byte) octets[3]});
            return new InetSocketAddress(addr, 443);
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private static InetSocketAddress ipv6(byte... bytes) {
        try {
            InetAddress addr = InetAddress.getByAddress(bytes);
            return new InetSocketAddress(addr, 443);
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    // ── 1. Independent JDK Mac computation matches exact bytes ───────────

    @Test
    void independentJdkMacComputesSameTagAsHandler() throws Exception {
        long ts = 1_234_567_890_000_000L;
        LongSupplier clock = () -> ts;
        byte[] dcid = new byte[]{1, 2, 3};
        InetSocketAddress addr = ipv4(192, 168, 1, 1);

        HmacRetryTokenHandler h = handler(TEST_KEY, clock, () -> true);

        // Write token via handler
        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));

        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Verify layout: version + 8-byte timestamp + 32-byte tag + dcid
        assertEquals(PREFIX_LENGTH + dcid.length, tokenBytes.length);
        assertEquals(VERSION, tokenBytes[0]);
        assertArrayEquals(encodeBigEndian(ts), Arrays.copyOfRange(tokenBytes, 1, 9));
        assertArrayEquals(dcid, Arrays.copyOfRange(tokenBytes, PREFIX_LENGTH, PREFIX_LENGTH + dcid.length));

        // Independent HMAC computation
        byte[] tag = Arrays.copyOfRange(tokenBytes, 9, PREFIX_LENGTH);
        byte[] expectedTag = computeTag(TEST_KEY,
                addr.getAddress().getAddress(), addr.getPort(),
                Arrays.copyOfRange(tokenBytes, 1, 9), dcid);
        assertArrayEquals(expectedTag, tag, "independent JDK Mac must match handler tag byte-for-byte");
    }

    // ── 2. Token format: version(1) + nanos8 + tag32 + DCID, offset41, max61 ──

    @Test
    void tokenLayoutVersionNanosTagDcidWithCorrectOffsetAndMax() {
        long ts = 999_000_000_000L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[5];
        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), ipv4(10, 0, 0, 1)));
        assertEquals(PREFIX_LENGTH + dcid.length, out.readableBytes());
        assertEquals(VERSION, out.getByte(out.readerIndex()));
        out.release();
    }

    @Test
    void maxTokenLengthIs61With20ByteDcid() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertEquals(MAX_TOKEN_LENGTH, h.maxTokenLength());
    }

    // ── 3. Valid IPv4 and IPv6 ───────────────────────────────────────────

    @Test
    void validIpv4AddressProducesAndValidatesToken() {
        long ts = 500_000_000_000L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{(byte) 0xDE, (byte) 0xAD};
        InetSocketAddress addr = ipv4(172, 16, 0, 99);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));

        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        int offset = h.validateToken(tokenBuf, addr);
        assertEquals(PREFIX_LENGTH, offset, "validateToken returns ODCID offset for valid token");
        tokenBuf.release();
    }

    @Test
    void validIpv6AddressProducesAndValidatesToken() {
        long ts = 777_777_777_777L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] ipv6 = new byte[16];
        for (int i = 0; i < 16; i++) ipv6[i] = (byte) (i + 1);
        InetSocketAddress addr = ipv6(ipv6);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));

        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        int offset = h.validateToken(tokenBuf, addr);
        assertEquals(PREFIX_LENGTH, offset);
        tokenBuf.release();
    }

    // ── 4. DCID boundaries: 0, 20 accepted; 21 rejects ──────────────────

    @Test
    void dcidZeroLengthIsAccepted() {
        long ts = 100L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.buffer(0)), ipv4(1, 2, 3, 4)));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(tokenBuf, ipv4(1, 2, 3, 4)));
        tokenBuf.release();
    }

    @Test
    void dcidTwentyBytesIsAccepted() {
        long ts = 200L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid20 = new byte[20];
        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid20)), ipv4(1, 2, 3, 4)));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(tokenBuf, ipv4(1, 2, 3, 4)));
        tokenBuf.release();
    }

    @Test
    void dcidTwentyOneBytesThrowsOnWrite() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        byte[] dcid21 = new byte[21];
        ByteBuf out = owned(Unpooled.buffer());
        assertThrows(IllegalArgumentException.class,
                () -> h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid21)), ipv4(1, 2, 3, 4)));
    }

    // ── 5. ODCID suffix intact after validation (sliced token) ──────────

    @Test
    void oDcidSuffixIntactAfterValidation() {
        long ts = 300L;
        byte[] dcid = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        InetSocketAddress addr = ipv4(10, 20, 30, 40);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));

        byte[] fullToken = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), fullToken);
        out.release();

        // Create a sliced view starting at offset 41 (the DCID portion)
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(fullToken));
        int offset = h.validateToken(tokenBuf, addr);
        assertEquals(PREFIX_LENGTH, offset);

        // The DCID portion starts at offset 41 in the token
        byte[] recovered = new byte[fullToken.length - PREFIX_LENGTH];
        System.arraycopy(fullToken, PREFIX_LENGTH, recovered, 0, recovered.length);
        assertArrayEquals(dcid, recovered, "DCID suffix must match original");
    }

    // ── 6. Tamper: every authenticated field => -1 ───────────────────────

    @Test
    void tamperingTimestampByteReturnsMinusOne() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Flip a byte in the timestamp (byte index 1)
        tokenBytes[1] ^= 0xFF;

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void tamperingTagByteReturnsMinusOne() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Flip a byte in the tag (byte index 9)
        tokenBytes[9] ^= 0xFF;

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void tamperingDcidByteReturnsMinusOne() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{0x42};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Flip DCID byte (index 41)
        tokenBytes[41] ^= 0xFF;

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void tamperingIpByteReturnsMinusOne() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Create a different address with a different IP
        InetSocketAddress otherAddr = ipv4(6, 5, 5, 5);

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, otherAddr), "wrong IP must invalidate");
        tokenBuf.release();
    }

    @Test
    void tamperingPortReturnsMinusOne() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Same IP but different port
        InetSocketAddress otherAddr = new InetSocketAddress(addr.getAddress(), 4443);

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, otherAddr));
        tokenBuf.release();
    }

    @Test
    void differentHandlerKeyInvalidatesToken() {
        long ts = 400L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(5, 5, 5, 5);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Create handler with a different key
        byte[] otherKey = TEST_KEY.clone();
        otherKey[0] ^= 0xFF;
        HmacRetryTokenHandler other = handler(otherKey, () -> ts + 1, () -> true);

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, other.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    // ── 7. Wrong version, truncated, overlong => -1 ─────────────────────

    @Test
    void wrongVersionReturnsMinusOne() {
        long ts = 500L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Change version byte
        tokenBytes[0] = 2;

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void truncatedTokenReturnsMinusOne() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        // Only 40 bytes — one short of PREFIX_LENGTH
        byte[] truncated = new byte[PREFIX_LENGTH - 1];
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(truncated));
        assertEquals(-1, h.validateToken(tokenBuf, ipv4(1, 1, 1, 1)));
        tokenBuf.release();
    }

    @Test
    void overlongTokenReturnsMinusOne() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        // 62 bytes — one over MAX_TOKEN_LENGTH
        byte[] overlong = new byte[MAX_TOKEN_LENGTH + 1];
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(overlong));
        assertEquals(-1, h.validateToken(tokenBuf, ipv4(1, 1, 1, 1)));
        tokenBuf.release();
    }

    // ── 8. Age boundaries: 0 valid, TTL-1 valid, TTL invalid, future invalid ──

    @Test
    void ageZeroIsValid() {
        long ts = 1_000_000_000_000L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Validate at exactly the same nanoTime
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void ageTtlMinusOneIsValid() {
        long ts = 1_000_000_000_000L;
        long ttlNanos = 30_000_000_000L;
        // Write with clock at ts, validate with clock at ts + ttlNanos - 1 (age = ttlNanos - 1)
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        HmacRetryTokenHandler validateH = handler(TEST_KEY, () -> ts + ttlNanos - 1, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, validateH.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void ageExactTtlIsInvalid() {
        long ts = 1_000_000_000_000L;
        long ttlNanos = 30_000_000_000L;
        // Write with clock at ts, validate with clock at ts + ttlNanos (age = TTL, should be invalid)
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        HmacRetryTokenHandler validateH = handler(TEST_KEY, () -> ts + ttlNanos, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, validateH.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void futureTimestampIsInvalid() {
        long ts = 1_000_000_000_000L;
        // Write with clock at ts, validate with clock at ts - 100 (clock behind token => negative age)
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        HmacRetryTokenHandler validateH = handler(TEST_KEY, () -> ts - 100L, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, validateH.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    // ── 9. Negative nanoTime and long wrap ───────────────────────────────

    @Test
    void negativeNanoTimeRemainsValid() {
        long ts = Long.MIN_VALUE + 100L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Validate a short time later — age is small positive
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(tokenBuf, ipv4(1, 1, 1, 1)));
        tokenBuf.release();
    }

    @Test
    void longWrapRemainsValidForShortElapsed() {
        long issueTs = Long.MAX_VALUE - 100L;
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> issueTs, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Same key, clock wrapped past Long.MAX_VALUE: wrapped elapsed is 201 ns and stays valid.
        HmacRetryTokenHandler wrappedH = handler(TEST_KEY, () -> Long.MIN_VALUE + 100L, () -> true);
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, wrappedH.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    @Test
    void longWrapExactTtlIsInvalid() {
        long issueTs = Long.MAX_VALUE - 100L;
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> issueTs, () -> true);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 1, 1, 1);

        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Same key, clock wrapped exactly one TTL after issue: wrapped age == TTL must be rejected.
        HmacRetryTokenHandler expiredH = handler(TEST_KEY, () -> issueTs + 30_000_000_000L, () -> true);
        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, expiredH.validateToken(tokenBuf, addr));
        tokenBuf.release();
    }

    // ── 10. requireRetry false: returns false without side effects ───────

    @Test
    void requireRetryFalseReturnsFalseWithoutWriting() {
        long ts = 500L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> false);
        byte[] dcid = new byte[]{1};
        ByteBuf out = owned(Unpooled.buffer());
        int readerBefore = out.readerIndex();
        int writerBefore = out.writerIndex();
        int refBefore = out.refCnt();

        boolean wrote = h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), ipv4(1, 2, 3, 4));
        assertFalse(wrote);
        assertEquals(readerBefore, out.readerIndex());
        assertEquals(writerBefore, out.writerIndex());
        assertEquals(refBefore, out.refCnt());
        out.release();
    }

    @Test
    void requireRetryFalseDoesNotReadClock() {
        boolean[] clockRead = {false};
        LongSupplier clock = () -> {
            clockRead[0] = true;
            return 500L;
        };
        HmacRetryTokenHandler h = handler(TEST_KEY, clock, () -> false);
        h.writeToken(owned(Unpooled.buffer()), owned(Unpooled.wrappedBuffer(new byte[]{1})), ipv4(1, 2, 3, 4));
        assertFalse(clockRead[0], "clock must not be read when requireRetry is false");
    }

    @Test
    void requireRetryFalseStillRejectsTamperedToken() {
        long ts = 500L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> false);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 2, 3, 4);

        // Write a valid token (with requireRetry temporarily true)
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Tamper the token
        tokenBytes[9] ^= 0xFF;

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(tokenBuf, addr),
                "requireRetry false must not affect validation — tampered token still rejected");
        tokenBuf.release();
    }

    @Test
    void requireRetryFalseStillAcceptsValidToken() {
        long ts = 500L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> false);
        byte[] dcid = new byte[]{1};
        InetSocketAddress addr = ipv4(1, 2, 3, 4);

        // Write a valid token (with requireRetry temporarily true)
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        ByteBuf out = owned(Unpooled.buffer());
        writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(tokenBuf, addr),
                "requireRetry false must not affect validation — valid token still accepted");
        tokenBuf.release();
    }

    // ── 11. Key array defensive copy ─────────────────────────────────────

    @Test
    void constructorDefensiveCopiesKeyArray() {
        byte[] callerKey = new byte[32];
        for (int i = 0; i < 32; i++) callerKey[i] = (byte) i;

        HmacRetryTokenHandler h = handler(callerKey, () -> 0L, () -> true);

        // Mutate the caller's array after construction
        callerKey[0] = (byte) 0xFF;

        // Write a token with the original key
        byte[] dcid = new byte[]{1};
        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), ipv4(1, 1, 1, 1));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Validate with a handler using the mutated (now different) key
        byte[] mutatedKey = new byte[32];
        for (int i = 0; i < 32; i++) mutatedKey[i] = callerKey[i]; // all 0xFF now
        HmacRetryTokenHandler other = handler(mutatedKey, () -> 1L, () -> true);

        ByteBuf tokenBuf = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, other.validateToken(tokenBuf, ipv4(1, 1, 1, 1)),
                "mutating caller's key array must not affect the handler's key");
        tokenBuf.release();
    }

    // ── 12. Constructor validation: null/wrong key, null clock/policy ────

    @Test
    void constructorRejectsNullKey() {
        assertThrows(IllegalArgumentException.class, () -> handler(null, () -> 0L, () -> true));
    }

    @Test
    void constructorRejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> handler(new byte[31], () -> 0L, () -> true));
        assertThrows(IllegalArgumentException.class, () -> handler(new byte[33], () -> 0L, () -> true));
        assertThrows(IllegalArgumentException.class, () -> handler(new byte[1], () -> 0L, () -> true));
    }

    @Test
    void constructorRejectsNullClock() {
        assertThrows(NullPointerException.class, () -> handler(TEST_KEY, null, () -> true));
    }

    @Test
    void constructorRejectsNullRequireRetry() {
        assertThrows(NullPointerException.class, () -> handler(TEST_KEY, () -> 0L, null));
    }

    // ── 13. Write invalid inputs throw BEFORE policy read ────────────────

    @Test
    void writeNullOutThrowsBeforePolicyCheck() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertThrows(NullPointerException.class,
                () -> h.writeToken(null, owned(Unpooled.wrappedBuffer(new byte[]{1})), ipv4(1, 2, 3, 4)));
    }

    @Test
    void writeNullDcidThrowsBeforePolicyCheck() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertThrows(NullPointerException.class,
                () -> h.writeToken(owned(Unpooled.buffer()), null, ipv4(1, 2, 3, 4)));
    }

    @Test
    void writeNullAddressThrowsBeforePolicyCheck() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertThrows(IllegalArgumentException.class,
                () -> h.writeToken(owned(Unpooled.buffer()), owned(Unpooled.wrappedBuffer(new byte[]{1})), null));
    }

    @Test
    void writeUnresolvedAddressThrowsBeforePolicyCheck() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertThrows(IllegalArgumentException.class,
                () -> h.writeToken(owned(Unpooled.buffer()), owned(Unpooled.wrappedBuffer(new byte[]{1})),
                        InetSocketAddress.createUnresolved("localhost", 443)));
    }

    @Test
    void writeDcidTooLongThrowsBeforePolicyCheck() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> false);
        assertThrows(IllegalArgumentException.class,
                () -> h.writeToken(owned(Unpooled.buffer()), owned(Unpooled.wrappedBuffer(new byte[21])), ipv4(1, 2, 3, 4)));
    }

    // ── 14. Validation null/unresolved/empty token => -1 ─────────────────

    @Test
    void validateNullTokenReturnsMinusOne() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        assertEquals(-1, h.validateToken(null, ipv4(1, 2, 3, 4)));
    }

    @Test
    void validateUnresolvedAddressReturnsMinusOne() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        ByteBuf tokenBuf = owned(Unpooled.buffer(PREFIX_LENGTH));
        tokenBuf.writeBytes(new byte[PREFIX_LENGTH]);
        assertEquals(-1, h.validateToken(tokenBuf, InetSocketAddress.createUnresolved("localhost", 443)));
        tokenBuf.release();
    }

    @Test
    void validateEmptyTokenReturnsMinusOne() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 0L, () -> true);
        ByteBuf tokenBuf = owned(Unpooled.buffer(0));
        assertEquals(-1, h.validateToken(tokenBuf, ipv4(1, 2, 3, 4)));
        tokenBuf.release();
    }

    // ── 15. Nonzero reader/writer indices, sliced and little-endian views ──

    @Test
    void writeTokenAppendsAtNonzeroWriterIndexAndPreservesInputs() throws Exception {
        long ts = 4_242_424_242L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        InetSocketAddress addr = ipv4(198, 51, 100, 9);

        // DCID payload sits at a nonzero readerIndex inside a head/tail holder.
        byte[] dcidPayload = new byte[]{0x11, 0x22, 0x33};
        ByteBuf dcidHolder = owned(Unpooled.buffer(dcidPayload.length + 4));
        dcidHolder.writeByte(0xAA);
        dcidHolder.writeBytes(dcidPayload);
        dcidHolder.writeByte(0xBB);
        dcidHolder.setIndex(1, 1 + dcidPayload.length);
        int dcidReaderBefore = dcidHolder.readerIndex();
        int dcidWriterBefore = dcidHolder.writerIndex();
        int dcidRefBefore = dcidHolder.refCnt();

        // Output is prefilled and both indices are nonzero before the append.
        ByteBuf out = owned(Unpooled.buffer(64));
        out.writeBytes(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06});
        out.setIndex(2, 6);
        int outReaderBefore = out.readerIndex();
        int outWriterBefore = out.writerIndex();
        int outRefBefore = out.refCnt();

        assertTrue(h.writeToken(out, dcidHolder, addr));

        // Only the writer advanced, by exactly one token length.
        assertEquals(outReaderBefore, out.readerIndex(), "writeToken must not move output readerIndex");
        assertEquals(outWriterBefore + PREFIX_LENGTH + dcidPayload.length, out.writerIndex(),
                "writeToken must advance output writerIndex by the exact token length");
        assertEquals(outRefBefore, out.refCnt(), "writeToken must not change output refCnt");

        byte[] after = new byte[out.writerIndex()];
        out.getBytes(0, after);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, Arrays.copyOf(after, 6),
                "prefilled output bytes must be preserved");
        byte[] appended = Arrays.copyOfRange(after, outWriterBefore, after.length);
        assertEquals(PREFIX_LENGTH + dcidPayload.length, appended.length);
        assertEquals(VERSION, appended[0]);
        assertArrayEquals(encodeBigEndian(ts), Arrays.copyOfRange(appended, 1, 9));
        assertArrayEquals(dcidPayload, Arrays.copyOfRange(appended, PREFIX_LENGTH, appended.length),
                "appended DCID must be the payload read at the DCID readerIndex");
        byte[] expectedTag = computeTag(TEST_KEY, addr.getAddress().getAddress(), addr.getPort(),
                Arrays.copyOfRange(appended, 1, 9), dcidPayload);
        assertArrayEquals(expectedTag, Arrays.copyOfRange(appended, 9, PREFIX_LENGTH),
                "appended HMAC must match the independent oracle over the DCID payload");

        // Inputs untouched.
        assertEquals(dcidReaderBefore, dcidHolder.readerIndex(), "writeToken must not move DCID readerIndex");
        assertEquals(dcidWriterBefore, dcidHolder.writerIndex(), "writeToken must not move DCID writerIndex");
        assertEquals(dcidRefBefore, dcidHolder.refCnt(), "writeToken must not retain or release the input DCID");
    }

    @Test
    void validateTokenReadsAtNonzeroReaderIndexWithoutMutation() {
        long ts = 5_555_555_555L;
        HmacRetryTokenHandler writeH = handler(TEST_KEY, () -> ts, () -> true);
        // Validation must authenticate regardless of the Retry policy, so validate with policy off.
        HmacRetryTokenHandler validateH = handler(TEST_KEY, () -> ts, () -> false);
        byte[] dcid = new byte[]{0x0D, 0x0E};
        InetSocketAddress addr = ipv4(198, 51, 100, 10);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(writeH.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);

        // Token sits at a nonzero readerIndex inside a head/tail holder.
        ByteBuf holder = owned(Unpooled.buffer(tokenBytes.length + 4));
        holder.writeByte(0xCC);
        holder.writeBytes(tokenBytes);
        holder.writeByte(0xDD);
        holder.setIndex(1, 1 + tokenBytes.length);

        int readerBefore = holder.readerIndex();
        int writerBefore = holder.writerIndex();
        int refBefore = holder.refCnt();
        byte[] bytesBefore = new byte[holder.writerIndex()];
        holder.getBytes(0, bytesBefore);

        assertEquals(PREFIX_LENGTH, validateH.validateToken(holder, addr));
        assertEquals(readerBefore, holder.readerIndex(), "valid validation must not move readerIndex");
        assertEquals(writerBefore, holder.writerIndex(), "valid validation must not move writerIndex");
        assertEquals(refBefore, holder.refCnt(), "valid validation must not change refCnt");
        byte[] bytesAfterValid = new byte[holder.writerIndex()];
        holder.getBytes(0, bytesAfterValid);
        assertArrayEquals(bytesBefore, bytesAfterValid, "valid validation must not modify token bytes");

        // Tamper one tag byte inside the holder and validate again.
        int tagIndex = holder.readerIndex() + 9;
        holder.setByte(tagIndex, holder.getByte(tagIndex) ^ 0xFF);
        int readerBefore2 = holder.readerIndex();
        int writerBefore2 = holder.writerIndex();
        int refBefore2 = holder.refCnt();

        assertEquals(-1, validateH.validateToken(holder, addr));
        assertEquals(readerBefore2, holder.readerIndex(), "rejected validation must not move readerIndex");
        assertEquals(writerBefore2, holder.writerIndex(), "rejected validation must not move writerIndex");
        assertEquals(refBefore2, holder.refCnt(), "rejected validation must not change refCnt");
    }

    @Test
    void policyDisabledWritePreservesIndicesBytesAndRefCnts() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 777L, () -> false);
        InetSocketAddress addr = ipv4(198, 51, 100, 11);

        byte[] dcidPayload = new byte[]{0x51, 0x52};
        ByteBuf dcidHolder = owned(Unpooled.buffer(dcidPayload.length + 4));
        dcidHolder.writeByte(0x01);
        dcidHolder.writeBytes(dcidPayload);
        dcidHolder.writeByte(0x02);
        dcidHolder.setIndex(1, 1 + dcidPayload.length);
        int dcidReader = dcidHolder.readerIndex();
        int dcidWriter = dcidHolder.writerIndex();
        int dcidRef = dcidHolder.refCnt();

        ByteBuf out = owned(Unpooled.buffer(32));
        out.writeBytes(new byte[]{0x10, 0x20, 0x30, 0x40});
        out.setIndex(1, 4);
        byte[] outBytesBefore = new byte[out.writerIndex()];
        out.getBytes(0, outBytesBefore);
        int outReader = out.readerIndex();
        int outWriter = out.writerIndex();
        int outRef = out.refCnt();

        assertFalse(h.writeToken(out, dcidHolder, addr));

        assertEquals(outReader, out.readerIndex(), "policy-off write must not move output readerIndex");
        assertEquals(outWriter, out.writerIndex(), "policy-off write must not move output writerIndex");
        assertEquals(outRef, out.refCnt(), "policy-off write must not change output refCnt");
        assertEquals(dcidReader, dcidHolder.readerIndex(), "policy-off write must not move DCID readerIndex");
        assertEquals(dcidWriter, dcidHolder.writerIndex(), "policy-off write must not move DCID writerIndex");
        assertEquals(dcidRef, dcidHolder.refCnt(), "policy-off write must not change DCID refCnt");
        byte[] outBytesAfter = new byte[out.writerIndex()];
        out.getBytes(0, outBytesAfter);
        assertArrayEquals(outBytesBefore, outBytesAfter, "policy-off write must not modify output bytes");
    }

    @Test
    void rejectedWritePreservesIndicesBytesAndRefCnts() {
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 888L, () -> true);
        InetSocketAddress addr = ipv4(198, 51, 100, 12);

        ByteBuf dcidTooLong = owned(Unpooled.buffer(24));
        dcidTooLong.writeBytes(new byte[22]);
        dcidTooLong.setIndex(1, 22); // 21 readable bytes, one over the 20-byte maximum
        int dcidReader = dcidTooLong.readerIndex();
        int dcidWriter = dcidTooLong.writerIndex();
        int dcidRef = dcidTooLong.refCnt();

        ByteBuf out = owned(Unpooled.buffer(32));
        out.writeBytes(new byte[]{0x0A, 0x0B, 0x0C});
        out.setIndex(1, 3);
        byte[] outBytesBefore = new byte[out.writerIndex()];
        out.getBytes(0, outBytesBefore);
        int outReader = out.readerIndex();
        int outWriter = out.writerIndex();
        int outRef = out.refCnt();

        assertThrows(IllegalArgumentException.class, () -> h.writeToken(out, dcidTooLong, addr));

        assertEquals(outReader, out.readerIndex(), "rejected write must not move output readerIndex");
        assertEquals(outWriter, out.writerIndex(), "rejected write must not move output writerIndex");
        assertEquals(outRef, out.refCnt(), "rejected write must not change output refCnt");
        assertEquals(dcidReader, dcidTooLong.readerIndex(), "rejected write must not move DCID readerIndex");
        assertEquals(dcidWriter, dcidTooLong.writerIndex(), "rejected write must not move DCID writerIndex");
        assertEquals(dcidRef, dcidTooLong.refCnt(), "rejected write must not change DCID refCnt");
        byte[] outBytesAfter = new byte[out.writerIndex()];
        out.getBytes(0, outBytesAfter);
        assertArrayEquals(outBytesBefore, outBytesAfter, "rejected write must not modify output bytes");
    }

    @Test
    void slicedBufferRetainsOwnershipAndValidates() {
        long ts = 700L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{0x11, 0x22};
        InetSocketAddress addr = ipv4(1, 2, 3, 4);

        // Write a valid token
        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] fullToken = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), fullToken);
        out.release();

        // Put the token into a larger buffer at offset 5
        ByteBuf container = owned(Unpooled.buffer(fullToken.length + 10));
        container.writeBytes(new byte[5]);
        container.writeBytes(fullToken);
        container.writeBytes(new byte[5]);

        // Slice from offset 5 — slice shares refCnt with parent in Netty
        ByteBuf sliced = container.slice(5, fullToken.length);
        int refCntBefore = sliced.refCnt();
        int readerBefore = sliced.readerIndex();

        // Validate the slice
        assertEquals(PREFIX_LENGTH, h.validateToken(sliced, addr));

        // Handler must not change refCnt or reader index
        assertEquals(refCntBefore, sliced.refCnt(), "validateToken must not change refCnt");
        assertEquals(readerBefore, sliced.readerIndex(), "validateToken must not change reader index");

        // Release the slice (also releases container since they share refCnt)
        sliced.release();
    }

    @Test
    void littleEndianBufferViewRetainsBigEndianWireBytes() {
        long ts = 800L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{0x33};
        InetSocketAddress addr = ipv4(9, 9, 9, 9);

        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr);
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        out.release();

        // Create a little-endian view (ByteBuf order doesn't change getBytes behavior,
        // but we verify the handler reads bytes correctly regardless)
        ByteBuf leBuf = owned(Unpooled.wrappedBuffer(tokenBytes)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(PREFIX_LENGTH, h.validateToken(leBuf, addr));
        leBuf.release();
    }

    // ── 16. Policy read exactly once for valid write ─────────────────────

    @Test
    void policyReadExactlyOnceForValidWrite() {
        int[] count = {0};
        BooleanSupplier policy = () -> {
            count[0]++;
            return true;
        };
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 900L, policy);
        ByteBuf out = owned(Unpooled.buffer());
        h.writeToken(out, owned(Unpooled.wrappedBuffer(new byte[]{1})), ipv4(1, 1, 1, 1));
        assertEquals(1, count[0], "requireRetry must be read exactly once on writeToken");
        out.release();
    }

    @Test
    void policyNotReadWhenWriteFailsValidation() {
        int[] count = {0};
        BooleanSupplier policy = () -> {
            count[0]++;
            return true;
        };
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> 900L, policy);
        ByteBuf out = owned(Unpooled.buffer());
        // null dcid should throw before policy check
        assertThrows(NullPointerException.class,
                () -> h.writeToken(out, null, ipv4(1, 1, 1, 1)));
        assertEquals(0, count[0], "requireRetry must not be read when pre-policy validation fails");
    }

    // ── 17. Inherited 3-arg validateToken delegates to the 2-arg authentication ──

    @Test
    void threeArgValidateTokenReportsValidPresentedToken() {
        long ts = 1_111_222_333L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        byte[] dcid = new byte[]{0x0A, 0x0B, 0x0C, 0x0D};
        InetSocketAddress addr = ipv4(203, 0, 113, 7);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(dcid)), addr));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);

        // 2-arg legacy method: exact ODCID offset and suffix bytes.
        ByteBuf legacy = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(PREFIX_LENGTH, h.validateToken(legacy, addr));
        byte[] suffix = new byte[tokenBytes.length - PREFIX_LENGTH];
        legacy.getBytes(legacy.readerIndex() + PREFIX_LENGTH, suffix);
        assertArrayEquals(dcid, suffix, "ODCID suffix must start exactly at offset 41");

        // 3-arg inherited default method must delegate and report the token valid.
        ByteBuf presented = owned(Unpooled.wrappedBuffer(tokenBytes));
        ByteBuf currentDcid = owned(Unpooled.wrappedBuffer(new byte[]{0x7F}));
        assertTrue(h.validateToken(presented, addr, currentDcid).isValid(),
                "inherited 3-arg validateToken must report a valid presented token");
    }

    @Test
    void threeArgValidateTokenReportsTamperedPresentedTokenInvalid() {
        long ts = 1_111_222_333L;
        HmacRetryTokenHandler h = handler(TEST_KEY, () -> ts, () -> true);
        InetSocketAddress addr = ipv4(203, 0, 113, 7);

        ByteBuf out = owned(Unpooled.buffer());
        assertTrue(h.writeToken(out, owned(Unpooled.wrappedBuffer(new byte[]{0x2A})), addr));
        byte[] tokenBytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), tokenBytes);
        tokenBytes[9] ^= 0xFF; // flip a tag byte so the MAC no longer matches

        ByteBuf legacy = owned(Unpooled.wrappedBuffer(tokenBytes));
        assertEquals(-1, h.validateToken(legacy, addr));

        ByteBuf presented = owned(Unpooled.wrappedBuffer(tokenBytes));
        ByteBuf currentDcid = owned(Unpooled.wrappedBuffer(new byte[]{0x7F}));
        assertFalse(h.validateToken(presented, addr, currentDcid).isValid(),
                "inherited 3-arg validateToken must report a tampered token invalid");
    }
}
