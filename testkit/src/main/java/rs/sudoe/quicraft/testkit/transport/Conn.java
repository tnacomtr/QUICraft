// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.transport;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * A blocking, message-framed connection over TCP or a QUICraft QUIC stream. Frames are
 * {@code [int length][byte type][payload]}; writes are serialized.
 */
abstract class Conn implements Closeable {
    static final byte HELLO = 1;
    static final byte LOGIN = 2;
    static final byte CHUNK = 3;
    static final byte END_BURST = 4;
    static final byte ENTITY = 5;
    static final byte PING = 6;
    static final byte PONG = 7;
    static final byte BYE = 8;
    /** Server to client after BYE: the sender's counters as "key=value,…". */
    static final byte STATS = 9;

    private DataInputStream in;

    abstract InputStream input();

    abstract OutputStream output();

    /** Sender-side transport counters for this connection; empty if unknown. */
    abstract java.util.Map<String, Long> senderStats();

    synchronized void send(byte type, byte[] payload) throws IOException {
        OutputStream out = output();
        int length = payload.length + 1;
        byte[] frame = new byte[4 + length];
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        frame[4] = type;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        out.write(frame);
        out.flush();
    }

    /** Returns {type, payload...}; blocks. */
    byte[] receive() throws IOException {
        if (in == null) {
            in = new DataInputStream(input());
        }
        int length = in.readInt();
        if (length < 1 || length > (1 << 20)) {
            throw new IOException("bad frame length " + length);
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        return frame;
    }

    static Conn tcp(Socket socket) throws IOException {
        socket.setTcpNoDelay(true); // as Minecraft does
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        return new Conn() {
            @Override
            InputStream input() {
                return in;
            }

            @Override
            OutputStream output() {
                return out;
            }

            @Override
            java.util.Map<String, Long> senderStats() {
                return TcpCounters.snapshot();
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }

    static Conn quic(QuicByteStream stream) {
        StreamInput in = new StreamInput();
        Object writable = new Object();
        stream.setListener(new QuicByteStream.Listener() {
            @Override
            public void onData(ByteBuffer data) {
                in.offer(data);
            }

            @Override
            public void onWritabilityChanged(boolean isWritable) {
                synchronized (writable) {
                    writable.notifyAll();
                }
            }

            @Override
            public void onClosed(Throwable cause) {
                in.close();
                synchronized (writable) {
                    writable.notifyAll();
                }
            }
        });
        stream.setAutoRead(true);
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                // Respect backpressure like a blocking socket would.
                synchronized (writable) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (!stream.isWritable() && stream.isOpen()) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            throw new IOException("QUIC stream stayed unwritable");
                        }
                        try {
                            TimeUnit.NANOSECONDS.timedWait(writable, Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                }
                if (!stream.isOpen()) {
                    throw new IOException("QUIC stream closed");
                }
                stream.write(ByteBuffer.wrap(b, off, len));
            }

            @Override
            public void flush() {
                stream.flush();
            }
        };
        return new Conn() {
            @Override
            InputStream input() {
                return in;
            }

            @Override
            OutputStream output() {
                return out;
            }

            @Override
            java.util.Map<String, Long> senderStats() {
                try {
                    return stream.connectionStats().get(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return java.util.Collections.emptyMap();
                }
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }

    /** Bytes from stream callbacks, read by a blocking thread. */
    private static final class StreamInput extends InputStream {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private byte[] current;
        private int pos;
        private boolean closed;

        synchronized void offer(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            chunks.add(b);
            notifyAll();
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        @Override
        public synchronized int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            while (current == null || pos == current.length) {
                current = chunks.poll();
                pos = 0;
                if (current != null) {
                    continue;
                }
                if (closed) {
                    return -1;
                }
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
