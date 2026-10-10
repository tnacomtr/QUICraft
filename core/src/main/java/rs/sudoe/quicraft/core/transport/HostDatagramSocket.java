// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * A bound UDP socket owned by the platform (e.g. a game-Netty {@code DatagramChannel} on the
 * {@link HostLoop}), carrying a QUIC endpoint. All calls, both ways, happen on that loop.
 */
public interface HostDatagramSocket {
    InetSocketAddress localAddress();

    /** Starts delivering datagrams to {@code receiver}. Called once. */
    void start(Receiver receiver);

    /** Queues one datagram. {@code data} is only valid during the call. */
    void write(ByteBuffer data, InetSocketAddress recipient);

    void flush();

    /** False while the socket's send queue is full; {@link Receiver#onWritabilityChanged} follows. */
    boolean isWritable();

    void close();

    interface Receiver {
        /** {@code data} is only valid during the call. */
        void onDatagram(ByteBuffer data, InetSocketAddress sender);

        /** After a batch of {@link #onDatagram} calls: QUIC sends its replies here. */
        void onReadComplete();

        void onWritabilityChanged(boolean writable);

        void onClosed();
    }
}
