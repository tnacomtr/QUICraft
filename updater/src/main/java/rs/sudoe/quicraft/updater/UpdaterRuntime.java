// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/**
 * The platform-neutral updater facade (docs/updater.md 12.8, 12.9, 12.11, 12.14). An adapter calls
 * {@link #start} once, forwards commands to {@link #dispatch}, and calls {@link #shutdown} on stop.
 *
 * <p><b>Failure rule.</b> Every public entry point catches {@link Throwable} and logs one fixed WARN line
 * with the exception class name. The only exception that propagates is a {@link VirtualMachineError},
 * which is rethrown as the rest of the project does. Nothing else from the updater reaches the platform.
 *
 * <p><b>Threads.</b> Checks, updates and reset run on one daemon thread named {@value #WORKER_NAME}. One
 * command runs at a time: a second one gets {@link Reply#BUSY}. {@code cancel} runs on the caller and never
 * waits. The {@code out} consumer of {@link #dispatch} may be called from the worker thread, so an adapter
 * must hop to its server thread before it messages players.
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
    /** The disclosure marker, keyed to the disclosed host set (see {@code Boot#disclose}). */
    static final String DISCLOSURE_MARKER = "disclosure-shown";
    /** The disclosure marker is read at most this many bytes; a larger file never matches. */
    static final int MARKER_READ_LIMIT = 256;
    /** Most bad-manifest digests kept for log suppression (docs/updater.md 12.8: 0..32). */
    static final int WARNED_BAD_LIMIT = 32;

    static final String BUSY_TEXT = "another updater command is already running";
    static final String STOPPING_TEXT = "updater is stopping; the command was not run";
    static final String USAGE = "usage: update | update check | update check -s | update cancel | update reset-state";
    static final String NOT_CONSOLE = "reset-state is available only from the server console";
    static final String RESET_JOURNAL = "reset refused: an update is pending restart or recovery";
    static final String RESET_BUSY = "reset refused: an update transaction or another state operation is active";
    static final String STATE_FULL_TEXT = "updater state is full; no update is offered";
    static final String STALE_TEXT = "revocation state is not fresh";

    /** The row of a check that found nothing newer (docs/updater.md section 6). */
    static final String NO_UPDATE_TEXT = "no update";
    /** The prefix of the row that offers a newer release. */
    static final String AVAILABLE_PREFIX = "update available: ";
    /** The prefix of the announcement row sent to the admins. */
    static final String ANNOUNCE_PREFIX = "a new QUICraft version is available: ";
    /** The prefix of the row that ends an update command. */
    static final String UPDATE_FINISHED_PREFIX = "update finished: ";
    /** The prefix of the disabled line. */
    static final String DISABLED_PREFIX = "updater disabled: ";
    /** The log prefix of every command output line. */
    static final String OUTPUT_LOG_PREFIX = "update command output: ";
    /** What a non-console invoker reads in place of a line it may not see. */
    static final String REDACTED_TEXT = "details are in the server console";

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

    /** Every fixed reason a runtime can be disabled with; {@link #visibleToPlayer} accepts these lines. */
    private static final List<String> DISABLED_REASONS = List.of(REASON_DISCOVERY, REASON_LIVE_READ,
            REASON_METADATA, REASON_DEV_BUILD, REASON_BUILD, REASON_PLATFORM, REASON_VERSION, REASON_TRUST,
            REASON_RECOVERY, REASON_STATE, REASON_INTERNAL, REASON_NO_HOSTS);
    /** The fixed details {@link #REASON_METADATA} is appended to when the own-jar read is refused. */
    private static final List<String> METADATA_DETAILS = List.of(OwnJarResources.MISSING,
            OwnJarResources.DUPLICATE, OwnJarResources.TOO_LARGE);
    /** The fixed prefixes of a row that carries exactly one version and nothing else. */
    private static final List<String> VERSION_ROW_PREFIXES = List.of(AVAILABLE_PREFIX, ANNOUNCE_PREFIX);

    private static final String WARN_PREFIX = "WARN:";
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(120);
    private static final long MIN_OFFSET_MS = 60_000L;
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    /** How far {@link Boot#isDevelopmentBuild} walks a cause chain before giving up. */
    private static final int MAX_CAUSE_DEPTH = 10;

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
    /**
     * What {@code update} may do, decided once by {@link InstallCapabilities} from the owner's
     * {@code update.install} setting and the platform's {@code os.name} (docs/updater.md 12.10, 12.13).
     * The transaction still downgrades to staging when the update directory cannot be synced.
     */
    private final InstallCapability updateCapability;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile MutableCancelToken checkToken;
    private volatile MutableCancelToken resetToken;

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
        this.updateCapability = InstallCapability.VERIFIED_STAGING_ONLY;
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
        this.updateCapability = b.capability;
    }

    /** Test seams. Production uses {@link #system()}. */
    static final class Seams {
        final SourceFetcher fetcher;
        final Clock clock;
        final DurabilityOps ops;
        final Function<ThreadFactory, ScheduledExecutorService> executors;
        final Function<UpdaterLog, Optional<ReleaseSetup>> setup;
        final Function<Path, StateStore> stores;
        /** The platform's {@code os.name}, injected so a test can pick the OS the updater sees. */
        final String osName;

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseSetup>> setup) {
            this(fetcher, clock, ops, executors, setup, StateStore::new);
        }

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseSetup>> setup,
                Function<Path, StateStore> stores) {
            this(fetcher, clock, ops, executors, setup, stores, systemOsName());
        }

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseSetup>> setup,
                Function<Path, StateStore> stores, String osName) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.ops = Objects.requireNonNull(ops, "ops");
            this.executors = Objects.requireNonNull(executors, "executors");
            this.setup = Objects.requireNonNull(setup, "setup");
            this.stores = Objects.requireNonNull(stores, "stores");
            this.osName = Objects.requireNonNull(osName, "osName");
        }

        /**
         * Tests that inject hosts and endpoints separately get the empty redirect map, so a host key
         * missing from it is refused every hop ({@link RedirectPolicy#denyAll()}).
         */
        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseHosts>> origins, ReleaseEndpoints endpoints) {
            this(fetcher, clock, ops, executors, fromOrigins(origins, endpoints));
        }

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseHosts>> origins, ReleaseEndpoints endpoints,
                Function<Path, StateStore> stores) {
            this(fetcher, clock, ops, executors, fromOrigins(origins, endpoints), stores);
        }

        Seams(SourceFetcher fetcher, Clock clock, DurabilityOps ops,
                Function<ThreadFactory, ScheduledExecutorService> executors,
                Function<UpdaterLog, Optional<ReleaseHosts>> origins, ReleaseEndpoints endpoints,
                Function<Path, StateStore> stores, String osName) {
            this(fetcher, clock, ops, executors, fromOrigins(origins, endpoints), stores, osName);
        }

        private static Function<UpdaterLog, Optional<ReleaseSetup>> fromOrigins(
                Function<UpdaterLog, Optional<ReleaseHosts>> origins, ReleaseEndpoints endpoints) {
            Objects.requireNonNull(origins, "origins");
            Objects.requireNonNull(endpoints, "endpoints");
            return log -> origins.apply(log)
                    .map(hosts -> new ReleaseSetup(hosts, endpoints, Map.of()));
        }

        /**
         * The platform's {@code os.name}, read once at start. A property that is absent or unreadable
         * counts as an unknown OS, which keeps verified staging only.
         */
        static String systemOsName() {
            try {
                return System.getProperty("os.name", "");
            } catch (SecurityException ignored) {
                // A property read that is not allowed counts as an unknown OS, so nothing is installed.
                return "";
            }
        }

        /**
         * The production seams. The clock ticks in whole seconds: state.json and the journal store
         * instants read from it, and their encoder accepts whole seconds only.
         */
        static Seams system() {
            return new Seams(HttpSourceFetcher.create(), Clock.tickSeconds(ZoneOffset.UTC),
                    DurabilityOps.system(),
                    Executors::newSingleThreadScheduledExecutor,
                    log -> UpdaterOrigins.resolve(System.getProperty(UpdaterOrigins.PROPERTY), log));
        }
    }

    /**
     * Starts the updater for this server. Never throws, except a {@link VirtualMachineError}; any other
     * failure, including while the seams are built, gives a disabled runtime.
     *
     * @param p the platform adapter
     * @return the runtime, enabled or disabled
     */
    public static UpdaterRuntime start(UpdaterPlatform p) {
        return start(p, () -> Seams.system());
    }

    static UpdaterRuntime start(UpdaterPlatform p, Seams seams) {
        return start(p, () -> seams);
    }

    /**
     * Starts with seams built inside the guard, so a failure while building them (for example a
     * {@link LinkageError}) gives a disabled runtime rather than reaching the platform.
     */
    static UpdaterRuntime start(UpdaterPlatform p, Supplier<Seams> seams) {
        UpdaterLog log = safeLog(p);
        Boot boot = null;
        try {
            Objects.requireNonNull(p, "platform");
            boot = new Boot(p, Objects.requireNonNull(seams.get(), "seams"), log);
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
     * Runs one command line. Never throws, except a {@link VirtualMachineError}.
     *
     * <p>The five accepted forms are exactly the docs/updater.md section 3 table, with the command
     * prefix ({@code update}) already removed: an empty array is {@code /quicraft update} and stages the
     * new jar, {@code {"check"}} and {@code {"check", "-s"}} are the two checks, {@code {"cancel"}}
     * cancels, and {@code {"reset-state"}} is the console-only reset. Anything else, including the
     * literal word {@code update}, prints {@link #USAGE}.
     *
     * <p><b>Disclosure.</b> The command may come from the console or from a player holding the updater
     * permission. With {@code console} true every line reaches {@code out} unchanged. With {@code false}
     * the sink is wrapped: every line is written to the log in full at INFO, and only the facade's own
     * fixed texts reach the invoker; anything else becomes one fixed line (see {@link PlayerOutput} and
     * {@link #visibleToPlayer}).
     *
     * @param args the words after the command prefix, for example {@code {"check", "-s"}}
     * @param console true when the command came from the server console
     * @param out receives every line of the answer; may be called from the worker thread, so an adapter
     *     must hop to its server thread before it messages players
     * @return how the command was handled
     */
    public Reply dispatch(String[] args, boolean console, Consumer<String> out) {
        Consumer<String> sink = console ? out : new PlayerOutput(out);
        try {
            return dispatchChecked(args, console, sink);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "updater command failed (" + t.getClass().getName() + ")");
            emit(sink, "updater command failed; see the server log");
            return Reply.DONE;
        }
    }

    /**
     * Stops the updater: refuses new work at once, cancels any active check or update, stops the worker and
     * waits at most five seconds for it. Never throws, except a {@link VirtualMachineError}.
     */
    public void shutdown() {
        stopping = true;
        try {
            MutableCancelToken token = checkToken;
            if (token != null) {
                token.cancel();
            }
            MutableCancelToken reset = resetToken;
            if (reset != null) {
                reset.cancel();
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
            // A task discarded by shutdownNow never runs its own cleanup: nothing runs now, so clear busy.
            busy.set(false);
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
            emit(out, DISABLED_PREFIX + disabledReason);
            return Reply.DONE;
        }
        if (stopping) {
            emit(out, STOPPING_TEXT);
            return Reply.DONE;
        }
        if (args == null) {
            emit(out, USAGE);
            return Reply.DONE;
        }
        if (args.length == 0) {
            // '/quicraft update' with no further word: download, verify and stage the new jar.
            if (txn == null) {
                emit(out, REASON_NO_HOSTS);
                return Reply.DONE;
            }
            return startWorker(out, () -> runUpdate(out));
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

    /**
     * One scheduled tick. Skipped while a command runs or after shutdown has started. The output follows
     * {@code update.check-silent}. Never throws, except a {@link VirtualMachineError}.
     */
    void scheduledTick() {
        try {
            if (stopping || !busy.compareAndSet(false, true)) {
                return;
            }
            try {
                runCheck(null, config.checkSilent(), true);
            } finally {
                busy.set(false);
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "scheduled update check failed (" + t.getClass().getName() + ")");
        }
    }

    /**
     * One check. A command ({@code scheduled} false) answers on {@code out}. A scheduled tick answers on the
     * log only: silent mode writes the section 6 rows, and non-silent mode writes every line at INFO.
     */
    private void runCheck(Consumer<String> out, boolean silent, boolean scheduled) {
        MutableCancelToken token = new MutableCancelToken();
        checkToken = token;
        try {
            CheckResult result = pipeline.run(CheckMode.REPORT, live.onDiskVersion(), token);
            if (silent) {
                silentOutput(result, out, scheduled);
            } else {
                verboseOutput(result, scheduled ? this::infoSafe : out);
                if (scheduled) {
                    announceIfNew(result, null, true);
                }
            }
        } catch (IOException | UpdateRejectedException e) {
            String text = "update check failed (" + e.getClass().getName() + ")";
            if (silent) {
                // A host failure: section 6 allows nothing to the invoker, one DEBUG line to the log.
                debugSafe(text);
            } else if (scheduled) {
                infoSafe(text);
            } else {
                emit(out, text);
            }
        } finally {
            checkToken = null;
        }
    }

    private void verboseOutput(CheckResult result, Consumer<String> sink) {
        if (result.candidate().isPresent()) {
            emit(sink, AVAILABLE_PREFIX + result.candidate().get().entry().version());
        } else {
            emit(sink, NO_UPDATE_TEXT);
        }
        if (result.stateFull()) {
            emit(sink, STATE_FULL_TEXT);
        }
        if (!result.freshRevocations()) {
            emit(sink, STALE_TEXT);
        }
        for (String d : result.diagnostics()) {
            emit(sink, d);
        }
    }

    /**
     * The silent table of docs/updater.md section 6, for {@code check -s} and silent scheduled checks. Every
     * diagnostic goes to the log at DEBUG only. The bad-manifest WARN rows go to the log only; a command's
     * {@code out} gets just the announcement INFO row.
     */
    private void silentOutput(CheckResult result, Consumer<String> out, boolean scheduled)
            throws IOException, UpdateRejectedException {
        for (String d : result.diagnostics()) {
            debugSafe(d);
        }
        if (config.logBadSignatures()) {
            reportBadManifests(result.badManifests(), out, scheduled);
        }
        announceIfNew(result, out, scheduled);
    }

    /**
     * Logs one WARN per distinct bad manifest that is not already in {@code state.warnedBad}. The list is
     * updated in the same commit, so the same document warns once across runs and restarts.
     */
    private void reportBadManifests(List<BadManifest> events, Consumer<String> out, boolean scheduled) {
        if (events.isEmpty()) {
            return;
        }
        List<BadManifest> fresh = new ArrayList<>();
        try {
            store.commit(snap -> {
                List<String> seen = new ArrayList<>(snap.state().warnedBad());
                fresh.clear();
                for (BadManifest event : events) {
                    if (!seen.contains(event.sha256())) {
                        seen.add(event.sha256());
                        fresh.add(event);
                    }
                }
                if (fresh.isEmpty()) {
                    return snap;
                }
                while (seen.size() > WARNED_BAD_LIMIT) {
                    seen.remove(0);
                }
                return new Snapshot(withWarnedBad(snap.state(), seen), snap.revoked());
            });
        } catch (IOException | UpdateRejectedException | RuntimeException e) {
            // The record is not saved, so these documents may warn again on the next check. The announcement
            // that follows must still run, so this failure does not propagate.
            debugSafe("bad-manifest record could not be saved (" + e.getClass().getName() + ")");
        }
        for (BadManifest event : fresh) {
            silentLine(Level.WARN, "release manifest from host " + event.hostKey()
                    + " was rejected", out, scheduled);
        }
    }

    /**
     * Announces a valid newer release once per version. The version is recorded as {@code lastAnnounced}
     * only after the admins were told, so a failed notice is retried by the next check.
     *
     * <p>The delivered notice is logged before that state write, so a failed write never loses the record
     * of a notice the admins already received.
     *
     * <p>Delivery is at-least-once: a crash between {@link UpdaterPlatform#notifyAdmins} and the commit repeats
     * the notice on the next start.
     */
    private void announceIfNew(CheckResult result, Consumer<String> out, boolean scheduled)
            throws IOException, UpdateRejectedException {
        if (result.candidate().isEmpty() || result.stateFull()) {
            return;
        }
        String version = result.candidate().get().entry().version().toString();
        Optional<Snapshot> current = store.load();
        if (current.isPresent() && version.equals(current.get().state().lastAnnounced())) {
            return;
        }
        String notice = ANNOUNCE_PREFIX + version;
        if (!notifyAdmins(notice)) {
            return;
        }
        // The notice is delivered, so it is logged before the state write: a failed commit still leaves the
        // delivered notice in the log, and the version is not recorded, so the next check re-announces it.
        silentLine(Level.INFO, notice, out, scheduled);
        store.commit(snap -> version.equals(snap.state().lastAnnounced())
                ? snap
                : new Snapshot(withLastAnnounced(snap.state(), version), snap.revoked()));
    }

    private static UpdaterState withLastAnnounced(UpdaterState s, String version) {
        return new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(), s.revocations(),
                s.seqByKid(), s.withdrawn(), version, s.warnedBad(), s.tornSince());
    }

    private static UpdaterState withWarnedBad(UpdaterState s, List<String> warnedBad) {
        return new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(), s.revocations(),
                s.seqByKid(), s.withdrawn(), s.lastAnnounced(), warnedBad, s.tornSince());
    }

    private void runUpdate(Consumer<String> out) {
        UpdateRequest request = new UpdateRequest(identity, updateCapability,
                line -> progress(line, out));
        TransactionResult result = txn.execute(request);
        for (String message : result.messages()) {
            emit(out, message);
            if (message.startsWith(WARN_PREFIX)) {
                warnSafe(log, stripWarn(message));
            }
        }
        emit(out, UPDATE_FINISHED_PREFIX + result.outcome());
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
        MutableCancelToken token = new MutableCancelToken();
        resetToken = token;
        try {
            if (stopping) {
                // shutdown ran between the lease and this publish, so it saw no token to cancel: the reset
                // cancels itself here instead of resetting state while the runtime is stopping.
                token.cancel();
                emit(out, STOPPING_TEXT);
                return;
            }
            if (Files.exists(updateDir.resolve(UpdateDirectory.JOURNAL), LinkOption.NOFOLLOW_LINKS)) {
                emit(out, RESET_JOURNAL);
                return;
            }
            StateReset.ResetResult result = StateReset.resetStateAndRefetch(store, pipeline, identity,
                    live.onDiskVersion(), token);
            for (String message : result.messages()) {
                emit(out, message);
            }
        } catch (IOException | UpdateRejectedException e) {
            emit(out, "reset failed (" + e.getClass().getName() + ")");
        } finally {
            resetToken = null;
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

    /**
     * One section 6 row. The log always gets it at its level. Only an INFO row of a command also goes to
     * {@code out}: WARN and DEBUG rows never do, because the invoker may be a player.
     */
    private void silentLine(Level level, String text, Consumer<String> out, boolean scheduled) {
        switch (level) {
            case DEBUG -> debugSafe(text);
            case INFO -> {
                infoSafe(text);
                if (!scheduled) {
                    emitLogged(out, text);
                }
            }
            default -> warnSafe(log, text);
        }
    }

    /**
     * Sends a row the log already holds to the command sink. A player's {@link PlayerOutput} would log it a
     * second time, so it is told the row is logged already; every other sink gets it as usual.
     */
    private static void emitLogged(Consumer<String> out, String text) {
        if (out instanceof PlayerOutput player) {
            player.preLogged(text);
        } else {
            emit(out, text);
        }
    }

    /**
     * The command sink of a non-console invoker: a player holding the updater permission. Every line is
     * written to the log in full, at INFO, with {@value #OUTPUT_LOG_PREFIX} in front, so the owner can
     * still read it. Only a line that {@link #visibleToPlayer} accepts reaches the invoker; every other
     * line is replaced by {@link #REDACTED_TEXT}, which is sent at most once per command.
     */
    private final class PlayerOutput implements Consumer<String> {
        private final Consumer<String> inner;
        private final AtomicBoolean redacted = new AtomicBoolean();

        PlayerOutput(Consumer<String> inner) {
            this.inner = inner;
        }

        @Override
        public void accept(String line) {
            infoSafe(OUTPUT_LOG_PREFIX + line);
            deliver(line);
        }

        /**
         * Delivers a line the log already recorded, without logging it a second time. The disclosure
         * filter is the same as {@link #accept}: the invoker reads it or the one redacted line.
         */
        void preLogged(String line) {
            deliver(line);
        }

        private void deliver(String line) {
            if (visibleToPlayer(line)) {
                emit(inner, line);
            } else if (redacted.compareAndSet(false, true)) {
                emit(inner, REDACTED_TEXT);
            }
        }
    }

    /**
     * The exact lines a non-console invoker may read unchanged: the facade's own fixed texts and its
     * formatted rows, and nothing else.
     *
     * <p>The accepted set: the usage text, the disabled line, the stopping and busy lines, the no-update
     * row, the two rows that carry exactly one {@link UpdateVersion} (the offer and the announcement),
     * the full-state and stale-revocation lines, the three cancel replies, the console-only line, the two
     * reset refusals, the not-configured line, and the staged or installed outcome rows.
     *
     * <p>Every accepted line is either an exact match of one of the facade's constants (or of a row built
     * from one constant plus one of a small fixed set of detail words), or one fixed prefix followed by
     * exactly one version token. No prefix match accepts arbitrary text, so a path, another jar's file
     * name, a host key or a raw exception message can never pass.
     *
     * @param line one line of the command output
     * @return true when the line may reach a non-console invoker unchanged
     */
    static boolean visibleToPlayer(String line) {
        if (line == null) {
            return false;
        }
        if (line.equals(USAGE) || line.equals(STOPPING_TEXT) || line.equals(BUSY_TEXT)
                || line.equals(NOT_CONSOLE) || line.equals(RESET_BUSY) || line.equals(RESET_JOURNAL)
                || line.equals(STATE_FULL_TEXT) || line.equals(STALE_TEXT) || line.equals(NO_UPDATE_TEXT)
                || line.equals(REASON_NO_HOSTS)
                || line.equals(UPDATE_FINISHED_PREFIX + TransactionOutcome.STAGED_ONLY)
                || line.equals(UPDATE_FINISHED_PREFIX + TransactionOutcome.INSTALLED_RESTART_PENDING)) {
            return true;
        }
        for (CancelReply reply : CancelReply.values()) {
            if (line.equals(cancelText(reply))) {
                return true;
            }
        }
        for (String reason : DISABLED_REASONS) {
            if (line.equals(DISABLED_PREFIX + reason)) {
                return true;
            }
        }
        for (String detail : METADATA_DETAILS) {
            if (line.equals(DISABLED_PREFIX + REASON_METADATA + detail)) {
                return true;
            }
        }
        for (String prefix : VERSION_ROW_PREFIXES) {
            if (line.startsWith(prefix)) {
                return isVersion(line.substring(prefix.length()));
            }
        }
        return false;
    }

    /** True when {@code text} is exactly one version token. A path, a file name or a message never is. */
    private static boolean isVersion(String text) {
        if (text.isEmpty() || text.length() > UpdateVersion.MAX_LENGTH) {
            return false;
        }
        try {
            UpdateVersion.parse(text);
            return true;
        } catch (UpdateRejectedException e) {
            return false;
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

    /** @return true when the admins were told; false when the platform failed (the caller retries later) */
    private boolean notifyAdmins(String message) {
        try {
            platform.notifyAdmins(message);
            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            warnSafe(log, "admin notice failed (" + t.getClass().getName() + ")");
            return false;
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
        InstallCapability capability = InstallCapability.VERIFIED_STAGING_ONLY;
        ReleaseHosts hosts;
        LiveJar live;
        RuntimeIdentity identity;
        StateStore store;
        CheckPipeline pipeline;
        UpdaterTransaction txn;
        ScheduledExecutorService executor;
        /** Set once the health completion is registered; it keeps the worker alive past a failed init. */
        CompletionStage<HealthCompletion> healthStage;
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
            capability = InstallCapabilities.decide(s.osName, config);
            ReleaseSetup release = s.setup.apply(log).orElse(null);
            hosts = release == null ? null : release.hosts();

            LiveJarDiscovery discovery = p.discovery();
            try {
                live = discovery.discover();
            } catch (DevelopmentBuildException e) {
                return fail(REASON_DEV_BUILD);
            } catch (IOException | UpdateRejectedException e) {
                return fail(isDevelopmentBuild(e) ? REASON_DEV_BUILD : REASON_DISCOVERY);
            } catch (RuntimeException e) {
                // A runtime failure from the adapter is an internal start failure, unless it carries the
                // development-build reason, which deserves its own line.
                return fail(isDevelopmentBuild(e) ? REASON_DEV_BUILD : REASON_INTERNAL);
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

            // Spec order: the health completion is registered straight after recovery, so a later init
            // failure cannot discard the verdict.
            if (recovery.status() == RecoveryStatus.HEALTH_PENDING) {
                RecoveryTicket ticket = recovery.ticket().orElseThrow();
                healthStage = StartupRecovery.completeHealth(ctx, ticket, p.health(), HEALTH_TIMEOUT, executor);
                healthStage.whenComplete((verdict, error) -> logHealth(verdict, error));
            }

            store = s.stores.apply(dir);
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
            if (release != null) {
                MetadataCollector collector = new MetadataCollector(s.fetcher, release.endpoints(),
                        release.redirects(), FetchTimeouts.defaults());
                pipeline = new CheckPipeline(store, collector, hosts, identity, s.clock);
                txn = new UpdaterTransaction(dir, s.ops, store, pipeline, s.fetcher, release.endpoints(),
                        release.redirects(), FetchTimeouts.defaults(), discovery, barrier, s.clock);
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

        /**
         * The first-start disclosure, once per disclosed host set. Without configured hosts nothing is sent,
         * so nothing is disclosed and no marker is written. Dev origins (localhost) are keyed differently
         * from production hosts, so a dev run never uses up a production disclosure.
         */
        private void disclose() {
            if (hosts == null) {
                return;
            }
            boolean dev = hosts.hosts().stream().allMatch(h -> "localhost".equals(h.origin().getHost()));
            String keys = hosts.hosts().stream().map(ReleaseHost::key).sorted()
                    .collect(Collectors.joining(","));
            String wanted = "disclosure-v1 " + (dev ? "dev" : "production") + " " + keys + "\n";
            Path marker = dir.resolve(DISCLOSURE_MARKER);
            try {
                recordDisclosure(marker, wanted, "update checks send this server's IP address, the running version and "
                        + "the platform to " + keys.replace(",", ", ") + "; set update.check-interval=off in "
                        + p.configFile() + " to turn checks off", log);
            } catch (IOException | RuntimeException e) {
                log.debug("disclosure marker could not be written (" + e.getClass().getName() + ")");
            }
        }

        /**
         * True when {@code t} or one of its causes is a {@link DevelopmentBuildException}, so an adapter
         * that wraps the discovery failure still gets {@link #REASON_DEV_BUILD}. The cause chain is walked
         * with a depth cap, and a self-referencing cause ends the walk.
         */
        private static boolean isDevelopmentBuild(Throwable t) {
            Throwable current = t;
            for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (current instanceof DevelopmentBuildException) {
                    return true;
                }
                Throwable next = current.getCause();
                if (next == current) {
                    return false;
                }
                current = next;
            }
            return false;
        }

        UpdaterRuntime fail(String reason) {
            warnSafe(log, DISABLED_PREFIX + reason);
            stopExecutor();
            return new UpdaterRuntime(log, reason);
        }

        /**
         * Stops the worker. A registered health completion is allowed to finish first: its queued task still
         * records the verdict, and the executor then shuts down gracefully.
         */
        void stopExecutor() {
            if (executor == null) {
                return;
            }
            if (healthStage != null) {
                ExecutorService worker = executor;
                healthStage.whenComplete((verdict, error) -> worker.shutdown());
            } else {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Writes the disclosure marker unless it is already current. A linked marker is never read or written, but the
     * disclosure is still logged. Never throws except IO and runtime errors, which the caller logs.
     */
    static void recordDisclosure(Path marker, String wanted, String text, UpdaterLog log) throws IOException {
        boolean linked = Files.isSymbolicLink(marker);
        if (!linked && markerMatches(marker, wanted)) {
            return;
        }
        log.info(text);
        if (linked) {
            log.debug("disclosure marker is a link; not followed");
            return;
        }
        Files.deleteIfExists(marker);
        writePrivate(marker, wanted);
    }

    /** Reads at most {@value #MARKER_READ_LIMIT} bytes, never following a link. */
    static boolean markerMatches(Path marker, String wanted) throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(marker, options)) {
            ByteBuffer buffer = ByteBuffer.allocate(MARKER_READ_LIMIT + 1);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill the buffer up to one byte past the limit, so an oversized marker never matches
            }
            if (buffer.position() > MARKER_READ_LIMIT) {
                return false;
            }
            return wanted.equals(new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Creates the marker owner-only (0600 where POSIX applies), so it is never world-readable. CREATE_NEW and
     * NOFOLLOW_LINKS: an existing file or a link at the path is refused, never written through.
     */
    static void writePrivate(Path marker, String text) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileAttribute<?>[] attrs = marker.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))}
                : new FileAttribute<?>[0];
        try (SeekableByteChannel channel = Files.newByteChannel(marker, options, attrs)) {
            ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }
    private static boolean looksLikeSnapshot(byte[] buildJson) {
        return DevelopmentBuildException.looksLikeDevelopmentBuild(buildJson);
    }
}
