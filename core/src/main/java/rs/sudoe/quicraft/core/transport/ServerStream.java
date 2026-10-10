// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.nio.ByteBuffer;

/**
 * The server's side of stream 0 (docs/protocol.md §8): passes the client's token to its
 * connection, which decides when the game gets the stream, and sends the server's preamble first.
 */
final class ServerStream extends PreambleStream {
    interface PreambleHandler {
        /** On the event loop. {@code token} may be null. */
        void onPreamble(ServerStream stream, byte[] token);
    }

    private final PreambleHandler handler;
    /** Event loop only: reads the first flight of a stream passed early, for its player name. */
    private FlightRecorder flight;
    private java.util.function.Consumer<String> onName;

    ServerStream(NettyQuicByteStream raw, PreambleHandler handler) {
        super(raw);
        this.handler = handler;
    }

    /** On the event loop: report the Login Start's player name once the first flight is in. */
    void readNameThen(java.util.function.Consumer<String> onName) {
        this.flight = new FlightRecorder();
        this.onName = onName;
    }

    @Override
    void onPeerData(ByteBuffer data) {
        if (flight == null) {
            return;
        }
        int state = flight.add(data);
        if (state == FlightRecorder.MORE) {
            return;
        }
        String name = state == FlightRecorder.DONE ? FirstFlight.playerName(flight.flight()) : null;
        flight = null;
        if (name != null) {
            onName.accept(name);
        }
    }

    @Override
    void onPreamble(byte[] token) {
        handler.onPreamble(this, token);
    }

    /** Sends the server's preamble with a fresh token (null for none), ahead of the game's bytes. */
    void writePreamble(byte[] token) {
        raw.write(ByteBuffer.wrap(Preamble.encode(token)));
        raw.flush();
    }
}
