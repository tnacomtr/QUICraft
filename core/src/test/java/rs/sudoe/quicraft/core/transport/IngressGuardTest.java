// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Datagram-level flood guard behavior ({@link IngressGuard}, docs/protocol.md §9). Only long-header
 * Initial packets are counted against the guard's own {@link ConnectionAdmission}; everything else
 * is forwarded unchanged and never counted. Driven through an {@link EmbeddedChannel} with real
 * QUIC header bytes. Every admission that is not testing rollover uses a frozen clock; only the
 * window test advances an injected {@link AtomicLong}.
 */
class IngressGuardTest {
    /** One QUIC admission window, in nanoseconds. */
    private static final long WINDOW = 1_000_000_000L;

    /** A clock frozen in time so attempt budgets never roll over during a test. */
    private static final LongSupplier FROZEN = () -> 0L;

    /** QUIC requires datagrams carrying an Initial to be at least 1200 bytes. */
    private static final int MIN_INITIAL_SIZE = 1200;

    private static final InetSocketAddress RECIPIENT = resolved("198.51.100.1", 443);
    private static final InetSocketAddress SENDER_1 = resolved("192.0.2.1", 5000);
    private static final InetSocketAddress SENDER_2 = resolved("192.0.2.2", 5000);
    private static final InetSocketAddress SENDER_3 = resolved("192.0.2.3", 5000);

    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private static InetSocketAddress resolved(String host, int port) {
        return new InetSocketAddress(host, port);
    }

    private static ConnectionAdmission admission(int global, int perIp, int maxOpen, int maxOpenPerIp,
            int retryThreshold, int maxTrackedIps) {
        return new ConnectionAdmission(global, perIp, maxOpen, maxOpenPerIp, retryThreshold, maxTrackedIps, FROZEN);
    }

    private EmbeddedChannel channel(ConnectionAdmission admission) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new IngressGuard(admission, Codecs.LOCAL_CONNECTION_ID_LENGTH));
        channels.add(channel);
        return channel;
    }

    @AfterEach
    void finishChannels() {
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
        channels.clear();
    }

    // ── byte builders ────────────────────────────────────────────────────

    /** A >=1200-byte long-header Initial: 0xC0, version 1, DCID 8, SCID 8, empty token. */
    private static ByteBuf initialPacket() {
        ByteBuf buffer = Unpooled.buffer(MIN_INITIAL_SIZE);
        buffer.writeByte(0xC0); // long header, fixed bit, long type 0 = Initial
        buffer.writeInt(1); // version 1
        buffer.writeByte(8);
        buffer.writeZero(8); // DCID
        buffer.writeByte(8);
        buffer.writeZero(8); // SCID
        buffer.writeByte(0); // token length varint = 0
        buffer.writeZero(MIN_INITIAL_SIZE - buffer.readableBytes());
        return buffer;
    }

    /** A >=1200-byte long-header Handshake: 0xE0, version 1, DCID 8, SCID 8, no token. */
    private static ByteBuf handshakePacket() {
        ByteBuf buffer = Unpooled.buffer(MIN_INITIAL_SIZE);
        buffer.writeByte(0xE0); // long header, long type 2 = Handshake
        buffer.writeInt(1);
        buffer.writeByte(8);
        buffer.writeZero(8);
        buffer.writeByte(8);
        buffer.writeZero(8);
        buffer.writeZero(MIN_INITIAL_SIZE - buffer.readableBytes());
        return buffer;
    }

    /** A >=1200-byte short-header packet: 0x40 then a 20-byte destination connection id. */
    private static ByteBuf shortHeaderPacket() {
        ByteBuf buffer = Unpooled.buffer(MIN_INITIAL_SIZE);
        buffer.writeByte(0x40); // short header form, fixed bit
        buffer.writeZero(Codecs.LOCAL_CONNECTION_ID_LENGTH);
        buffer.writeZero(MIN_INITIAL_SIZE - buffer.readableBytes());
        return buffer;
    }

    /** Ten deterministic non-QUIC bytes; the parser cannot read a header from them. */
    private static ByteBuf garbage() {
        return Unpooled.wrappedBuffer(
                new byte[]{0x10, 0x2f, 0x03, 0x7a, 0x55, 0x00, 0x11, 0x63, 0x0c, 0x42});
    }

    // ── sending helpers ──────────────────────────────────────────────────

    /** Wraps {@code content} as a datagram and feeds it to the guard; null when dropped. */
    private static DatagramPacket send(EmbeddedChannel channel, ByteBuf content, InetSocketAddress sender) {
        DatagramPacket packet = new DatagramPacket(content, RECIPIENT, sender);
        channel.writeInbound(packet);
        if (packet.refCnt() == 0) {
            return null; // dropped and released by the guard
        }
        return channel.readInbound();
    }

    private static void release(DatagramPacket packet) {
        if (packet != null && packet.refCnt() > 0) {
            packet.release();
        }
    }

    /** Sends one datagram and reports whether the guard forwarded it; releases forwarded packets. */
    private static boolean forwarded(EmbeddedChannel channel, ByteBuf content, InetSocketAddress sender) {
        DatagramPacket packet = send(channel, content, sender);
        if (packet == null) {
            return false;
        }
        packet.release();
        return true;
    }

    // ── 1. within budget ─────────────────────────────────────────────────

    @Test
    void initialWithinBudgetIsForwardedUnchanged() {
        EmbeddedChannel channel = channel(admission(1000, 2, 1, 1, 1000, 100));
        ByteBuf content = initialPacket();
        int readerIndex = content.readerIndex();
        int writerIndex = content.writerIndex();

        DatagramPacket packet = new DatagramPacket(content, RECIPIENT, SENDER_1);
        channel.writeInbound(packet);

        assertSame(packet, channel.readInbound(), "the same packet object must be forwarded");
        assertEquals(readerIndex, content.readerIndex(), "reader index untouched");
        assertEquals(writerIndex, content.writerIndex(), "writer index untouched");
        assertEquals(1, packet.refCnt(), "a forwarded packet keeps its reference");
        packet.release();
    }

    // ── 2. per-IP budget ─────────────────────────────────────────────────

    @Test
    void thirdInitialFromTheSameIpIsDroppedAndReleasedExactlyOnce() {
        EmbeddedChannel channel = channel(admission(1000, 2, 1, 1, 1000, 100));

        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "first Initial admitted");
        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "second Initial admitted");

        ByteBuf content = initialPacket();
        assertNull(send(channel, content, SENDER_1), "third Initial over the per-IP budget is dropped");
        assertEquals(0, content.refCnt(), "a dropped datagram is released exactly once");

        assertTrue(forwarded(channel, initialPacket(), SENDER_2), "another IP keeps its own budget");
    }

    // ── 3. global budget ─────────────────────────────────────────────────

    @Test
    void thirdInitialOverTheGlobalBudgetIsDropped() {
        EmbeddedChannel channel = channel(admission(2, 1000, 1, 1, 2, 100));

        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "first global attempt");
        assertTrue(forwarded(channel, initialPacket(), SENDER_2), "second global attempt");

        ByteBuf content = initialPacket();
        assertNull(send(channel, content, SENDER_3), "third Initial over the global budget is dropped");
        assertEquals(0, content.refCnt(), "a dropped datagram is released exactly once");
    }

    // ── 4. only Initials are counted ─────────────────────────────────────

    @Test
    void nonInitialDatagramsPassAndAreNeverCounted() {
        EmbeddedChannel channel = channel(admission(1000, 2, 1, 1, 1000, 100));

        for (int i = 0; i < 100; i++) {
            assertTrue(forwarded(channel, shortHeaderPacket(), SENDER_1),
                    "short-header packet " + i + " must pass");
        }
        assertTrue(forwarded(channel, handshakePacket(), SENDER_1), "Handshake must pass");
        assertTrue(forwarded(channel, garbage(), SENDER_1), "unparseable garbage must pass");

        // None of the non-Initials was counted, so the whole per-IP Initial allowance is still there.
        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "first Initial after the non-Initial flood");
        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "second Initial after the non-Initial flood");
        assertFalse(forwarded(channel, initialPacket(), SENDER_1),
                "only now is the per-IP budget exhausted, proving non-Initials were never counted");
    }

    // ── 5. non-datagram messages ─────────────────────────────────────────

    @Test
    void nonDatagramMessagesAreForwarded() {
        EmbeddedChannel channel = channel(admission(1, 1, 1, 1, 1, 10));
        String message = "not a datagram";
        channel.writeInbound(message);
        assertSame(message, channel.readInbound(), "a non-DatagramPacket message is passed through");
    }

    // ── 6. window rollover ───────────────────────────────────────────────

    @Test
    void windowRolloverAdmitsTheIpAgain() {
        AtomicLong now = new AtomicLong(0);
        EmbeddedChannel channel = channel(new ConnectionAdmission(1000, 2, 1, 1, 1000, 100, now::get));

        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "first Initial");
        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "second Initial");
        assertFalse(forwarded(channel, initialPacket(), SENDER_1), "third Initial in the same window is dropped");

        now.set(WINDOW);
        assertTrue(forwarded(channel, initialPacket(), SENDER_1), "the next window admits the IP again");
    }

    // ── 7. fail-open ─────────────────────────────────────────────────────

    @Test
    void unresolvedSenderFailsOpenAndIsForwarded() {
        EmbeddedChannel channel = channel(admission(1, 1, 1, 1, 1, 10));
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("example.invalid", 5000);

        DatagramPacket packet = new DatagramPacket(initialPacket(), RECIPIENT, unresolved);
        channel.writeInbound(packet);

        assertSame(packet, channel.readInbound(), "an unresolved sender must fail open");
        assertEquals(1, packet.refCnt(), "failed-open packet is not released");
        release(packet);
    }
}
