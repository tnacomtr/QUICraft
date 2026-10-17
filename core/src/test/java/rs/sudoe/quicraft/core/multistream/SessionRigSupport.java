// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared packet and stream helpers, independent of the legacy session fixture. */
class SessionRigSupport {
    /** One Minecraft frame: VarInt(length) then the packet id byte and the payload. */
    static byte[] frame(int packetId, byte[] payload) {
        int inner = 1 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(inner + 3);
        int v = inner;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.write(packetId);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    static byte[] frame(int packetId, int payloadLength) {
        return frame(packetId, new byte[payloadLength]);
    }

    static byte[] frame(int packetId, String label) {
        return frame(packetId, label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static final class Closed {
        final int code;
        final boolean byPeer;

        Closed(int code, boolean byPeer) {
            this.code = code;
            this.byPeer = byPeer;
        }

        @Override
        public String toString() {
            return "closed " + Integer.toHexString(code) + (byPeer ? " by peer" : " locally");
        }
    }

    /** Collects what a raw stream receives. */
    static final class Bytes extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] chunk = new byte[buf.readableBytes()];
                buf.readBytes(chunk);
                synchronized (bytes) {
                    bytes.write(chunk, 0, chunk.length);
                }
            } finally {
                buf.release();
            }
        }

        byte[] snapshot() {
            synchronized (bytes) {
                return bytes.toByteArray();
            }
        }
    }

    /**
     * The owner of the client's stream 0 before activation, standing in for the login code of
     * 12.11/12.13. It never discards what it reads: a server may start records right after its own
     * activation (12.12), so bytes read here are the start of the record stream and are handed to
     * the session as the activation leftover, exactly as {@code V3ConnectionImpl} does.
     */
    static final class EarlyControl extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private volatile int retained;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] chunk = new byte[buf.readableBytes()];
                buf.readBytes(chunk);
                synchronized (kept) {
                    kept.write(chunk, 0, chunk.length);
                    retained = kept.size();
                }
            } finally {
                buf.release();
            }
        }

        /** Takes every byte read so far as the activation leftover; call on the client's loop. */
        java.nio.ByteBuffer take() {
            synchronized (kept) {
                if (kept.size() == 0) {
                    return null;
                }
                byte[] bytes = kept.toByteArray();
                kept.reset();
                retained = 0;
                return java.nio.ByteBuffer.wrap(bytes);
            }
        }

        /** How many bytes this owner read and has not handed over yet. */
        int retained() {
            return retained;
        }
    }

    static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }
}
