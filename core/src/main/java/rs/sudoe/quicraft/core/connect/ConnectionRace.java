// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * Races QUIC against TCP (docs/protocol.md §5). QUIC starts first. TCP starts after the head
 * start, or at once if QUIC fails. The first to complete wins and the loser is closed, even if
 * it completes later. The player never waits for a full QUIC timeout.
 *
 * <p>TCP is the platform's own connection type {@code T}; core never touches it beyond
 * starting and closing it.
 */
public final class ConnectionRace<T> {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** How the platform opens and closes its vanilla TCP connection. */
    public interface Tcp<T> {
        CompletableFuture<T> connect();

        void close(T connection);
    }

    /** Exactly one of {@link #quic()} and {@link #tcp()} is non-null. */
    public static final class Result<T> {
        private final QuicByteStream quic;
        private final T tcp;
        private final FallbackReason quicFailure;

        private Result(QuicByteStream quic, T tcp, FallbackReason quicFailure) {
            this.quic = quic;
            this.tcp = tcp;
            this.quicFailure = quicFailure;
        }

        public QuicByteStream quic() {
            return quic;
        }

        public T tcp() {
            return tcp;
        }

        public boolean quicWon() {
            return quic != null;
        }

        /** Why QUIC didn't win, for the failure cache and the fallback report; null if it won. */
        public FallbackReason quicFailure() {
            return quicFailure;
        }
    }

    private final Supplier<CompletableFuture<QuicByteStream>> quicAttempt;
    private final Tcp<T> tcp;
    private final CompletableFuture<Result<T>> result = new CompletableFuture<>();

    private CompletableFuture<QuicByteStream> quicFuture;
    private CompletableFuture<T> tcpFuture;
    private ScheduledFuture<?> headStartTimer;
    private FallbackReason quicFailure;
    private Throwable quicCause;
    private Throwable tcpCause;

    private ConnectionRace(Supplier<CompletableFuture<QuicByteStream>> quicAttempt, Tcp<T> tcp) {
        this.quicAttempt = quicAttempt;
        this.tcp = tcp;
    }

    public static <T> CompletableFuture<Result<T>> race(Supplier<CompletableFuture<QuicByteStream>> quic, Tcp<T> tcp,
            long headStartMillis, ScheduledExecutorService scheduler) {
        ConnectionRace<T> race = new ConnectionRace<>(quic, tcp);
        race.start(headStartMillis, scheduler);
        return race.result;
    }

    private void start(long headStartMillis, ScheduledExecutorService scheduler) {
        CompletableFuture<QuicByteStream> attempt;
        try {
            attempt = quicAttempt.get();
        } catch (Throwable t) {
            attempt = new CompletableFuture<>();
            attempt.completeExceptionally(t);
        }
        synchronized (this) {
            quicFuture = attempt;
            if (!attempt.isDone()) {
                headStartTimer = scheduler.schedule(this::startTcp, headStartMillis, TimeUnit.MILLISECONDS);
            }
        }
        attempt.whenComplete(this::onQuic);
    }

    private void onQuic(QuicByteStream stream, Throwable error) {
        boolean closeStream = false;
        boolean startTcp = false;
        synchronized (this) {
            if (error == null) {
                if (result.isDone()) {
                    closeStream = true; // TCP already won
                } else {
                    cancelTimer();
                    // A TCP attempt already running is closed in onTcp when it completes. Not
                    // cancelled: that would leave the platform's connection open.
                    result.complete(new Result<>(stream, null, null));
                }
            } else {
                quicCause = error;
                if (quicFailure == null) {
                    quicFailure = result.isDone() ? FallbackReason.TIMEOUT_OR_LOST_RACE : FallbackReason.classify(error);
                }
                cancelTimer();
                if (tcpFuture == null) {
                    startTcp = true;
                } else if (tcpCause != null) {
                    failBoth();
                }
            }
        }
        if (closeStream) {
            stream.close();
        }
        if (startTcp) {
            startTcp();
        }
    }

    private void startTcp() {
        CompletableFuture<T> attempt;
        synchronized (this) {
            if (tcpFuture != null || result.isDone()) {
                return;
            }
            try {
                attempt = tcp.connect();
            } catch (Throwable t) {
                attempt = new CompletableFuture<>();
                attempt.completeExceptionally(t);
            }
            tcpFuture = attempt;
        }
        attempt.whenComplete(this::onTcp);
    }

    private void onTcp(T connection, Throwable error) {
        boolean closeConnection = false;
        synchronized (this) {
            if (error == null) {
                if (result.isDone()) {
                    closeConnection = true; // QUIC already won
                } else {
                    if (!quicFuture.isDone()) {
                        quicFailure = FallbackReason.TIMEOUT_OR_LOST_RACE;
                        quicFuture.cancel(false); // closes a late QUIC win in onQuic
                    }
                    result.complete(new Result<>(null, connection, quicFailure));
                }
            } else {
                tcpCause = error;
                if (quicFuture.isDone() && quicCause != null) {
                    failBoth();
                }
                // Otherwise QUIC may still win.
            }
        }
        if (closeConnection) {
            try {
                tcp.close(connection);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "closing the losing TCP connection failed", t);
            }
        }
    }

    /** Both failed: report TCP's error, as vanilla would, with QUIC's attached. */
    private void failBoth() {
        Throwable cause = tcpCause;
        if (quicCause != null && quicCause != cause) {
            cause.addSuppressed(quicCause);
        }
        result.completeExceptionally(cause);
    }

    private void cancelTimer() {
        if (headStartTimer != null) {
            headStartTimer.cancel(false);
        }
    }
}
