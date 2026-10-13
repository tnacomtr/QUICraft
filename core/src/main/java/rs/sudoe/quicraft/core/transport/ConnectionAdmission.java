// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded connection admission state for a listener (docs/protocol.md Retry and limits).
 *
 * <p>One listener-wide fixed one-second monotonic window governs attempts. Global and per-IP
 * attempt budgets reset together when the window rolls over. Open leases count pending and
 * established connections alike and are capped globally and per IP. Tracked IP entries are bounded
 * by {@code maxTrackedIps}; entries that hold open leases or current-window attempts are never
 * evicted to admit another address.
 *
 * <p>Not thread-safe except through its own synchronized methods; all state is guarded by this
 * instance.
 */
final class ConnectionAdmission {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final int globalAttemptsPerSecond;
    private final int perIpAttemptsPerSecond;
    private final int maxOpen;
    private final int maxOpenPerIp;
    private final int retryThreshold;
    private final int maxTrackedIps;
    private final LongSupplier clock;

    private final Map<InetAddress, IpState> ips = new HashMap<>();
    private long windowStart;
    private int globalAttempts;
    private int open;

    ConnectionAdmission(
            int globalAttemptsPerSecond,
            int perIpAttemptsPerSecond,
            int maxOpen,
            int maxOpenPerIp,
            int retryThreshold,
            int maxTrackedIps) {
        this(
                globalAttemptsPerSecond,
                perIpAttemptsPerSecond,
                maxOpen,
                maxOpenPerIp,
                retryThreshold,
                maxTrackedIps,
                System::nanoTime);
    }

    ConnectionAdmission(
            int globalAttemptsPerSecond,
            int perIpAttemptsPerSecond,
            int maxOpen,
            int maxOpenPerIp,
            int retryThreshold,
            int maxTrackedIps,
            LongSupplier clock) {
        requirePositive(globalAttemptsPerSecond, "globalAttemptsPerSecond");
        requirePositive(perIpAttemptsPerSecond, "perIpAttemptsPerSecond");
        requirePositive(maxOpen, "maxOpen");
        requirePositive(maxOpenPerIp, "maxOpenPerIp");
        requirePositive(retryThreshold, "retryThreshold");
        requirePositive(maxTrackedIps, "maxTrackedIps");
        if (retryThreshold > globalAttemptsPerSecond) {
            throw new IllegalArgumentException(
                    "retryThreshold must not exceed globalAttemptsPerSecond");
        }
        this.globalAttemptsPerSecond = globalAttemptsPerSecond;
        this.perIpAttemptsPerSecond = perIpAttemptsPerSecond;
        this.maxOpen = maxOpen;
        this.maxOpenPerIp = maxOpenPerIp;
        this.retryThreshold = retryThreshold;
        this.maxTrackedIps = maxTrackedIps;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.windowStart = clock.getAsLong();
    }

    /**
     * Spends one attempt for {@code address} if the global and per-IP budgets allow it. A refusal
     * by per-IP or tracking limits still consumes one global attempt when global budget remains.
     */
    synchronized boolean allowAttempt(InetAddress address) {
        Objects.requireNonNull(address, "address");
        advance();
        if (globalAttempts >= globalAttemptsPerSecond) {
            return false;
        }
        globalAttempts++;
        IpState state = ips.get(address);
        if (state == null) {
            if (ips.size() >= maxTrackedIps) {
                return false;
            }
            state = new IpState();
            ips.put(address, state);
        } else if (state.attempts >= perIpAttemptsPerSecond) {
            return false;
        }
        state.attempts++;
        return true;
    }

    /**
     * Reserves an open-connection slot for {@code address}, or returns null when denied. Does not
     * spend an attempt.
     */
    synchronized Lease tryAcquire(InetAddress address) {
        Objects.requireNonNull(address, "address");
        advance();
        if (open >= maxOpen) {
            return null;
        }
        IpState state = ips.get(address);
        if (state == null) {
            if (ips.size() >= maxTrackedIps) {
                return null;
            }
            state = new IpState();
            ips.put(address, state);
        } else if (state.open >= maxOpenPerIp) {
            return null;
        }
        open++;
        state.open++;
        return new Lease(this, address, state);
    }

    /** True once consumed global attempts in the current window reach the Retry threshold. */
    synchronized boolean requiresRetry() {
        advance();
        return globalAttempts >= retryThreshold;
    }

    /**
     * Rolls the window over when at least one second has elapsed. Backward or negative elapsed
     * time never resets; wrapping {@code long} subtraction handles nanoTime wrap.
     */
    private void advance() {
        long now = clock.getAsLong();
        if (now - windowStart < WINDOW_NANOS) {
            return;
        }
        windowStart = now;
        globalAttempts = 0;
        Iterator<IpState> it = ips.values().iterator();
        while (it.hasNext()) {
            IpState state = it.next();
            state.attempts = 0;
            if (state.open == 0) {
                it.remove();
            }
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Per-IP state: attempts in the current window and open leases. */
    private static final class IpState {
        int attempts;
        int open;
    }

    /** An open-connection reservation. {@link #close()} is idempotent. */
    static final class Lease implements AutoCloseable {
        private final ConnectionAdmission owner;
        private final InetAddress address;
        private final IpState state;
        private boolean closed;

        private Lease(ConnectionAdmission owner, InetAddress address, IpState state) {
            this.owner = owner;
            this.address = address;
            this.state = state;
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (closed) {
                    return;
                }
                closed = true;
                owner.advance();
                owner.open--;
                state.open--;
                if (state.open == 0 && state.attempts == 0) {
                    owner.ips.remove(address);
                }
            }
        }
    }
}
