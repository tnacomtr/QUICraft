// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated Retry-token handler (Phase 4, docs/protocol.md §9).
 *
 * <p>Token layout, in order: version byte {@code 1}, issued-at nanoTime as 8 big-endian bytes, a
 * 32-byte HMAC-SHA256 tag, then the original destination connection id (0 to 20 bytes) as the
 * suffix. The suffix is the ODCID, so a valid token reports offset {@value #PREFIX_LENGTH}.
 *
 * <p>The tag covers the domain string, the raw client IP, the port, the version and timestamp
 * prefix, and the DCID suffix. Tokens expire 30 seconds after issue. The key is listener-local and
 * random, so a restart invalidates all outstanding tokens.
 *
 * <p>Retry emission is governed only by the {@code requireRetry} policy. Validation authenticates
 * every presented token whatever the policy says.
 */
final class HmacRetryTokenHandler implements QuicTokenHandler {
    private static final int KEY_LENGTH = 32;
    private static final byte VERSION = 1;
    private static final int TIMESTAMP_LENGTH = 8;
    private static final int TAG_LENGTH = 32;
    private static final int PREFIX_LENGTH = 1 + TIMESTAMP_LENGTH + TAG_LENGTH;
    private static final int MAX_DCID_LENGTH = 20;
    private static final int MAX_TOKEN_LENGTH = PREFIX_LENGTH + MAX_DCID_LENGTH;
    private static final long LIFETIME_NANOS = 30_000_000_000L;
    private static final byte[] DOMAIN = "quicraft/retry/v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final LongSupplier clock;
    private final BooleanSupplier requireRetry;

    /** Production constructor: listener-local random key and the monotonic system clock. */
    HmacRetryTokenHandler(BooleanSupplier requireRetry) {
        this(newRandomKey(), System::nanoTime, requireRetry);
    }

    /** Test constructor. The key is copied, so later changes to the caller's array have no effect. */
    HmacRetryTokenHandler(byte[] key, LongSupplier clock, BooleanSupplier requireRetry) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("key must be exactly " + KEY_LENGTH + " bytes");
        }
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        if (requireRetry == null) {
            throw new NullPointerException("requireRetry");
        }
        this.key = key.clone();
        this.clock = clock;
        this.requireRetry = requireRetry;
    }

    /**
     * Writes a token when the Retry policy requires one.
     *
     * <p>Inputs are validated before the policy is read. A null {@code out}, a null {@code dcid} or
     * {@code address}, an unresolved address, or a DCID longer than 20 bytes throws, even when Retry
     * is not required. When the policy is off, valid inputs return {@code false} without writing to
     * {@code out}, changing any buffer index or refcount, or reading the clock.
     */
    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        if (dcid == null) {
            throw new NullPointerException("dcid");
        }
        byte[] ip = rawAddress(address);
        if (ip == null) {
            throw new IllegalArgumentException("address must be resolved to an IPv4 or IPv6 address");
        }
        int dcidLength = dcid.readableBytes();
        if (dcidLength > MAX_DCID_LENGTH) {
            throw new IllegalArgumentException("dcid length " + dcidLength + " exceeds " + MAX_DCID_LENGTH);
        }
        if (!requireRetry.getAsBoolean()) {
            return false;
        }
        byte[] dcidBytes = new byte[dcidLength];
        dcid.getBytes(dcid.readerIndex(), dcidBytes);
        byte[] timestamp = encodeBigEndian(clock.getAsLong());
        byte[] tag = computeTag(ip, address.getPort(), timestamp, dcidBytes);
        out.writeByte(VERSION);
        out.writeBytes(timestamp);
        out.writeBytes(tag);
        out.writeBytes(dcidBytes);
        return true;
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        if (token == null) {
            return -1;
        }
        byte[] ip = rawAddress(address);
        int length = token.readableBytes();
        if (ip == null || length < PREFIX_LENGTH || length > MAX_TOKEN_LENGTH) {
            return -1;
        }
        byte[] bytes = new byte[length];
        token.getBytes(token.readerIndex(), bytes);
        if (bytes[0] != VERSION) {
            return -1;
        }
        byte[] timestamp = Arrays.copyOfRange(bytes, 1, 1 + TIMESTAMP_LENGTH);
        byte[] tag = Arrays.copyOfRange(bytes, 1 + TIMESTAMP_LENGTH, PREFIX_LENGTH);
        byte[] dcid = Arrays.copyOfRange(bytes, PREFIX_LENGTH, length);
        byte[] expected = computeTag(ip, address.getPort(), timestamp, dcid);
        if (!MessageDigest.isEqual(tag, expected)) {
            return -1;
        }
        // Only an authenticated timestamp is trusted. Wrapping subtraction is intentional so that
        // negative nanoTime values remain valid.
        long age = clock.getAsLong() - decodeBigEndian(timestamp);
        if (age < 0 || age >= LIFETIME_NANOS) {
            return -1;
        }
        return PREFIX_LENGTH;
    }

    @Override
    public int maxTokenLength() {
        return MAX_TOKEN_LENGTH;
    }

    private byte[] computeTag(byte[] ip, int port, byte[] timestamp, byte[] dcid) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(DOMAIN);
            mac.update((byte) ip.length);
            mac.update(ip);
            mac.update((byte) (port >>> 8));
            mac.update((byte) port);
            mac.update(VERSION);
            mac.update(timestamp);
            mac.update(dcid);
            byte[] tag = mac.doFinal();
            if (tag.length != TAG_LENGTH) {
                throw new IllegalStateException("unexpected HMAC length " + tag.length);
            }
            return tag;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static byte[] rawAddress(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        InetAddress inet = address.getAddress();
        if (inet == null) {
            return null;
        }
        byte[] raw = inet.getAddress();
        return raw.length == 4 || raw.length == 16 ? raw : null;
    }

    private static byte[] encodeBigEndian(long value) {
        byte[] bytes = new byte[TIMESTAMP_LENGTH];
        for (int i = 0; i < TIMESTAMP_LENGTH; i++) {
            bytes[i] = (byte) (value >>> (56 - 8 * i));
        }
        return bytes;
    }

    private static long decodeBigEndian(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < TIMESTAMP_LENGTH; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }

    private static byte[] newRandomKey() {
        byte[] key = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
