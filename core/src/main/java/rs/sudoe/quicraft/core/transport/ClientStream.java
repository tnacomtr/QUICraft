// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Faults;

/**
 * The client's side of stream 0 (docs/protocol.md §8). Keeps the fresh early token from the
 * server's preamble. With a flight sent as 0-RTT data, the game's own first flight must repeat it
 * byte for byte and is dropped, and the server's early answer reaches the game only after that,
 * as it would over TCP. Without one, the game's first flight is recorded for the next join.
 */
public final class ClientStream extends PreambleStream {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private final EarlyCache cache;
    private final List<Object> server;
    private final String joinInputs;
    private final byte[] sentEarly;
    private final Object writeLock = new Object();
    /** Guarded by writeLock. */
    private int matched;
    /** Guarded by writeLock; null when not recording. */
    private FlightRecorder recorder;
    private volatile boolean mismatched;
    private volatile Runnable onMismatch;

    ClientStream(NettyQuicByteStream raw, EarlyCache cache, List<Object> server, String joinInputs, byte[] sentEarly) {
        super(raw);
        this.cache = cache;
        this.server = server;
        this.joinInputs = joinInputs;
        this.sentEarly = sentEarly;
        if (sentEarly == null && joinInputs != null) {
            recorder = new FlightRecorder();
        }
        if (sentEarly != null) {
            markGameWrote(); // the server may act on the 0-RTT flight
        }
    }

    /** Whether the first flight went out as 0-RTT data. */
    public boolean sentEarlyData() {
        return sentEarly != null;
    }

    /**
     * Runs if the game's first flight differs from the one sent as 0-RTT data (docs/protocol.md
     * §8, "Mismatch"), after the connection is closed: the platform starts the join again over
     * TCP. Any thread.
     */
    public void onFirstFlightMismatch(Runnable handler) {
        this.onMismatch = handler;
        if (mismatched) {
            handler.run();
        }
    }

    @Override
    void onPreamble(byte[] token) {
        if (token != null) {
            cache.putToken(server, token);
        }
        if (sentEarly == null) {
            openGate();
        }
    }

    @Override
    public void write(ByteBuffer data) {
        boolean open = false;
        boolean mismatchNow = false;
        synchronized (writeLock) {
            if (mismatched) {
                return;
            }
            if (sentEarly != null && matched < sentEarly.length) {
                int n = Math.min(data.remaining(), sentEarly.length - matched);
                boolean same;
                try {
                    Faults.check(Faults.EARLY_COMPARE);
                    same = equal(data, sentEarly, matched, n);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "comparing the first flight failed; treating it as a mismatch", t);
                    same = false;
                }
                if (same) {
                    data.position(data.position() + n);
                    matched += n;
                    open = matched == sentEarly.length;
                } else {
                    mismatched = true;
                    mismatchNow = true;
                }
            }
            if (!mismatchNow && recorder != null) {
                try {
                    Faults.check(Faults.EARLY_RECORD);
                    int state = recorder.add(data);
                    if (state == FlightRecorder.DONE) {
                        cache.putFlight(server, joinInputs, recorder.flight());
                    }
                    if (state != FlightRecorder.MORE) {
                        recorder = null;
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "recording the first flight failed; no 0-RTT next time", t);
                    recorder = null;
                }
            }
        }
        if (mismatchNow) {
            mismatch();
            return;
        }
        if (open) {
            openGate();
        }
        if (data.hasRemaining()) {
            super.write(data);
        }
    }

    private void mismatch() {
        LOG.info("QUICraft: the game's first packets differ from the 0-RTT data sent ahead of them; "
                + "closing QUIC and joining again over TCP");
        cache.forget(server);
        discardKeptAndOpenGate(); // the server's answer was to the other packets: never shown
        // At once, not after a FIN: the server must stop the login it started.
        Codecs.closeLater(raw.connection(), true, rs.sudoe.quicraft.core.Protocol.CLOSE_NORMAL);
        Runnable handler = onMismatch;
        if (handler != null) {
            handler.run();
        }
    }

    private static boolean equal(ByteBuffer data, byte[] expected, int offset, int n) {
        int p = data.position();
        for (int i = 0; i < n; i++) {
            if (data.get(p + i) != expected[offset + i]) {
                return false;
            }
        }
        return true;
    }
}
