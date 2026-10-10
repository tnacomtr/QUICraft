// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stream 0 as the game sees it: the peer's preamble (docs/protocol.md §8) is read and stripped
 * here, whatever the game's read settings, and nothing reaches the game until the subclass opens
 * the gate. Until then the data after the preamble is kept, and the underlying stream is paused
 * (flow control holds the rest). Once open, the game's auto-read and read requests apply, after
 * what was kept.
 */
abstract class PreambleStream implements QuicByteStream, QuicByteStream.Listener {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    final NettyQuicByteStream raw;
    private final Preamble.Parser parser = new Preamble.Parser();
    private volatile Listener listener;
    /** The game wrote on the stream (the preambles don't count). */
    private volatile boolean gameWrote;

    // Event loop only.
    private final ArrayDeque<ByteBuffer> kept = new ArrayDeque<>();
    private boolean gateOpen;
    private boolean autoRead;
    private boolean readRequested;
    private boolean rawClosed;
    private Throwable rawCloseCause;
    private boolean closeDelivered;
    /** Set by discardKeptAndOpenGate: data is dropped from then on. */
    private boolean discarding;

    PreambleStream(NettyQuicByteStream raw) {
        this.raw = raw;
    }

    /**
     * Starts reading the peer's preamble, whatever the game wants. Separate from the constructor:
     * on the event loop, data already received may be delivered within this call.
     */
    final void start() {
        raw.setListener(this);
        raw.setAutoRead(true);
    }

    /** On the event loop, once, when the peer's preamble is complete. {@code token} may be null. */
    abstract void onPreamble(byte[] token);

    /** On the event loop: the peer violated the preamble format. */
    void onInvalidPreamble(Preamble.InvalidPreambleException e) {
        LOG.log(Level.FINE, "invalid QUICraft preamble; closing", e);
        Codecs.closeLater(raw.connection(), true,
                rs.sudoe.quicraft.core.Protocol.CLOSE_PROTOCOL_VIOLATION);
    }

    /** On the event loop: data after the preamble, before it goes on. Must not consume it. */
    void onPeerData(ByteBuffer data) {}

    /** On the event loop: the underlying stream closed before the gate opened. */
    void onClosedBeforeGate(Throwable cause) {}

    /** Lets the kept data and everything after it through to the game. Any thread. */
    final void openGate() {
        execute(() -> {
            gateOpen = true;
            drain();
        });
    }

    /** Drops what was kept and all data after it, and opens the gate: only a close reaches the game. Any thread. */
    final void discardKeptAndOpenGate() {
        execute(() -> {
            discarding = true;
            kept.clear();
            gateOpen = true;
            drain();
        });
    }

    final boolean preambleRead() {
        return parser.done();
    }

    // --- Listener on the underlying stream (event loop).

    @Override
    public final void onData(ByteBuffer data) {
        if (!parser.done()) {
            boolean complete;
            try {
                complete = parser.feed(data);
            } catch (Preamble.InvalidPreambleException e) {
                onInvalidPreamble(e);
                return;
            }
            if (!complete) {
                return;
            }
            if (!gateOpen) {
                raw.setAutoRead(false);
            }
            onPreamble(parser.token());
        }
        if (!data.hasRemaining() || discarding) {
            return;
        }
        onPeerData(data);
        Listener l = listener;
        if (gateOpen && kept.isEmpty() && l != null) {
            l.onData(data);
        } else {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data).flip();
            kept.add(copy);
        }
    }

    @Override
    public final void onWritabilityChanged(boolean writable) {
        Listener l = listener;
        if (l != null) {
            l.onWritabilityChanged(writable);
        }
    }

    @Override
    public final void onClosed(Throwable cause) {
        rawClosed = true;
        rawCloseCause = cause;
        if (!gateOpen) {
            onClosedBeforeGate(cause);
        }
        drain();
    }

    private void drain() {
        if (!gateOpen) {
            return;
        }
        Listener l = listener;
        if (l == null) {
            return;
        }
        while (!kept.isEmpty() && (autoRead || readRequested)) {
            readRequested = false;
            l.onData(kept.poll());
        }
        if (!kept.isEmpty()) {
            return;
        }
        if (rawClosed) {
            if (!closeDelivered) {
                closeDelivered = true;
                l.onClosed(rawCloseCause);
            }
            return;
        }
        raw.setAutoRead(autoRead);
        if (readRequested && !autoRead) {
            readRequested = false;
            raw.read();
        }
    }

    // --- QuicByteStream for the game.

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        execute(this::drain);
    }

    @Override
    public void setAutoRead(boolean autoRead) {
        execute(() -> {
            this.autoRead = autoRead;
            drain();
        });
    }

    @Override
    public void read() {
        execute(() -> {
            readRequested = true;
            drain();
        });
    }

    @Override
    public void write(ByteBuffer data) {
        gameWrote = true;
        raw.write(data);
    }

    /** Game data went out on this stream, so a close lets it arrive first. */
    void markGameWrote() {
        gameWrote = true;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return raw.remoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return raw.localAddress();
    }

    @Override
    public void flush() {
        raw.flush();
    }

    @Override
    public boolean isWritable() {
        return raw.isWritable();
    }

    @Override
    public boolean isOpen() {
        return raw.isOpen();
    }

    /**
     * As {@link QuicByteStream#close()}, where an endpoint that never wrote game data on the
     * stream closes the connection at once (docs/protocol.md §8): a preamble alone is nothing
     * the peer needs to read.
     */
    @Override
    public void close() {
        if (gameWrote) {
            raw.close();
        } else {
            raw.markClosing();
            Codecs.closeLater(raw.connection(), true, rs.sudoe.quicraft.core.Protocol.CLOSE_NORMAL);
        }
    }

    @Override
    public CompletableFuture<Map<String, Long>> connectionStats() {
        return raw.connectionStats();
    }

    @Override
    public void execute(Runnable task) {
        raw.execute(task);
    }

    @Override
    public boolean inEventLoop() {
        return raw.inEventLoop();
    }
}
