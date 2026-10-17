// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.DefaultMaxMessagesRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.handler.codec.quic.QuicStreamChannel;

/** Installed before the first login read: Netty QUIC caches its receive handle for stream life. */
public final class ReadGrantAllocator extends DefaultMaxMessagesRecvByteBufAllocator {
    private int limit = 16 * 1024;

    /** Package-private so tests can exercise a cached handle without a live QUIC channel. */
    ReadGrantAllocator() {
        super(1);
    }

    /** Must run on the stream's event loop, before any read can cache a different handle. */
    public static void install(QuicStreamChannel stream) {
        if (!(stream.config().getRecvByteBufAllocator() instanceof ReadGrantAllocator)) {
            stream.config().setRecvByteBufAllocator(new ReadGrantAllocator());
        }
        // Merely replacing config after login does not replace QUIC's cached handle. Fail safe
        // rather than silently allowing an older, larger allocator to exceed a new read grant.
        ReadGrantAllocator allocator = (ReadGrantAllocator) stream.config().getRecvByteBufAllocator();
        if (!(stream.unsafe().recvBufAllocHandle() instanceof ReadGrantAllocator.GrantHandle)
                || ((GrantHandle) stream.unsafe().recvBufAllocHandle()).owner() != allocator) {
            throw new IllegalStateException("read grant allocator must be installed before the first read");
        }
    }

    static void limit(QuicStreamChannel stream, int bytes) {
        ((ReadGrantAllocator) stream.config().getRecvByteBufAllocator()).setLimit(bytes);
    }

    /**
     * Sets the grant the next read may allocate. A handle cached by QUIC reads this field on every
     * call, so the change applies without replacing the handle. Callers keep the value at most the
     * read grant they reserved.
     */
    void setLimit(int bytes) {
        if (bytes < 0 || bytes > ServerReadPartition.MAX_READ_GRANT) {
            throw new IllegalArgumentException("read grant " + bytes);
        }
        limit = bytes;
    }

    @Override
    public RecvByteBufAllocator.Handle newHandle() {
        return new GrantHandle();
    }

    private final class GrantHandle extends MaxMessageHandle {
        ReadGrantAllocator owner() {
            return ReadGrantAllocator.this;
        }

        @Override
        public int guess() {
            return limit;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(limit, limit);
        }
    }
}
