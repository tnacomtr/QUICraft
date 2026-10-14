// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/**
 * The platform-neutral updater facade (docs/updater.md 12.8, 12.9, 12.11, 12.14). An adapter calls
 * {@link #start} once, forwards commands to {@link #dispatch}, and calls {@link #shutdown} on stop.
 * Every public entry point catches everything except a {@link VirtualMachineError} and logs one
 * fixed WARN line with the exception class name, so nothing from the updater reaches the server.
 *
 * <p>Checks, updates and reset run on one daemon thread named {@value #WORKER_NAME}. One command runs
 * at a time: a second one gets {@link Reply#BUSY}. {@code cancel} runs on the caller and never waits.
 */
public final class UpdaterRuntime {

    /** The answer of {@link #dispatch}. */
    public enum Reply {
        /** A worker task was queued; its lines follow on the worker thread. */
        STARTED,
        /** Another command is running; the fixed busy line was printed. */
        BUSY,
        /** Everything was answered on the caller thread. */
        DONE
    }

    /** The worker thread name. */
    static final String WORKER_NAME = "quicraft-updater";
    static final String DISCLOSURE_MARKER = "disclosure-shown";

    /**
     * What {@code update} may do. Only verified staging is allowed while the live swap is gated on
     * blocker 10 (docs/updater.md 12.13). Change this only together with that gate.
     */
    static final InstallCapability UPDATE_CAPABILITY = InstallCapability.VERIFIED_STAGING_ONLY;

    static final String BUSY_TEXT = "another updater command is already running";
    static final String STOPPING_TEXT = "updater is stopping; the command was not run";
    static final String USAGE = "usage: check [-s] | update | cancel | reset-state";
    static final String NOT_CONSOLE = "reset-state is available only from the server console";
    static final String RESET_JOURNAL = "reset refused: an update is pending restart or recovery";
    static final String RESET_BUSY = "reset refused: an update transaction or another state operation is active";
    static final String STATE_FULL_TEXT = "updater state is full; no update is offered";
    static final String STALE_TEXT = "revocation state is not fresh";

    static final String REASON_DISCOVERY = "live jar could not be located";
    static final String REASON_LIVE_READ = "live jar could not be read";
    static final String REASON_METADATA = "live jar metadata is unusable: ";
    static final String REASON_DEV_BUILD = "development build";
    static final String REASON_BUILD = "build metadata is invalid";
    static final String REASON_PLATFORM = "platform does not match the build metadata";
    static final String REASON_VERSION = "build version does not match the installed jar";
    static final String REASON_TRUST = "trust file is invalid";
    static final String REASON_RECOVERY = "startup recovery left the update files in place; see the WARN lines above";
    static final String REASON_STATE = "updater state could not be initialised";
    static final String REASON_INTERNAL = "unexpected failure during start";
    static final String REASON_NO_HOSTS = UpdaterOrigins.NOT_CONFIGURED;

    private static final String WARN_PREFIX = "WARN:";
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(120);
    private static final long MIN_OFFSET_MS = 60_000L;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final UpdaterLog log;
    private final String disabledReason;
    private final UpdaterPlatform platform;
    private final Path updateDir;
    private final LiveJar live;
    private final RuntimeIdentity identity;
    private final StateStore store;
    private final CheckPipeline pipeline;
    private final UpdaterTransaction txn;
    private final ScheduledExecutorService executor;
    private final UpdaterConfig config;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile MutableCancelToken checkToken;

    private UpdaterRuntime(UpdaterLog log, String disabledReason) {
        this.log = log;
        this.disabledReason = Objects.requireNonNull(disabledReason, "disabledReason");
        this.platform = null;
        this.updateDir = null;
        this.live = null;
        this.identity = null;
        this.store = null;
        this.pipeline = null;
        this.txn = null;
        this.executor = null;
        this.config = UpdaterConfig.defaults();
    }

    private UpdaterRuntime(Boot b) {
        this.log = b.log;
        this.disabledReason = null;
        this.platform = b.p;
        this.updateDir = b.dir;
        this.live = b.live;
        this.identity = b.identity;
        this.store = b.store;
        this.pipeline = b.pipeline;
        this.txn = b.txn;
        this.executor = b.executor;
        this.config = b.config;
    }

    /** Test seams. Production uses {@link #system()}. */
    static final class Seams {
        final SourceFetcher fetcher;
        final Clock clock;
        final DurabilityOps ops;
        final Function<ThreadFactory, ScheduledExecutorService> executors;
        final Function<UpdaterLog, Optional<ReleaseHosts>> origins;
        final ReleaseEndpoints endpoints;

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseHosts>> origins, ReleaseEndpoints endpoints) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.ops = Objects.requireNonNull(ops, "ops");
            this.executors = Objects.requireNonNull(executors, "executors");
            this.origins = Objects.requireNonNull(origins, "origins");
            this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        }

        static Seams system() {
            return new Seams(HttpSourceFetcher.create(), Clock.systemUTC(), DurabilityOps.system(),
                    Executors::newSingleThreadScheduledExecutor,
                    log -> UpdaterOrigins.resolve(System.getProperty(UpdaterOrigins.PROPERTY), log),
                    new DevReleaseEndpoints());
        }
    }

    /**
     * Starts the updater for this server. Never throws; a failure gives a disabled runtime.
     *
     * @param p the platform adapter
     * @return the runtime, enabled or disabled
     */
    public static UpdaterRuntime start(UpdaterPlatform p) {
        return start(p, Seams.system());
    }

    static UpdaterRuntime start(UpdaterPlatform p, Seams seams) {
        UpdaterLog log = safeLog(p);
        Boot boot = null;
        try {
            Objects.requireNonNull(p, "platform");
            boot = new Boot(p, seams, log);
            return boot.run();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            if (boot != null) {
                boot.stopExecutor();
            }
            warnSafe(log, "updater disabled: " + REASON_INTERNAL + " (" + t.getClass().getName() + ")");
            return new UpdaterRuntime(log, REASON_INTERNAL);
        }
    }

    /**
     * Runs one command line.
     *
     * @param args the words after the command prefix, for example {@code {"check", "-s"}}
     * @param console true when the command came from the server console
     * @param out receives every line of the answer; may be called from the worker thread
     * @return how the command was handled
     */
    public Reply dispatch(String[] args, boolean console, Consumer<String> out) {
        try {
            return dispatchChecked(args, console, out);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "updater command failed (" + t.getClass().getName() + ")");
            emit(out, "updater command failed; see the server log");
            return Reply.DONE;
        }
    }

    /**
     * Cancels any active check or update, stops the worker and waits at most five seconds for it.
     * Never throws.
     */
    public void shutdown() {
        try {
            MutableCancelToken token = checkToken;
            if (token != null) {
                token.cancel();
            }
            if (txn != null) {
                txn.cancelActive();
            }
            if (executor != null) {
                executor.shutdownNow();
                if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    warnSafe(log, "updater worker did not stop within five seconds");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnSafe(log, "updater shutdown was interrupted");
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "updater shutdown failed (" + t.getClass().getName() + ")");
        }
    }

    /**
     * Test seam: waits until no command or scheduled tick is running.
     *
     * @return false when the timeout passed first
     */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (busy.get()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    // ---- commands -----------------------------------------------------------------------------

    private Reply dispatchChecked(String[] args, boolean console, Consumer<String> out) {
        if (disabledReason != null) {
            emit(out, "updater disabled: " + disabledReason);
            return Reply.DONE;
        }
        if (args == null || args.length == 0) {
            emit(out, USAGE);
            return Reply.DONE;
        }
        switch (args[0]) {
            case "check": {
                boolean silent = args.length == 2 && "-s".equals(args[1]);
                if (args.length > 2 || (args.length == 2 && !silent)) {
                    emit(out, USAGE);
                    return Reply.DONE;
                }
                if (pipeline == null) {
                    emit(out, REASON_NO_HOSTS);
                    return Reply.DONE;
                }
                return startWorker(out, () -> runCheck(out, silent, false));
            }
            case "update": {
                if (args.length != 1) {
                    emit(out, USAGE);
                    return Reply.DONE;
                }
                if (txn == null) {
                    emit(out, REASON_NO_HOSTS);
                    return Reply.DONE;
                }
                return startWorker(out, () -> runUpdate(out));
            }
            case "cancel": {
                if (args.length != 1) {
                    emit(out, USAGE);
                    return Reply.DONE;
                }
                emit(out, cancelText(txn == null ? CancelReply.NO_ACTIVE_TRANSACTION : txn.cancelActive()));
                return Reply.DONE;
            }
            case "reset-state": {
                if (args.length != 1) {
                    emit(out, USAGE);
                    return Reply.DONE;
                }
                if (!console) {
                    emit(out, NOT_CONSOLE);
                    return Reply.DONE;
                }
                if (pipeline == null) {
                    emit(out, REASON_NO_HOSTS);
                    return Reply.DONE;
                }
                return startWorker(out, () -> runReset(out));
            }
            default:
                emit(out, USAGE);
                return Reply.DONE;
        }
    }

    private Reply startWorker(Consumer<String> out, Runnable task) {
        if (!busy.compareAndSet(false, true)) {
            emit(out, BUSY_TEXT);
            return Reply.BUSY;
        }
        try {
            executor.execute(() -> runBusy(task, out));
        } catch (RejectedExecutionException e) {
            busy.set(false);
            emit(out, STOPPING_TEXT);
            return Reply.DONE;
        }
        return Reply.STARTED;
    }

    private void runBusy(Runnable task, Consumer<String> out) {
        try {
            task.run();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "updater command failed (" + t.getClass().getName() + ")");
            emit(out, "updater command failed; see the server log");
        } finally {
            busy.set(false);
        }
    }

    /** One scheduled tick. Skipped when a command is running. Never throws. */
    void scheduledTick() {
        try {
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            try {
                runCheck(null, true, true);
            } finally {
                busy.set(false);
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "scheduled update check failed (" + t.getClass().getName() + ")");
        }
    }

    private void runCheck(Consumer<String> out, boolean silent, boolean scheduled) {
        MutableCancelToken token = new MutableCancelToken();
        checkToken = token;
        try {
            CheckResult result = pipeline.run(CheckMode.REPORT, live.onDiskVersion(), token);
            if (silent) {
                silentOutput(result, out, scheduled);
            } else {
                verboseOutput(result, out);
            }
        } catch (IOException | UpdateRejectedException e) {
            String text = "update check failed (" + e.getClass().getName() + ")";
            if (silent) {
                silentLine(Level.WARN, text, out, scheduled);
            } else {
                emit(out, text);
            }
        } finally {
            checkToken = null;
        }
    }

    private void verboseOutput(CheckResult result, Consumer<String> out) {
        if (result.candidate().isPresent()) {
            emit(out, "update available: " + result.candidate().get().entry().version());
        } else {
            emit(out, "no update");
        }
        if (result.stateFull()) {
            emit(out, STATE_FULL_TEXT);
        }
        if (!result.freshRevocations()) {
            emit(out, STALE_TEXT);
        }
        for (String d : result.diagnostics()) {
            emit(out, d);
        }
    }

    /** The silent table of docs/updater.md section 6, for scheduled checks and {@code check -s}. */
    private void silentOutput(CheckResult result, Consumer<String> out, boolean scheduled)
            throws IOException, UpdateRejectedException {
        Set<String> badSeen = new LinkedHashSet<>();
        for (String d : result.diagnostics()) {
            if (d.contains("rejected")) {
                if (config.logBadSignatures() && badSeen.add(d)) {
                    silentLine(Level.WARN, d, out, scheduled);
                }
            } else {
                silentLine(Level.DEBUG, d, out, scheduled);
            }
        }
        if (result.candidate().isPresent() && !result.stateFull()) {
            String version = result.candidate().get().entry().version().toString();
            if (announce(version)) {
                String notice = "a new QUICraft version is available: " + version;
                notifyAdmins(notice);
                silentLine(Level.INFO, notice, out, scheduled);
            }
        }
    }

    /**
     * Records {@code version} as announced, once. Returns true only for the first announcement; the
     * record is the persisted {@code lastAnnounced} in {@code state.json}.
     */
    private boolean announce(String version) throws IOException, UpdateRejectedException {
        boolean[] first = {false};
        store.commit(snap -> {
            if (version.equals(snap.state().lastAnnounced())) {
                return snap;
            }
            first[0] = true;
            return new Snapshot(withLastAnnounced(snap.state(), version), snap.revoked());
        });
        return first[0];
    }

    private static UpdaterState withLastAnnounced(UpdaterState s, String version) {
        return new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(), s.revocations(),
                s.seqByKid(), s.withdrawn(), version, s.warnedBad(), s.tornSince());
    }

    private void runUpdate(Consumer<String> out) {
        UpdateRequest request = new UpdateRequest(identity, UPDATE_CAPABILITY,
                line -> progress(line, out));
        TransactionResult result = txn.execute(request);
        for (String message : result.messages()) {
            emit(out, message);
            if (message.startsWith(WARN_PREFIX)) {
                warnSafe(log, stripWarn(message));
            }
        }
        emit(out, "update finished: " + result.outcome());
    }

    /** The transaction's progress sink: WARN in the log and a copy to the command output. Never throws. */
    private void progress(String line, Consumer<String> out) {
        warnSafe(log, stripWarn(line));
        emit(out, line);
    }

    private void runReset(Consumer<String> out) {
        TransactionLock.TxnLease lease;
        try {
            lease = TransactionLock.tryAcquire(updateDir);
        } catch (UpdateRejectedException e) {
            emit(out, RESET_BUSY);
            return;
        } catch (IOException e) {
            emit(out, "reset failed (" + e.getClass().getName() + ")");
            return;
        }
        try {
            if (Files.exists(updateDir.resolve(UpdateDirectory.JOURNAL), LinkOption.NOFOLLOW_LINKS)) {
                emit(out, RESET_JOURNAL);
                return;
            }
            StateReset.ResetResult result = StateReset.resetStateAndRefetch(store, pipeline, identity,
                    live.onDiskVersion(), new MutableCancelToken());
            for (String message : result.messages()) {
                emit(out, message);
            }
        } catch (IOException | UpdateRejectedException e) {
            emit(out, "reset failed (" + e.getClass().getName() + ")");
        } finally {
            closeQuietly(lease, out);
        }
    }

    private void closeQuietly(TransactionLock.TxnLease lease, Consumer<String> out) {
        try {
            lease.close();
        } catch (IOException e) {
            emit(out, "reset lock could not be released (" + e.getClass().getName() + ")");
        }
    }

    private static String cancelText(CancelReply reply) {
        switch (reply) {
            case REQUESTED:
                return "cancel requested; the update stops at its next step";
            case INSTALLED_RESTART_PENDING:
                return "the update is already installed; restart the server to finish it; cancel does not roll back";
            default:
                return "no update is running; nothing was deleted";
        }
    }

    // ---- output helpers -----------------------------------------------------------------------

    private enum Level {
        DEBUG, INFO, WARN
    }

    private void silentLine(Level level, String text, Consumer<String> out, boolean scheduled) {
        if (!scheduled) {
            emit(out, text);
            return;
        }
        switch (level) {
            case DEBUG -> debugSafe(text);
            case INFO -> infoSafe(text);
            default -> warnSafe(log, text);
        }
    }

    private void debugSafe(String text) {
        try {
            log.debug(text);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
            // The log sink is the adapter's; a failing sink must not reach the server.
        }
    }

    private void infoSafe(String text) {
        try {
            log.info(text);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
            // See debugSafe.
        }
    }

    private void notifyAdmins(String message) {
        try {
            platform.notifyAdmins(message);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "admin notice failed (" + t.getClass().getName() + ")");
        }
    }

    private static void emit(Consumer<String> out, String text) {
        if (out == null) {
            return;
        }
        try {
            out.accept(text);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
            // A failing command sink must not stop the transaction or the worker.
        }
    }

    private static String stripWarn(String message) {
        return message.startsWith(WARN_PREFIX) ? message.substring(WARN_PREFIX.length()).trim() : message;
    }

    static void warnSafe(UpdaterLog log, String text) {
        try {
            log.warn(text);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable ignored) {
            // See debugSafe.
        }
    }

    /** Wraps the platform sink so that no log call can throw. A failing {@code log()} gives a no-op sink. */
    private static UpdaterLog safeLog(UpdaterPlatform p) {
        UpdaterLog inner;
        try {
            inner = Objects.requireNonNull(p.log(), "log");
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            inner = NO_LOG;
        }
        return new SafeLog(inner);
    }

    private static final UpdaterLog NO_LOG = new UpdaterLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };

    private static final class SafeLog implements UpdaterLog {
        private final UpdaterLog inner;

        SafeLog(UpdaterLog inner) {
            this.inner = inner;
        }

        @Override
        public void debug(String message) {
            try {
                inner.debug(message);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable ignored) {
                // A failing sink must not reach the server.
            }
        }

        @Override
        public void info(String message) {
            try {
                inner.info(message);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable ignored) {
                // See debug.
            }
        }

        @Override
        public void warn(String message) {
            try {
                inner.warn(message);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable ignored) {
                // See debug.
            }
        }
    }

    private static Thread workerThread(Runnable r) {
        Thread t = new Thread(r, WORKER_NAME);
        t.setDaemon(true);
        return t;
    }

    // ---- init ---------------------------------------------------------------------------------

    /** The init sequence (docs/updater.md 12.8, 12.11, 12.14). Runs on the start thread only. */
    private static final class Boot {
        final UpdaterPlatform p;
        final Seams s;
        final UpdaterLog log;
        Path dir;
        UpdaterConfig config = UpdaterConfig.defaults();
        ReleaseHosts hosts;
        LiveJar live;
        RuntimeIdentity identity;
        StateStore store;
        CheckPipeline pipeline;
        UpdaterTransaction txn;
        ScheduledExecutorService executor;
        final AtomicBoolean ready = new AtomicBoolean();

        Boot(UpdaterPlatform p, Seams s, UpdaterLog log) {
            this.p = p;
            this.s = s;
            this.log = log;
        }

        UpdaterRuntime run() {
            // Read before anything in this start creates the update directory.
            boolean dirExisted = p.updateDirExistedBeforeStart();
            dir = Objects.requireNonNull(p.updateDir(), "updateDir");
            config = UpdaterConfig.load(p.configFile(), log::warn);
            hosts = s.origins.apply(log).orElse(null);

            LiveJarDiscovery discovery = p.discovery();
            try {
                live = discovery.discover();
            } catch (IOException | UpdateRejectedException e) {
                return fail(REASON_DISCOVERY);
            }

            OwnJarResources.Resources resources;
            try {
                resources = OwnJarResources.read(live.realPath());
            } catch (IOException e) {
                return fail(REASON_LIVE_READ);
            } catch (UpdateRejectedException e) {
                return fail(REASON_METADATA + e.getMessage());
            }

            BuildInfo build;
            try {
                build = DocumentParser.parseBuild(resources.buildJson());
            } catch (UpdateRejectedException e) {
                return fail(looksLikeSnapshot(resources.buildJson()) ? REASON_DEV_BUILD : REASON_BUILD);
            }
            if (!p.platform().equals(build.platform())) {
                return fail(REASON_PLATFORM);
            }
            if (build.version().compareTo(live.onDiskVersion()) != 0) {
                return fail(REASON_VERSION);
            }

            UpdateDocuments.Trust trust;
            try {
                trust = DocumentParser.parseTrust(resources.trustJson());
            } catch (UpdateRejectedException e) {
                return fail(REASON_TRUST);
            }
            identity = new RuntimeIdentity(p.platform(), p.gameTarget(), build.version(), trust,
                    live.sha512());

            executor = s.executors.apply(UpdaterRuntime::workerThread);
            RecoveryContext ctx = new RecoveryContext(dir, s.ops, discovery, identity);
            RecoveryResult recovery = StartupRecovery.run(ctx);
            for (String message : recovery.messages()) {
                log.warn(message);
            }
            if (recovery.status() == RecoveryStatus.DISABLED
                    || recovery.status() == RecoveryStatus.RECOVERY_REQUIRED) {
                return fail(REASON_RECOVERY);
            }

            store = new StateStore(dir);
            try {
                UpdateDocuments.Trust runningTrust = identity.runningTrust();
                StateRecords.StateDelta initial = TrustTransition.apply(
                        TrustTransition.classify(null, runningTrust), runningTrust, null);
                store.initialiseIfAbsent(initial, !dirExisted);
            } catch (IOException | UpdateRejectedException e) {
                return fail(REASON_STATE);
            }

            InitBarrier barrier = ready::get;
            ready.set(true);
            if (hosts != null) {
                MetadataCollector collector = new MetadataCollector(s.fetcher, s.endpoints, Map.of(),
                        FetchTimeouts.defaults());
                pipeline = new CheckPipeline(store, collector, hosts, identity, s.clock);
                txn = new UpdaterTransaction(dir, s.ops, store, pipeline, s.fetcher, s.endpoints, Map.of(),
                        FetchTimeouts.defaults(), discovery, barrier, s.clock);
            }

            if (recovery.status() == RecoveryStatus.HEALTH_PENDING) {
                RecoveryTicket ticket = recovery.ticket().orElseThrow();
                CompletionStage<HealthCompletion> stage = StartupRecovery.completeHealth(ctx, ticket,
                        p.health(), HEALTH_TIMEOUT, executor);
                stage.whenComplete((verdict, error) -> logHealth(verdict, error));
            }

            disclose();

            UpdaterRuntime runtime = new UpdaterRuntime(this);
            Duration period = config.checkInterval().period();
            if (period != null && hosts != null) {
                long periodMs = period.toMillis();
                long offsetMs = MIN_OFFSET_MS
                        + ThreadLocalRandom.current().nextLong(periodMs - MIN_OFFSET_MS + 1);
                executor.scheduleAtFixedRate(runtime::scheduledTick, offsetMs, periodMs, TimeUnit.MILLISECONDS);
            }
            return runtime;
        }

        private void logHealth(HealthCompletion verdict, Throwable error) {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                warnSafe(log, "health result could not be recorded (" + cause.getClass().getName()
                        + "); the next start re-checks");
            } else if (verdict == HealthCompletion.HEALTHY) {
                log.info("new version passed the health check; update journal cleared");
            } else if (verdict == HealthCompletion.JOURNAL_CHANGED) {
                log.info("update journal changed during the health check; nothing was touched");
            } else {
                warnSafe(log, "new version did not pass the health check (" + verdict
                        + "); the update journal is kept");
            }
        }

        private void disclose() {
            Path marker = dir.resolve(DISCLOSURE_MARKER);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            String hostText = hosts == null ? "no hosts configured"
                    : hosts.hosts().stream().map(ReleaseHost::key).collect(Collectors.joining(", "));
            log.info("update checks send this server's IP address, the running version and the platform to "
                    + hostText + "; set update.check-interval=off in " + p.configFile()
                    + " to turn checks off");
            try {
                Files.createFile(marker);
            } catch (IOException | RuntimeException e) {
                log.debug("disclosure marker could not be created (" + e.getClass().getName() + ")");
            }
        }

        UpdaterRuntime fail(String reason) {
            warnSafe(log, "updater disabled: " + reason);
            stopExecutor();
            return new UpdaterRuntime(log, reason);
        }

        void stopExecutor() {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    private static boolean looksLikeSnapshot(byte[] buildJson) {
        return new String(buildJson, StandardCharsets.UTF_8).contains("SNAPSHOT");
    }
}
