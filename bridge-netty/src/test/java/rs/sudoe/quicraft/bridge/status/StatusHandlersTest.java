// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

@SuppressWarnings("deprecation") // NioEventLoopGroup: deprecated in 4.2, the only choice in 4.1
class StatusHandlersTest {
    private static Advertisement ad;
    private static final String STATUS = "{\"version\":{\"name\":\"26.1.2\",\"protocol\":775},\"description\":\"hi\"}";

    @BeforeAll
    static void setUp() throws Exception {
        ad = Advertisement.v1(25566, ServerIdentity.generate().fingerprint());
    }

    /** A game-like pipeline: pass-through stand-ins for the frame decoder and encoder. */
    private static EmbeddedChannel gamePipeline(Advertisement advertised) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        StatusAdvertising.install(ch.pipeline(), "splitter", "prepender", () -> advertised);
        return ch;
    }

    static ByteBuf handshake(int intent) {
        ByteBuf b = Unpooled.buffer();
        Wire.writeVarInt(b, 0);
        Wire.writeVarInt(b, 775);
        Wire.writeString(b, "play.example.org");
        b.writeShort(25565);
        Wire.writeVarInt(b, intent);
        return b;
    }

    static ByteBuf statusResponse(String json) {
        ByteBuf b = Unpooled.buffer();
        Wire.writeVarInt(b, 0);
        Wire.writeString(b, json);
        return b;
    }

    private static String json(ByteBuf packet) {
        assertEquals(Integer.valueOf(0), Wire.readVarInt(packet));
        String s = Wire.readString(packet, Wire.MAX_STATUS_BYTES);
        assertFalse(packet.isReadable());
        packet.release();
        return s;
    }

    @Test
    void aStatusResponseGetsTheAdvertisementAndTheHandlersLeave() {
        EmbeddedChannel ch = gamePipeline(ad);
        ByteBuf hs = handshake(1);
        ByteBuf copy = hs.copy();
        ch.writeInbound(hs);
        ByteBuf passed = ch.readInbound();
        assertEquals(copy, passed, "the handshake passes unchanged");
        passed.release();
        copy.release();
        assertNull(ch.pipeline().get(StatusAdvertising.INTENT));

        ch.writeOutbound(statusResponse(STATUS));
        String out = json(ch.readOutbound());
        assertEquals(Optional.of(ad), Advertisement.extract(out));
        assertTrue(out.startsWith(STATUS.substring(0, STATUS.length() - 1)), "the original members are kept");
        assertNull(ch.pipeline().get(StatusAdvertising.REWRITER));

        ByteBuf pong = Unpooled.buffer().writeByte(1).writeLong(42);
        ch.writeOutbound(pong.retain());
        ByteBuf pongOut = ch.readOutbound();
        assertEquals(pong, pongOut, "later packets pass untouched");
        pong.release();
        pongOut.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void aLoginConnectionIsNeverTouched() {
        EmbeddedChannel ch = gamePipeline(ad);
        ch.writeInbound(handshake(2));
        ((ByteBuf) ch.readInbound()).release();
        assertNull(ch.pipeline().get(StatusAdvertising.REWRITER), "not a status connection: the rewriter leaves");
        ByteBuf packet = statusResponse(STATUS); // id 0 in login is a disconnect: must pass as is
        ch.writeOutbound(packet.retain());
        ByteBuf out = ch.readOutbound();
        assertEquals(packet, out);
        packet.release();
        out.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void theAdvertisementIsLeftOutWhenWithdrawnOrOverTheCap() {
        EmbeddedChannel withdrawn = gamePipeline(null);
        withdrawn.writeInbound(handshake(1));
        ((ByteBuf) withdrawn.readInbound()).release();
        withdrawn.writeOutbound(statusResponse(STATUS));
        assertEquals(STATUS, json(withdrawn.readOutbound()));
        withdrawn.finishAndReleaseAll();

        StringBuilder motd = new StringBuilder();
        while (motd.length() < Advertisement.STATUS_MAX_LENGTH - 40) {
            motd.append('x');
        }
        String big = "{\"description\":\"" + motd + "\"}";
        EmbeddedChannel full = gamePipeline(ad);
        full.writeInbound(handshake(1));
        ((ByteBuf) full.readInbound()).release();
        full.writeOutbound(statusResponse(big));
        assertEquals(big, json(full.readOutbound()), "would exceed 32767: left out");
        full.finishAndReleaseAll();
    }

    @Test
    void garbageInsteadOfAHandshakePassesAndRemovesBothHandlers() {
        EmbeddedChannel ch = gamePipeline(ad);
        ByteBuf junk = Unpooled.wrappedBuffer(new byte[] {(byte) 0xFE, 0x01});
        ch.writeInbound(junk);
        ByteBuf in = ch.readInbound();
        assertEquals(2, in.readableBytes());
        in.release();
        assertNull(ch.pipeline().get(StatusAdvertising.INTENT));
        assertNull(ch.pipeline().get(StatusAdvertising.REWRITER));
        ch.finishAndReleaseAll();
    }

    @Test
    void theSnifferReadsTheAdvertisementWithoutChangingTheResponse() {
        AtomicReference<Optional<Advertisement>> seen = new AtomicReference<>();
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        StatusSniffer.install(ch.pipeline(), "splitter", seen::set);
        String advertised = ad.insertInto(STATUS);
        ByteBuf frame = statusResponse(advertised);
        ch.writeInbound(frame.retain());
        ByteBuf passed = ch.readInbound();
        assertEquals(frame, passed);
        frame.release();
        passed.release();
        assertEquals(Optional.of(ad), seen.get());
        assertNull(ch.pipeline().get(StatusSniffer.NAME));
        ch.finishAndReleaseAll();

        AtomicReference<Optional<Advertisement>> none = new AtomicReference<>();
        EmbeddedChannel plain = new EmbeddedChannel();
        plain.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        StatusSniffer.install(plain.pipeline(), "splitter", none::set);
        plain.writeInbound(statusResponse(STATUS));
        ((ByteBuf) plain.readInbound()).release();
        assertEquals(Optional.empty(), none.get());
        plain.finishAndReleaseAll();
    }

    /** One-shot status server on a plain socket: checks the handshake, answers with {@code json}. */
    private static CompletableFuture<String> statusServer(ServerSocket server, String json) {
        CompletableFuture<String> hostSeen = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                int length = readVarInt(in);
                byte[] hs = new byte[length];
                in.readFully(hs);
                ByteBuf b = Unpooled.wrappedBuffer(hs);
                Wire.readVarInt(b);
                Wire.readVarInt(b);
                String host = Wire.readString(b, 1000);
                b.readShort();
                assertEquals(Integer.valueOf(1), Wire.readVarInt(b));
                assertEquals(1, readVarInt(in));
                assertEquals(0, in.read());
                ByteBuf body = statusResponse(json);
                ByteBuf frame = Unpooled.buffer();
                Wire.writeVarInt(frame, body.readableBytes());
                frame.writeBytes(body);
                byte[] out = new byte[frame.readableBytes()];
                frame.readBytes(out);
                OutputStream os = s.getOutputStream();
                // Split in two writes so the client has to reassemble.
                os.write(out, 0, out.length / 2);
                os.flush();
                Thread.sleep(20);
                os.write(out, out.length / 2, out.length - out.length / 2);
                os.flush();
                hostSeen.complete(host);
                Thread.sleep(100);
            } catch (Throwable e) {
                hostSeen.completeExceptionally(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return hostSeen;
    }

    private static int readVarInt(InputStream in) throws java.io.IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new java.io.IOException("bad VarInt");
    }

    @Test
    void theStatusQueryReturnsTheRawJsonIncludingTheAdvertisement() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            StringBuilder motd = new StringBuilder();
            while (motd.length() < 20000) {
                motd.append("é");
            }
            String status = ad.insertInto("{\"description\":\"" + motd + "\"}");
            CompletableFuture<String> host = statusServer(server, status);
            String json = StatusQuery.query(group, new ReflectiveChannelFactory<>(NioSocketChannel.class),
                    (InetSocketAddress) server.getLocalSocketAddress(), "play.example.org", 775).get(5, TimeUnit.SECONDS);
            assertEquals(status, json);
            assertEquals("play.example.org", host.get(5, TimeUnit.SECONDS));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void theStatusQueryFailsWhenTheServerClosesOrRefuses() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            InetSocketAddress refused;
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                refused = (InetSocketAddress) s.getLocalSocketAddress();
            }
            CompletableFuture<String> q = StatusQuery.query(group, new ReflectiveChannelFactory<>(NioSocketChannel.class),
                    refused, "x", 775);
            assertThrows(ExecutionException.class, () -> q.get(5, TimeUnit.SECONDS));

            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Thread t = new Thread(() -> {
                    try (Socket s = server.accept()) {
                        s.getInputStream().read();
                    } catch (Exception ignored) {
                        // closing is the point
                    }
                });
                t.start();
                CompletableFuture<String> closed = StatusQuery.query(group,
                        new ReflectiveChannelFactory<>(NioSocketChannel.class),
                        (InetSocketAddress) server.getLocalSocketAddress(), "x", 775);
                assertThrows(ExecutionException.class, () -> closed.get(5, TimeUnit.SECONDS));
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
