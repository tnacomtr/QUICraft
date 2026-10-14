// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one update transaction (docs/updater.md 12.9, 12.10). The only code that replaces the live
 * jar. One transaction at a time per instance; {@link #cancelActive} never takes the transaction lock.
 */
final class UpdaterTransaction {

    /** Where the active transaction is. {@code RUNNING} is the only phase cancel can leave. */
    enum Phase {
        RUNNING,
        CANCEL_REQUESTED,
        SWAPPING
    }

    /** The in-process state of one active transaction: its cancel token and its phase. */
    static final class Active {
        final MutableCancelToken token = new MutableCancelToken();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    }

    private final Path updateDir;
    private final DurabilityOps ops;
    private final StateStore store;
    private final CheckPipeline pipeline;
    private final SourceFetcher fetcher;
    private final ReleaseEndpoints endpoints;
    private final Map<String, RedirectPolicy> redirects;
    private final FetchTimeouts timeouts;
    private final LiveJarDiscovery discovery;
    private final InitBarrier init;
    private final Clock clock;
    private final AtomicReference<Active> active = new AtomicReference<>();

    UpdaterTransaction(Path updateDir, DurabilityOps ops, StateStore store, CheckPipeline pipeline,
            SourceFetcher fetcher, ReleaseEndpoints endpoints, Map<String, RedirectPolicy> redirects,
            FetchTimeouts timeouts, LiveJarDiscovery discovery, InitBarrier init, Clock clock) {
        this.updateDir = Objects.requireNonNull(updateDir, "updateDir");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.store = Objects.requireNonNull(store, "store");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.redirects = Map.copyOf(Objects.requireNonNull(redirects, "redirects"));
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.init = Objects.requireNonNull(init, "init");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Claims the single in-process transaction slot.
     *
     * @return the active record, or {@code null} when another transaction is already active
     */
    Active tryEnter() {
        Active fresh = new Active();
        return active.compareAndSet(null, fresh) ? fresh : null;
    }

    /** Releases the slot claimed by {@link #tryEnter}; a no-op when the slot holds another record. */
    void leave(Active mine) {
        active.compareAndSet(mine, null);
    }

    /**
     * Asks the active transaction to stop before the point of no return. Never takes the transaction
     * lock and never deletes anything itself.
     */
    CancelReply cancelActive() {
        Active a = active.get();
        if (a == null) {
            return CancelReply.NO_ACTIVE_TRANSACTION;
        }
        if (a.phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED)) {
            a.token.cancel();
            return CancelReply.REQUESTED;
        }
        return a.phase.get() == Phase.CANCEL_REQUESTED
                ? CancelReply.REQUESTED
                : CancelReply.INSTALLED_RESTART_PENDING;
    }

    /**
     * Runs the transaction. Steps are added in later commits; for now a second concurrent call gets
     * NOT_ADMITTED and the first one stops at the skeleton.
     */
    TransactionResult execute(UpdateRequest req) {
        Objects.requireNonNull(req, "req");
        Active mine = tryEnter();
        if (mine == null) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED,
                    "an update transaction is already active");
        }
        try {
            throw new UnsupportedOperationException("transaction steps are not implemented yet");
        } finally {
            leave(mine);
        }
    }
}
