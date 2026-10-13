// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Hardening of listener admission after the Phase 4 security review (docs/protocol.md §9). */
class AdmissionHardeningTest {
    private static InetSocketAddress client(int lastOctet) throws Exception {
        return new InetSocketAddress(InetAddress.getByName("192.0.2." + lastOctet), 40000);
    }

    private static ByteBuf dcid() {
        return Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }

    private static ConnectionAdmission admission(int global, int perIp, int maxOpen) {
        return new ConnectionAdmission(global, perIp, maxOpen, maxOpen, global, 100);
    }

    @Test
    void validatedAttemptsPassWhileTokenlessAttemptsAreExhausted() throws Exception {
        AtomicLong retries = new AtomicLong();
        AdmissionTokenHandler handler = new AdmissionTokenHandler(admission(2, 2, 10), admission(2, 2, 1),
                TransportConfig.RetryMode.NEVER, retries);
        // Two token-less attempts from other IPs use up the token-less global budget...
        assertFalse(handler.writeToken(Unpooled.buffer(), dcid(), client(1)));
        assertFalse(handler.writeToken(Unpooled.buffer(), dcid(), client(2)));
        // ...so the next one is refused with a Retry, whose token the client brings back.
        ByteBuf token = Unpooled.buffer();
        assertTrue(handler.writeToken(token, dcid(), client(9)), "an attempt over the limit gets a Retry");
        for (int i = 10; i < 50; i++) {
            assertTrue(handler.writeToken(Unpooled.buffer(), dcid(), client(i)), "the token-less flood gets Retries");
        }
        assertTrue(handler.validateToken(token, client(9)) > 0,
                "a valid token spends the separate validated budget, which the flood didn't touch");
        assertEquals(41, retries.get());
    }

    @Test
    void adaptiveForcesRetryWhilePendingLeasesReachAQuarterOfMaxOpen() throws Exception {
        ConnectionAdmission admission = admission(100, 100, 4);
        AdmissionTokenHandler handler = new AdmissionTokenHandler(admission, admission(100, 100, 1),
                TransportConfig.RetryMode.ADAPTIVE, new AtomicLong());
        assertFalse(handler.writeToken(Unpooled.buffer(), dcid(), client(1)), "no Retry at low load");
        assertEquals(0, admission.pendingLeases(), "the open-slot probe leaves pending unchanged");

        ConnectionAdmission.Lease pending = admission.tryAcquire(client(1).getAddress());
        assertTrue(handler.writeToken(Unpooled.buffer(), dcid(), client(2)), "1 of 4 pending forces Retry");
        assertEquals(1, admission.pendingLeases(), "the probe left pending unchanged");

        pending.confirm();
        assertFalse(handler.writeToken(Unpooled.buffer(), dcid(), client(3)), "confirmed: no Retry");
        pending.close();
        assertEquals(0, admission.pendingLeases());
    }

    /** A datagram of {@code size} bytes starting with a long-header Initial (QUIC v1). */
    private static DatagramPacket initial(int size) throws Exception {
        ByteBuf b = Unpooled.buffer(size);
        b.writeByte(0xC0); // long header, fixed bit, type Initial
        b.writeInt(1); // version
        b.writeByte(8);
        b.writeBytes(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}); // DCID
        b.writeByte(0); // SCID length
        b.writeByte(0); // token length
        b.writeZero(size - b.writerIndex()); // the rest: length, packet number, payload
        return new DatagramPacket(b, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565), client(1));
    }

    @Test
    void initialDatagramsUnder1200BytesAreDroppedWithoutSpendingBudget() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
                new IngressGuard(new ConnectionAdmission(1, 1, 1, 1, 1, 10), Codecs.LOCAL_CONNECTION_ID_LENGTH));
        try {
            DatagramPacket small = initial(1199);
            assertFalse(channel.writeInbound(small));
            assertNull(channel.readInbound(), "a 1199-byte Initial is dropped");
            assertEquals(0, small.refCnt(), "and released once");

            DatagramPacket full = initial(1200);
            channel.writeInbound(full);
            DatagramPacket passed = channel.readInbound();
            assertSame(full, passed, "a 1200-byte Initial passes unchanged, the short one spent no budget");
            assertEquals(0, passed.content().readerIndex());
            assertEquals(1200, passed.content().readableBytes());
            passed.release();

            DatagramPacket over = initial(1200);
            channel.writeInbound(over);
            assertNull(channel.readInbound(), "the budget of one is now spent");
            assertEquals(0, over.refCnt());

            ByteBuf shortHeader = Unpooled.buffer(30).writeByte(0x40).writeZero(29);
            DatagramPacket oneRtt = new DatagramPacket(shortHeader,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565), client(1));
            channel.writeInbound(oneRtt);
            DatagramPacket passedShort = channel.readInbound();
            assertNotNull(passedShort, "short-header packets of any size pass");
            passedShort.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
