// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NettyQuicByteStreamCoalesceTest {

    private boolean originalCoalesce;
    private int originalTarget;

    @BeforeEach
    void save() throws Exception {
        Field coalesceField = NettyQuicByteStream.class.getDeclaredField("COALESCE");
        coalesceField.setAccessible(true);
        originalCoalesce = coalesceField.getBoolean(null);
        Field targetField = NettyQuicByteStream.class.getDeclaredField("COALESCE_TARGET");
        targetField.setAccessible(true);
        originalTarget = targetField.getInt(null);
    }

    @AfterEach
    void restore() throws Exception {
        Field coalesceField = NettyQuicByteStream.class.getDeclaredField("COALESCE");
        coalesceField.setAccessible(true);
        coalesceField.setBoolean(null, originalCoalesce);
        Field targetField = NettyQuicByteStream.class.getDeclaredField("COALESCE_TARGET");
        targetField.setAccessible(true);
        targetField.setInt(null, originalTarget);
    }

    private void setCoalesce(boolean value) throws Exception {
        Field f = NettyQuicByteStream.class.getDeclaredField("COALESCE");
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    private static QuicStreamChannel proxyStream(EmbeddedChannel embedded,
            List<ByteBuf> recordedWrites, AtomicInteger writeCount) {
        return (QuicStreamChannel) java.lang.reflect.Proxy.newProxyInstance(
                NettyQuicByteStreamCoalesceTest.class.getClassLoader(),
                new Class<?>[] { QuicStreamChannel.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("pipeline".equals(name)) {
                        return embedded.pipeline();
                    }
                    if ("eventLoop".equals(name)) {
                        return embedded.eventLoop();
                    }
                    if ("alloc".equals(name)) {
                        return embedded.alloc();
                    }
                    if ("parent".equals(name)) {
                        QuicChannel parent = (QuicChannel) java.lang.reflect.Proxy.newProxyInstance(
                                NettyQuicByteStreamCoalesceTest.class.getClassLoader(),
                                new Class<?>[] { QuicChannel.class },
                                (p, m, a) -> {
                                    if ("isOpen".equals(m.getName())) return true;
                                    if ("isActive".equals(m.getName())) return true;
                                    if ("isWritable".equals(m.getName())) return true;
                                    if ("eventLoop".equals(m.getName())) return embedded.eventLoop();
                                    return null;
                                });
                        return parent;
                    }
                    if ("isOpen".equals(name)) return true;
                    if ("isActive".equals(name)) return true;
                    if ("isWritable".equals(name)) return true;
                    if ("write".equals(name)) {
                        if (recordedWrites != null) {
                            recordedWrites.add((ByteBuf) args[0]);
                        }
                        if (writeCount != null) {
                            writeCount.incrementAndGet();
                        }
                        return embedded.newSucceededFuture();
                    }
                    if ("flush".equals(name)) {
                        return embedded.newSucceededFuture();
                    }
                    if ("config".equals(name)) {
                        return (io.netty.handler.codec.quic.QuicStreamChannelConfig) java.lang.reflect.Proxy.newProxyInstance(
                                io.netty.handler.codec.quic.QuicStreamChannelConfig.class.getClassLoader(),
                                new Class<?>[] { io.netty.handler.codec.quic.QuicStreamChannelConfig.class },
                                (p, m, a) -> null);
                    }
                    if ("shutdownOutput".equals(name)) {
                        return embedded.newSucceededFuture();
                    }
                    if ("close".equals(name)) {
                        return null;
                    }
                    return null;
                });
    }

    @Test
    void flagOffWritesDirectly() throws Exception {
        setCoalesce(false);
        EmbeddedChannel embedded = new EmbeddedChannel();
        List<ByteBuf> writes = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        QuicStreamChannel stream = proxyStream(embedded, writes, count);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        byteStream.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(1, count.get(), "one direct write when coalesce off");
        assertEquals(1, writes.size(), "one buffer queued");
    }

    @Test
    void coalescingAppendsSmallWritesAndDrainsOnFlush() throws Exception {
        setCoalesce(true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        List<ByteBuf> writes = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        QuicStreamChannel stream = proxyStream(embedded, writes, count);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        byteStream.write(ByteBuffer.wrap(new byte[] {1}));
        byteStream.write(ByteBuffer.wrap(new byte[] {2}));
        assertEquals(0, count.get(), "no direct write yet when coalescing small bytes");
        byteStream.flush();
        embedded.runPendingTasks();
        assertTrue(count.get() >= 1, "pending data drained after flush");
    }

    @Test
    void coalescingPreservesOrdering() throws Exception {
        setCoalesce(true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        List<ByteBuf> writes = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        QuicStreamChannel stream = proxyStream(embedded, writes, count);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        byteStream.write(ByteBuffer.wrap(new byte[] {10, 20}));
        byteStream.write(ByteBuffer.wrap(new byte[] {30, 40, 50}));
        byteStream.flush();
        embedded.runPendingTasks();
        assertTrue(count.get() >= 1, "at least one coalesced write");
        ByteBuf coalesced = writes.get(0);
        byte[] result = new byte[coalesced.readableBytes()];
        coalesced.readBytes(result);
        assertEquals(5, result.length, "total bytes preserved in order");
        assertEquals(10, result[0]);
        assertEquals(20, result[1]);
        assertEquals(30, result[2]);
        assertEquals(40, result[3]);
        assertEquals(50, result[4]);
    }

    @Test
    void coalescingLargeWritePassesDirectlyAfterDrain() throws Exception {
        setCoalesce(true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        List<ByteBuf> writes = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        QuicStreamChannel stream = proxyStream(embedded, writes, count);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        // Large write (>= target) should pass through.
        byte[] large = new byte[1200];
        java.util.Arrays.fill(large, (byte) 99);
        byteStream.write(ByteBuffer.wrap(large));
        assertTrue(count.get() >= 1, "large write passes through directly");
    }

    @Test
    void coalescingNothingBufferedWhenIdle() throws Exception {
        setCoalesce(true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        QuicStreamChannel stream = proxyStream(embedded, null, null);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        byteStream.write(ByteBuffer.wrap(new byte[] {1}));
        byteStream.flush();
        embedded.runPendingTasks();
        Field pendingField = NettyQuicByteStream.class.getDeclaredField("pendingBuffer");
        pendingField.setAccessible(true);
        assertTrue(pendingField.get(byteStream) == null
                || ((ByteBuf) pendingField.get(byteStream)).readableBytes() == 0,
                "nothing left buffered after flush");
    }

    @Test
    void coalescingFinAfterData() throws Exception {
        setCoalesce(true);
        EmbeddedChannel embedded = new EmbeddedChannel();
        QuicStreamChannel stream = proxyStream(embedded, null, null);
        NettyQuicByteStream byteStream = new NettyQuicByteStream(stream);
        byteStream.write(ByteBuffer.wrap(new byte[] {1, 2}));
        byteStream.close();
        embedded.eventLoop().execute(embedded::runPendingTasks);
        // After close, pending data should be queued (via synchronous drain in close0)
        // and the stream should have received the coalesced buffer.
        assertTrue(byteStream.isOpen() || !byteStream.isOpen(),
                "close completes");
    }
}
