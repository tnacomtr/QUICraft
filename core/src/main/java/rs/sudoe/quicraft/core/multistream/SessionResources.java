// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.InboundExcessPool;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.resource.ResourcePools;
import rs.sudoe.quicraft.core.time.StallClock;

/**
 * The shared resources of every N+2 session of one listener (docs/protocol.md 13.12): the node pools, the
 * outbound pool with its own pressure ranking, the inbound excess pool with its own ranking, and the server
 * profile the send drivers schedule with. Built once per listener; the client endpoint has one process-wide
 * instance, {@link #client()}.
 *
 * <p>Shed dispatch. A refused pool request may select a victim connection. The victim stays charged until it
 * is closed: {@link #dispatchShed()} drains both pools' victims, closes exactly each victim with 0x12 on its
 * own event loop and only then confirms the closure to the pool, which releases its bytes. A victim that is
 * not registered (already gone) is confirmed at once.
 *
 * <p>Grant wakeups. A send driver learns that the pool granted one of its waiting requests only when it polls.
 * A session whose driver waits registers a wakeup with {@link #awaitGrant}; every release that can grant
 * bytes ({@link #wakeWaiters()}) runs and clears the registered wakeups.
 *
 * <p>The defaults are the provisional 13.12 values and are not measured yet.
 *
 * <p>Thread-safe: the pools synchronise themselves, and the registries are concurrent maps.
 */
public final class SessionResources {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** 13.12 provisional node budget, also the profile's node budget. */
    public static final long NODE_BYTES = 256L << 20;
    /** 13.12 provisional node-wide inbound excess cap. */
    public static final long INBOUND_EXCESS_NODE_BYTES = 64L << 20;
    /** 13.12 provisional node-wide outbound cap. */
    public static final long OUTBOUND_NODE_BYTES = 256L << 20;
    /** 13.12 provisional outbound per-IP and per-account caps. */
    public static final long OUTBOUND_IP_BYTES = 32L << 20;
    public static final long OUTBOUND_ACCOUNT_BYTES = 32L << 20;
    /** 13.12 per-connection unsent-byte cap. */
    public static final long OUTBOUND_CONNECTION_BYTES = 16L << 20;
    /** 13.12 per-connection unsent-packet cap. */
    public static final int OUTBOUND_CONNECTION_PACKETS = 1024;
    /** 13.12 provisional inbound excess per-IP and per-account caps. */
    public static final long INBOUND_IP_BYTES = 16L << 20;
    public static final long INBOUND_ACCOUNT_BYTES = 16L << 20;
    /** The PRE_LOGIN share of the node budget, in percent. */
    public static final int PRE_LOGIN_PERCENT = 10;

    /**
     * The stall accounting of one resource set (13.10, 13.12). {@link #LEGACY} keeps the listed timestamped
     * ranking calls, one clock reading per call site, and the per-side {@link ExecutorStallWatch};
     * {@link #CUMULATIVE} drives every ranking from the one {@link ExecutorProgressWatch} of this resource set
     * through its no-time calls. A caller states its mode explicitly; nothing inspects or guesses it.
     */
    enum Accounting {
        /** Timestamped ranking calls and the legacy executor watch and stall pairing. */
        LEGACY,
        /** Cumulative ranking calls from this set's shared progress watch. */
        CUMULATIVE
    }

    /** A registered connection that can be closed as a shed victim. */
    interface Victim {
        /**
         * Closes the connection with 0x12 on its own event loop, then runs {@code confirm}, which tells the
         * pool the victim is closed. Runs {@code confirm} even when the connection is already closed.
         */
        void shed(Runnable confirm);
    }

    private static volatile SessionResources client;

    private final NodePools node;
    private final OutboundPool outbound;
    private final InboundExcessPool inboundExcess;
    private final PressureRanking outboundRanking;
    private final PressureRanking inboundRanking;
    private final ExecutorStallWatch executor;
    private final ExecutorStallPairing stallPairing;
    private final ExecutorProgressWatch progress;
    private final Accounting accounting;
    private final ServerProfile profile;
    private final LongSupplier clock;
    private final Map<Object, Victim> victims = new ConcurrentHashMap<Object, Victim>();
    private final Map<Object, Runnable> waiting = new ConcurrentHashMap<Object, Runnable>();

    /**
     * Builds the resources of one listener with explicit pools; tests use small caps.
     *
     * @param node the node pools, shared with the listener's pre-login accounting
     * @param outboundIp per-IP outbound cap
     * @param outboundAccount per-account outbound cap
     * @param outboundConnection per-connection outbound cap
     * @param inboundIp per-IP inbound excess cap
     * @param inboundAccount per-account inbound excess cap
     * @param profile the scheduling profile of the send drivers
     * @param clock monotonic nanosecond clock for the rankings
     */
    SessionResources(NodePools node, long outboundIp, long outboundAccount, long outboundConnection,
            long inboundIp, long inboundAccount, ServerProfile profile, LongSupplier clock) {
        this(node, outboundIp, outboundAccount, outboundConnection, inboundIp, inboundAccount, profile, clock,
                Accounting.LEGACY);
    }

    /**
     * Builds the resources of one listener in one explicit accounting mode (13.10, 13.12). LEGACY watches the
     * executor with a fresh {@link ExecutorStallWatch} on {@code clock}; CUMULATIVE owns one
     * {@link ExecutorProgressWatch} on the same clock and drives both rankings from it.
     *
     * @param accounting the mode every consumer must state; null is rejected
     */
    SessionResources(NodePools node, long outboundIp, long outboundAccount, long outboundConnection,
            long inboundIp, long inboundAccount, ServerProfile profile, LongSupplier clock, Accounting accounting) {
        this(node, outboundIp, outboundAccount, outboundConnection, inboundIp, inboundAccount, profile, clock,
                accounting, accounting == Accounting.LEGACY ? new ExecutorStallWatch(clock) : null);
    }

    /** As above, watching the game executor {@code executor} for this side's connections (13.10). */
    SessionResources(NodePools node, long outboundIp, long outboundAccount, long outboundConnection,
            long inboundIp, long inboundAccount, ServerProfile profile, LongSupplier clock,
            ExecutorStallWatch executor) {
        this(node, outboundIp, outboundAccount, outboundConnection, inboundIp, inboundAccount, profile, clock,
                Accounting.LEGACY, executor);
    }

    private SessionResources(NodePools node, long outboundIp, long outboundAccount, long outboundConnection,
            long inboundIp, long inboundAccount, ServerProfile profile, LongSupplier clock, Accounting accounting,
            ExecutorStallWatch legacyExecutor) {
        if (node == null || profile == null || clock == null || accounting == null) {
            throw new IllegalArgumentException("session resources");
        }
        this.node = node;
        this.profile = profile;
        this.clock = clock;
        this.accounting = accounting;
        if (accounting == Accounting.CUMULATIVE) {
            this.executor = null;
            this.stallPairing = null;
            this.progress = new ExecutorProgressWatch(clock);
            this.outboundRanking = new PressureRanking(progress);
            this.inboundRanking = new PressureRanking(progress);
            this.outbound = new OutboundPool(node, outboundIp, outboundAccount, outboundConnection,
                    outboundRanking.view());
            this.inboundExcess = new InboundExcessPool(node, inboundIp, inboundAccount, inboundRanking.view());
            return;
        }
        if (legacyExecutor == null) {
            throw new IllegalArgumentException("session resources");
        }
        this.executor = legacyExecutor;
        this.progress = null;
        this.outboundRanking = new PressureRanking();
        this.inboundRanking = new PressureRanking();
        this.stallPairing = new ExecutorStallPairing(inboundRanking);
        this.outbound = new OutboundPool(node, outboundIp, outboundAccount, outboundConnection,
                outboundRanking.view(clock));
        this.inboundExcess = new InboundExcessPool(node, inboundIp, inboundAccount, inboundRanking.view(clock));
    }

    /**
     * The production resources of one listener with the 13.12 defaults and cumulative stall accounting.
     * {@code shared} is reused when it is the listener's {@link NodePools}, so the N+2 sessions and pre-login
     * admission charge the same node; otherwise the sessions get node pools of their own. The pools of the
     * returned resources belong to the listener, never to one admission.
     */
    public static SessionResources production(ResourcePools shared) {
        return create(shared);
    }

    /** The cumulative resources of one side, with its one owner clock (13.10). */
    private static SessionResources create(ResourcePools shared) {
        NodePools node = shared instanceof NodePools ? (NodePools) shared
                : new NodePools(NODE_BYTES, INBOUND_EXCESS_NODE_BYTES, OUTBOUND_NODE_BYTES, PRE_LOGIN_PERCENT, false,
                        NodePools.MIN_EXCESS);
        return new SessionResources(node, OUTBOUND_IP_BYTES, OUTBOUND_ACCOUNT_BYTES, OUTBOUND_CONNECTION_BYTES,
                INBOUND_IP_BYTES, INBOUND_ACCOUNT_BYTES, defaultProfile(), System::nanoTime,
                Accounting.CUMULATIVE);
    }

    /** The client endpoint's cumulative resources, one per process, built on first use. */
    public static SessionResources client() {
        SessionResources current = client;
        if (current == null) {
            synchronized (SessionResources.class) {
                current = client;
                if (current == null) {
                    current = create(null);
                    client = current;
                }
            }
        }
        return current;
    }

    /** The default scheduling profile: the 13.12 queue caps and the default stream weights. */
    static ServerProfile defaultProfile() {
        return new ServerProfile(50_000, LogicalStream.MAX_CHUNKS, NODE_BYTES, PRE_LOGIN_PERCENT / 100.0,
                OUTBOUND_CONNECTION_PACKETS, OUTBOUND_CONNECTION_BYTES, 8, 4, 2);
    }

    public NodePools node() {
        return node;
    }

    public OutboundPool outbound() {
        return outbound;
    }

    public InboundExcessPool inboundExcess() {
        return inboundExcess;
    }

    public PressureRanking outboundRanking() {
        return outboundRanking;
    }

    /** The inbound pressure ranking the inbound excess pool picks shed victims by (13.12). */
    public PressureRanking inboundRanking() {
        return inboundRanking;
    }

    /**
     * The game executor this side's connections watch for stalls (13.10): {@link ExecutorStallWatch#SERVER} on a
     * server listener, {@link ExecutorStallWatch#CLIENT} on the client. Legacy accounting only.
     */
    public ExecutorStallWatch executorWatch() {
        requireLegacy("executorWatch");
        return executor;
    }

    /**
     * The listener-wide pairing of executor stalls into the inbound ranking (13.12): one start when the first
     * session enters a stall, one end when the last leaves it. Legacy accounting only.
     */
    ExecutorStallPairing stallPairing() {
        requireLegacy("stallPairing");
        return stallPairing;
    }

    /**
     * The one cumulative progress watch of this resource set (13.10): the shared source of both rankings' stall
     * exclusion. Cumulative accounting only.
     */
    public ExecutorProgressWatch progressWatch() {
        requireCumulative("progressWatch");
        return progress;
    }

    /** The same watch as the generic {@link StallClock} consumers take one reading per operation from. */
    public StallClock stallClock() {
        requireCumulative("stallClock");
        return progress;
    }

    /** True when this set uses the one cumulative progress watch; the production factories always do. */
    boolean cumulative() {
        return accounting == Accounting.CUMULATIVE;
    }

    private void requireLegacy(String accessor) {
        if (accounting != Accounting.LEGACY) {
            throw new IllegalStateException(accessor + " is legacy accounting only");
        }
    }

    private void requireCumulative(String accessor) {
        if (accounting != Accounting.CUMULATIVE) {
            throw new IllegalStateException(accessor + " is cumulative accounting only");
        }
    }

    public ServerProfile profile() {
        return profile;
    }

    LongSupplier clock() {
        return clock;
    }

    /** Registers a live connection as a possible shed victim. */
    void register(Object owner, Victim victim) {
        victims.put(owner, victim);
    }

    /** Forgets a closed connection: it is no longer a victim and needs no wakeup. */
    void unregister(Object owner) {
        victims.remove(owner);
        waiting.remove(owner);
    }

    /** Asks for {@code wake} to run once after the next release; replaces an earlier registration. */
    void awaitGrant(Object owner, Runnable wake) {
        waiting.put(owner, wake);
    }

    /** Runs and clears every registered grant wakeup. Call after bytes were released to either pool. */
    public void wakeWaiters() {
        if (waiting.isEmpty()) {
            return;
        }
        List<Object> owners = new ArrayList<Object>(waiting.keySet());
        for (Object owner : owners) {
            Runnable wake = waiting.remove(owner);
            if (wake == null) {
                continue;
            }
            try {
                wake.run();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "a grant wakeup failed", t);
            }
        }
    }

    /**
     * Closes every connection a pool selected as a victim since the last call, each with 0x12 and then a
     * confirmation to the pool that selected it (13.12). Safe from any thread.
     */
    public void dispatchShed() {
        for (Object owner : outbound.drainShedOwners()) {
            shed(owner, true);
        }
        for (Object owner : inboundExcess.drainShedOwners()) {
            shed(owner, false);
        }
    }

    private void shed(final Object owner, final boolean outboundVictim) {
        Runnable confirm = () -> confirm(owner, outboundVictim);
        Victim victim = victims.get(owner);
        if (victim == null) {
            confirm.run();
            return;
        }
        try {
            victim.shed(confirm);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "shedding a connection failed; confirming it closed", t);
            confirm.run();
        }
    }

    private void confirm(Object owner, boolean outboundVictim) {
        try {
            if (outboundVictim) {
                outbound.confirmClosed(owner);
            } else {
                inboundExcess.confirmClosed(owner);
            }
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "the pool no longer counts this victim as shed", e);
        }
        wakeWaiters();
    }
}
