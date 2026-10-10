// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

/**
 * One QUIC attempt's 0-RTT request (docs/protocol.md §8). {@code joinInputs} identifies what the
 * game's first flight depends on (host name and port it sends, intent, profile name and UUID,
 * protocol version), so a flight recorded under the same inputs on an earlier QUIC join can go
 * out as 0-RTT data. {@link #sent()} tells the caller whether it did.
 */
public final class EarlyFlight {
    private final String joinInputs;
    private volatile boolean sent;

    public EarlyFlight(String joinInputs) {
        if (joinInputs == null) {
            throw new NullPointerException("joinInputs");
        }
        this.joinInputs = joinInputs;
    }

    public String joinInputs() {
        return joinInputs;
    }

    /** Whether this attempt sent the recorded flight as 0-RTT data. */
    public boolean sent() {
        return sent;
    }

    /** Called by QuicClient once the flight is out; public for tests of callers. */
    public void markSent() {
        sent = true;
    }
}
