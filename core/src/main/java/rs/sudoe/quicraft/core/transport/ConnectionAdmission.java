// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * established connections alike and are capped globally and per IP; leases whose handshake hasn't
 * been confirmed are also counted as pending. Tracked IP entries are bounded by
 * {@code maxTrackedIps}; entries that hold open leases or current-window attempts are never
 * evicted to admit another address.
 *
 * <p>"Per IP" means per {@linkplain #limitKey limit key}: an IPv4 address (IPv4-mapped IPv6
 * included), or an IPv6 /64.
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
    private int pending;

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
     * Spends one attempt for {@code address} if the per-IP and global budgets allow it. Per-IP and
     * tracking limits are checked first; a refused attempt consumes nothing, so one address over
     * its limit can't drain the global budget.
     */
    synchronized boolean allowAttempt(InetAddress address) {
        InetAddress key = limitKey(Objects.requireNonNull(address, "address"));
        advance();
        IpState state = ips.get(key);
        if (state == null ? ips.size() >= maxTrackedIps : state.attempts >= perIpAttemptsPerSecond) {
            return false;
        }
        if (globalAttempts >= globalAttemptsPerSecond) {
            return false;
        }
        if (state == null) {
            state = new IpState();
            ips.put(key, state);
        }
        globalAttempts++;
        state.attempts++;
        return true;
    }

    /**
     * Reserves an open-connection slot for {@code address}, or returns null when denied. Does not
     * spend an attempt.
     */
    synchronized Lease tryAcquire(InetAddress address) {
        InetAddress key = limitKey(Objects.requireNonNull(address, "address"));
        advance();
        if (open >= maxOpen) {
            return null;
        }
        IpState state = ips.get(key);
        if (state == null) {
            if (ips.size() >= maxTrackedIps) {
                return null;
            }
            state = new IpState();
            ips.put(key, state);
        } else if (state.open >= maxOpenPerIp) {
            return null;
        }
        open++;
        pending++;
        state.open++;
        return new Lease(this, key, state);
    }

    /** True while unconfirmed (pending-handshake) leases are at or above 25% of {@code maxOpen}. */
    synchronized boolean handshakesBacklogged() {
        return (long) pending * 4 >= maxOpen;
    }

    /** Leases not yet {@linkplain Lease#confirm() confirmed} or closed, for tests. */
    synchronized int pendingLeases() {
        return pending;
    }

    /**
     * The key every per-IP limit counts under: an IPv4 address as is, an IPv4-mapped IPv6 address
     * as its IPv4 address, and any other IPv6 address as its /64 prefix (scope dropped).
     */
    static InetAddress limitKey(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] raw = address.getAddress();
        try {
            if (isIpv4Mapped(raw)) {
                return InetAddress.getByAddress(new byte[] {raw[12], raw[13], raw[14], raw[15]});
            }
            for (int i = 8; i < 16; i++) {
                raw[i] = 0;
            }
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
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

    /**
     * An open-connection reservation, pending until {@link #confirm()}. {@link #confirm()} and
     * {@link #close()} are idempotent; each changes the pending count at most once in total.
     */
    static final class Lease implements AutoCloseable {
        private final ConnectionAdmission owner;
        private final InetAddress address;
        private final IpState state;
        private boolean closed;
        private boolean confirmed;

        private Lease(ConnectionAdmission owner, InetAddress address, IpState state) {
            this.owner = owner;
            this.address = address;
            this.state = state;
        }

        /** The connection's handshake completed: it no longer counts as pending. */
        void confirm() {
            synchronized (owner) {
                if (closed || confirmed) {
                    return;
                }
                confirmed = true;
                owner.pending--;
            }
        }

        @Override
        public void close() {
            synchronized (owner) {
                if (closed) {
                    return;
                }
                closed = true;
                if (!confirmed) {
                    owner.pending--;
                }
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
