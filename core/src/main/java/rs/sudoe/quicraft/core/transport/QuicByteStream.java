// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * One QUIC stream as an ordered byte stream, with no Netty types in the API, so a bridge in any
 * Netty generation can wrap it in a game {@code Channel} (CLAUDE.md "Architecture").
 *
 * <p>Threading: listener callbacks run on the stream's own thread. Every method may be called
 * from any thread.
 */
public interface QuicByteStream {
    /** The peer's UDP address, for IP bans, forwarding and logging. */
    InetSocketAddress remoteAddress();

    InetSocketAddress localAddress();

    /** Sets the receiver. Data is only delivered once a listener is set and auto-read is on. */
    void setListener(Listener listener);

    /** Queues a copy of the buffer's remaining bytes; the buffer may be reused right away. */
    void write(ByteBuffer data);

    void flush();

    boolean isWritable();

    /** Starts (true) or stops (false) delivering data as it arrives. Off until enabled. */
    void setAutoRead(boolean autoRead);

    /** Delivers the next available data once when auto-read is off. */
    void read();

    boolean isOpen();

    /** Closes the stream and its connection with {@code CONNECTION_CLOSE} code 0. */
    void close();

    /** Runs a task on the stream's thread. */
    void execute(Runnable task);

    boolean inEventLoop();

    interface Listener {
        /** The buffer is only valid during the call. */
        void onData(ByteBuffer data);

        void onWritabilityChanged(boolean writable);

        /** Called once. {@code cause} is null for a normal close. */
        void onClosed(Throwable cause);
    }
}
