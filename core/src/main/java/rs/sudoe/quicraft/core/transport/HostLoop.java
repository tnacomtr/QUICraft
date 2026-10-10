// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.util.concurrent.TimeUnit;

/**
 * The platform's own event loop thread, for running QUIC on it (docs/protocol.md §11, "Hosted
 * transport"). With QUIC, the game pipeline and (on a proxy) the backend connection all on one
 * thread, a packet crosses no thread on its way through, as with vanilla TCP.
 *
 * <p>Implemented by the bridge module over the game's Netty {@code EventLoop}. Core never sees
 * the game's Netty types.
 */
public interface HostLoop {
    boolean inEventLoop(Thread thread);

    void execute(Runnable task);

    /** Runs {@code task} on this loop after the delay. */
    Cancellable schedule(Runnable task, long delay, TimeUnit unit);

    interface Cancellable {
        /** Returns false if the task already ran or was cancelled. */
        boolean cancel();
    }
}
