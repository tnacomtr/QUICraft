// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.RevokedKey;
import rs.sudoe.quicraft.updater.UpdaterTransaction.SameStore;

/**
 * The crash and recovery matrix (docs/updater.md 12.8 to 12.11). Each durability boundary of the update
 * transaction is a crash point: the counting {@link Boundaries} copies the whole rig tree at the chosen
 * boundary and throws {@link SimulatedCrash}. The copy is restored over the same paths (the journal stores
 * absolute paths, so a restart sees the same paths), and startup recovery and the next update command then
 * run on fresh objects only.
 */
class UpdateCrashMatrixTest {

    private static final byte[] OLD = "the running quicraft jar".getBytes(StandardCharsets.UTF_8);
    private static final String RUNNING = TransactionFixtures.RUNNING_VERSION;
    private static final String NEW = TransactionFixtures.NEW_VERSION;
    private static final TransactionProgress NO_OP = line -> { };
    private static final long WAIT_SECONDS = 10;
    private static final Executor DIRECT = Runnable::run;
    private static final String HEX_C = "c".repeat(64);
    private static final String PUB_X = Hex.sha256("matrix-revocation".getBytes(StandardCharsets.UTF_8));
    private static final String KID_X = kidOf(PUB_X);
    /** A key and a blocked artifact that the seeded state already restricts before any transaction runs. */
    private static final String PUB_OLD = Hex.sha256("matrix-seeded-revocation".getBytes(StandardCharsets.UTF_8));
    private static final String KID_OLD = kidOf(PUB_OLD);
    private static final String BLOCKED = "b".repeat(128);
    private static final String WITHDRAWN = "a".repeat(128);

    private static String kidOf(String pub) {
        try {
            return Hex.kid(pub);
        } catch (UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }

    @TempDir
    Path tmp;

    /** The injected crash. A VirtualMachineError, so the transaction rethrows it instead of cleaning up. */
    static final class SimulatedCrash extends VirtualMachineError {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String where) {
            super(where);
        }
    }

    @FunctionalInterface
    interface IoCall<T> {
        T call() throws IOException;
    }

    /**
     * Counts every durability boundary once armed. At boundary {@code crashAt} it either throws before the
     * operation ({@code crashAfter} false) or after it. The whole tree is copied at the crash, before the throw.
     * Once crashed, every later boundary throws the same crash.
     */
    static final class Boundaries {
        private final Path root;
        private final Path snapshotDir;
        boolean armed;
        int count;
        int crashAt;
        boolean crashAfter;
        boolean crashed;
        Path snapshot;

        Boundaries(Path root, Path snapshotDir) {
            this.root = root;
            this.snapshotDir = snapshotDir;
        }

        <T> T step(IoCall<T> op) throws IOException {
            if (!armed) {
                return op.call();
            }
            if (crashed) {
                throw new SimulatedCrash("boundary reached after the crash");
            }
            int n = ++count;
            if (n == crashAt && !crashAfter) {
                crash();
            }
            T out = op.call();
            if (n == crashAt && crashAfter) {
                crash();
            }
            return out;
        }

        private void crash() {
            crashed = true;
            try {
                copyTree(root, snapshotDir);
                snapshot = snapshotDir;
            } catch (IOException e) {
                throw new IllegalStateException("could not copy the crash snapshot", e);
            }
            throw new SimulatedCrash("injected crash at boundary " + count);
        }
    }

    /** A rig tree: the update directory, the live jar, and the in-memory release sources. */
    private static final class Rig {
        Path root;
        Path upd;
        Path live;
        byte[] jar;
        PipelineFixtures.ScriptedFetcher metadata;
        TransactionFixtures.ArtifactFetcher artifact;
    }

    /** Passes every call through, and lets a test cancel the running transaction at a chosen fetch or download. */
    static final class Hooked implements SourceFetcher {
        private final SourceFetcher inner;
        Runnable beforeFetch = () -> { };
        Runnable afterDownload = () -> { };

        Hooked(SourceFetcher inner) {
            this.inner = inner;
        }

        @Override
        public FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException {
            beforeFetch.run();
            return inner.fetch(request, cancel);
        }

        @Override
        public long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
                throws FetchException {
            long n = inner.download(request, destination, cancel);
            afterDownload.run();
            return n;
        }
    }

    // ---- tree helpers --------------------------------------------------------------------------

    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.copy(dir, to.resolve(from.relativize(dir)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---- rig, identity, discovery, transaction ----------------------------------------------------

    /**
     * The seeded state: non-empty revoked keys, a blocked artifact, a withdrawn entry and a per-key floor, so
     * monotonicity is checked against real restrictions rather than empty sets. The withdrawn entry and the
     * floor belong to the unrevoked release kid and the withdrawn version is above the running and new versions:
     * entries of revoked kids and versions not above max(running, on-disk) are pruned by design (docs/updater.md
     * 12.8, "Growth policy"), so seeding them would test the pruning, not the monotonicity.
     */
    private static StateDelta seededDelta() throws Exception {
        UpdaterState s = PipelineFixtures.seedState();
        Map<String, KidSeq> seqs = new HashMap<>(s.seqByKid());
        seqs.put(TestKeys.KID_1, new KidSeq(2, HEX_C, Instant.parse(SignedFixtures.TS1)));
        List<WithdrawnEntry> withdrawn = new ArrayList<>(s.withdrawn());
        withdrawn.add(new WithdrawnEntry(WITHDRAWN, UpdateVersion.parse("9.9.9"), TestKeys.KID_1));
        UpdaterState seeded = new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(),
                s.revocations(), seqs, withdrawn, s.lastAnnounced(), s.warnedBad(), s.tornSince());
        RevokedState revoked = new RevokedState(List.of(new RevokedKey(KID_OLD, PUB_OLD)), List.of(BLOCKED));
        return new StateDelta(seeded, revoked);
    }

    /**
     * The revocations the served metadata carries: the seeded key plus {@link #PUB_X}. PUB_X is new to the
     * state, so the check that fetches it introduces a restriction during the transaction.
     */
    private static byte[] servedRevocations() throws Exception {
        return PipelineFixtures.revocations(PipelineFixtures.revokedKey(KID_OLD, PUB_OLD),
                PipelineFixtures.revokedKey(KID_X, PUB_X));
    }

    /** The standard rig at {@code root}: a newer release that matches the jar, the live jar holding the old bytes. */
    private static Rig release(Path root) throws Exception {
        byte[] jar = TransactionFixtures.releaseJar(NEW);
        byte[] manifest = TransactionFixtures.manifestFor(jar, NEW);
        Rig r = new Rig();
        r.root = root;
        r.jar = jar;
        r.upd = PipelineFixtures.privateDir(root.resolve("upd"));
        r.live = root.resolve("live").resolve("quicraft.jar");
        Files.createDirectories(r.live.getParent());
        PipelineFixtures.writePrivate(r.live, OLD);
        new StateStore(r.upd).initialiseIfAbsent(seededDelta(), true);
        r.metadata = new PipelineFixtures.ScriptedFetcher();
        TransactionFixtures.serveMetadata(r.metadata, servedRevocations(), manifest);
        r.artifact = new TransactionFixtures.ArtifactFetcher(r.metadata, jar);
        return r;
    }

    private static RuntimeIdentity identity(String running, byte[] liveBytes) throws Exception {
        return new RuntimeIdentity(TransactionFixtures.PLATFORM, TransactionFixtures.TARGET,
                UpdateVersion.parse(running), PipelineFixtures.trust(), TransactionFixtures.sha512Hex(liveBytes));
    }

    /** Reads the live jar from disk on every call, the way a restarted process would. */
    private static LiveJarDiscovery discovery(Path live, byte[] newJar) {
        return () -> {
            byte[] bytes = Files.readAllBytes(live);
            UpdateVersion onDisk = UpdateVersion.parse(Arrays.equals(bytes, newJar) ? NEW : RUNNING);
            return new LiveJar(live.toRealPath(), live.getParent().toRealPath(), bytes.length,
                    TransactionFixtures.sha512Hex(bytes), onDisk);
        };
    }

    private static StateStore hookedStore(Path upd, Boundaries b) {
        return new StateStore(upd, () -> {
            try {
                b.step(() -> null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, directory -> b.step(() -> {
            directory.force(true);
            return null;
        }), (step, fileName) -> b.step(() -> null));
    }

    private static DurabilityOps countingOps(Boundaries b) {
        DurabilityOps system = DurabilityOps.system();
        return new DurabilityOps() {
            @Override
            public void force(FileChannel ch) throws IOException {
                b.step(() -> {
                    system.force(ch);
                    return null;
                });
            }

            @Override
            public void atomicMove(Path from, Path to) throws IOException {
                b.step(() -> {
                    system.atomicMove(from, to);
                    return null;
                });
            }

            @Override
            public boolean syncDirectory(Path dir) throws IOException {
                return b.step(() -> system.syncDirectory(dir));
            }
        };
    }

    private static UpdaterTransaction transaction(Rig r, RuntimeIdentity id, SourceFetcher fetcher, StateStore store,
            DurabilityOps ops) throws Exception {
        CheckPipeline pipeline = new CheckPipeline(store, PipelineFixtures.collector(fetcher), PipelineFixtures.HOSTS,
                id, PipelineFixtures.CLOCK);
        SameStore same = UpdateDirectory::sameFileStore;
        InitBarrier init = () -> true;
        return new UpdaterTransaction(r.upd, ops, store, pipeline, fetcher, PipelineFixtures.ENDPOINTS, Map.of(),
                FetchTimeouts.defaults(), discovery(r.live, r.jar), init, PipelineFixtures.CLOCK, same);
    }

    private static TransactionResult runPlain(Rig r, InstallCapability cap) throws Exception {
        RuntimeIdentity id = identity(RUNNING, OLD);
        return transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system())
                .execute(new UpdateRequest(id, cap, NO_OP));
    }

    private static Optional<JournalRecord> journalOf(Path upd) throws Exception {
        return new UpdateDirectory(upd, DurabilityOps.system()).readJournal();
    }

    private static int stagingCount(Path upd) throws Exception {
        return new UpdateDirectory(upd, DurabilityOps.system()).stagingTxids().size();
    }

    private static int backupCount(Path upd) throws Exception {
        return new UpdateDirectory(upd, DurabilityOps.system()).backupTxids().size();
    }

    private static RecoveryContext recoveryContext(Rig r, RuntimeIdentity id) {
        return new RecoveryContext(r.upd, DurabilityOps.system(), discovery(r.live, r.jar), id);
    }

    private static HealthSignal signal(boolean healthy) {
        return timeout -> CompletableFuture.completedFuture(healthy);
    }

    private static HealthSignal timingOut() {
        return timeout -> {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.completeExceptionally(new TimeoutException("no verdict"));
            return f;
        };
    }

    private static HealthCompletion complete(RecoveryContext ctx, RecoveryTicket ticket, HealthSignal signal)
            throws Exception {
        CompletionStage<HealthCompletion> stage = StartupRecovery.completeHealth(ctx, ticket, signal,
                Duration.ofSeconds(WAIT_SECONDS), DIRECT);
        return stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- invariants ----------------------------------------------------------------------------------

    private static Set<String> pubs(RevokedState revoked) {
        return new HashSet<>(revoked.revokedPubs());
    }

    private static Set<String> withdrawnSha(UpdaterState state) {
        Set<String> out = new HashSet<>();
        state.withdrawn().forEach(w -> out.add(w.sha512()));
        return out;
    }

    /** Revocations and floors never go backwards: every element of {@code before} survives in {@code after}. */
    static void assertMonotone(Snapshot before, Snapshot after) {
        assertTrue(pubs(after.revoked()).containsAll(pubs(before.revoked())), "revoked keys never disappear");
        assertTrue(after.revoked().blockedArtifacts().containsAll(before.revoked().blockedArtifacts()),
                "blocked artifacts never disappear");
        assertTrue(after.state().revocations().seq() >= before.state().revocations().seq(),
                "the revocations sequence never goes back");
        assertTrue(after.state().epoch() >= before.state().epoch(), "the trust epoch never goes back");
        assertTrue(withdrawnSha(after.state()).containsAll(withdrawnSha(before.state())),
                "withdrawn entries never disappear");
        for (Map.Entry<String, KidSeq> e : before.state().seqByKid().entrySet()) {
            KidSeq now = after.state().seqByKid().get(e.getKey());
            assertNotNull(now, "kid floor " + e.getKey() + " is kept");
            assertTrue(now.seq() >= e.getValue().seq(), "kid floor never goes back for " + e.getKey());
        }
    }

    /** The invariants on the crash-time disk image, before startup recovery runs. */
    private static boolean assertCrashImage(Rig r, Snapshot before) throws Exception {
        byte[] live = Files.readAllBytes(r.live);
        boolean liveNew = Arrays.equals(live, r.jar);
        assertTrue(liveNew || Arrays.equals(live, OLD), "the live jar is the old jar or the new verified jar");
        Optional<JournalRecord> journal = journalOf(r.upd);
        if (liveNew) {
            assertTrue(journal.isPresent(), "a live new jar always has a journal to finish the recovery");
            assertArrayEquals(OLD, Files.readAllBytes(new UpdateDirectory(r.upd, DurabilityOps.system())
                    .backupJar(journal.get().txid())), "the backup holds the old jar");
        } else if (journal.isPresent()) {
            assertEquals(JournalPhase.PREPARED, journal.get().phase(),
                    "with the old jar live, no journal may claim a swap");
        }
        assertMonotone(before, new StateStore(r.upd).load().orElseThrow());
        return liveNew;
    }

    // ---- the matrix ----------------------------------------------------------------------------------

    private static Boundaries boundaries(Rig r, Path snap, int at, boolean after) {
        Boundaries b = new Boundaries(r.root, snap);
        b.crashAt = at;
        b.crashAfter = after;
        b.armed = true;
        return b;
    }

    /** Runs the whole transaction once with no crash and returns N_max for {@code cap}. */
    private int countBoundaries(InstallCapability cap) throws Exception {
        Rig r = release(tmp.resolve("count-" + cap));
        Boundaries b = boundaries(r, tmp.resolve("count-snap-" + cap), 0, false);
        RuntimeIdentity id = identity(RUNNING, OLD);
        TransactionResult result = transaction(r, id, r.artifact, hookedStore(r.upd, b), countingOps(b))
                .execute(new UpdateRequest(id, cap, NO_OP));
        TransactionOutcome expected = cap == InstallCapability.VERIFIED_STAGING_ONLY
                ? TransactionOutcome.STAGED_ONLY : TransactionOutcome.INSTALLED_RESTART_PENDING;
        assertEquals(expected, result.outcome(), result.messages().toString());
        // The served revocation is new to the seeded state, so the check introduced it during the transaction.
        assertTrue(pubs(new StateStore(r.upd).load().orElseThrow().revoked()).contains(PUB_X),
                "the transaction introduced a new restriction");
        System.out.println("N_max " + cap + " = " + b.count);
        return b.count;
    }

    /** Crashes at boundary {@code n} of {@code cap}, restores the snapshot, recovers, then runs the next command. */
    private void crashCase(InstallCapability cap, boolean after, int n) throws Exception {
        String name = cap + "-" + (after ? "after" : "before") + "-" + n;
        Rig r = release(tmp.resolve("case-" + name));
        Path snap = tmp.resolve("snap-" + name);
        Snapshot before = new StateStore(r.upd).load().orElseThrow();
        Boundaries b = boundaries(r, snap, n, after);
        RuntimeIdentity id = identity(RUNNING, OLD);
        try {
            transaction(r, id, r.artifact, hookedStore(r.upd, b), countingOps(b))
                    .execute(new UpdateRequest(id, cap, NO_OP));
            fail("boundary " + n + " was never reached; the transaction completed");
        } catch (SimulatedCrash expected) {
            // The crash point. The rest of the process is discarded below.
        }
        assertTrue(b.crashed, "the crash was injected");
        assertNotNull(b.snapshot, "the crash-time copy exists");

        // Restart: the crash-time image is the disk. Cleanup code that ran after the throw is discarded.
        deleteTree(r.root);
        copyTree(b.snapshot, r.root);
        // The crash-time baseline: loaded from the restored disk, before any recovery code runs.
        Snapshot crashTime = new StateStore(r.upd).load().orElseThrow();

        boolean liveNew = assertCrashImage(r, before);
        byte[] crashLive = Files.readAllBytes(r.live);

        RecoveryResult rr = StartupRecovery.run(recoveryContext(r, identity(liveNew ? NEW : RUNNING, crashLive)));
        assertNotEquals(RecoveryStatus.DISABLED, rr.status(), rr.messages().toString());
        assertMonotone(crashTime, new StateStore(r.upd).load().orElseThrow());
        assertArrayEquals(crashLive, Files.readAllBytes(r.live), "recovery does not move the live jar");
        if (liveNew) {
            assertTrue(rr.status() == RecoveryStatus.HEALTH_PENDING
                    || rr.status() == RecoveryStatus.RECOVERY_REQUIRED, rr.messages().toString());
        } else {
            assertTrue(rr.status() == RecoveryStatus.IDLE || rr.status() == RecoveryStatus.RECOVERY_REQUIRED,
                    rr.messages().toString());
        }
        RecoveryContext ctx = recoveryContext(r, identity(liveNew ? NEW : RUNNING, crashLive));
        TransactionResult follow;
        if (rr.status() == RecoveryStatus.HEALTH_PENDING) {
            assertTrue(rr.ticket().isPresent(), "health pending carries a ticket");
            // Before the verdict the journal is still BOOT_PENDING, so the next command must be refused.
            RuntimeIdentity restarted = identity(NEW, Files.readAllBytes(r.live));
            TransactionResult refused = transaction(r, restarted, r.artifact, new StateStore(r.upd),
                    DurabilityOps.system()).execute(new UpdateRequest(restarted, cap, NO_OP));
            assertEquals(TransactionOutcome.NOT_ADMITTED, refused.outcome(), refused.messages().toString());
            assertTrue(journalOf(r.upd).isPresent(), "the refused command leaves the journal alone");
            assertEquals(HealthCompletion.HEALTHY, complete(ctx, rr.ticket().get(), signal(true)),
                    "a healthy new jar completes the recovery");
            assertFalse(journalOf(r.upd).isPresent(), "a healthy completion removes the journal");
        }
        if (!liveNew && rr.status() == RecoveryStatus.IDLE) {
            assertFalse(journalOf(r.upd).isPresent(), "an idle recovery with the old jar leaves no journal");
        }
        if (rr.status() != RecoveryStatus.RECOVERY_REQUIRED) {
            assertEquals(0, stagingCount(r.upd), "no abandoned staging file after recovery or health");
        }
        assertMonotone(before, new StateStore(r.upd).load().orElseThrow());
        assertMonotone(crashTime, new StateStore(r.upd).load().orElseThrow());

        // Backups that exist before the next command must survive it unchanged (12.10 step 7).
        Map<String, byte[]> predecessors = backupBytes(r.upd);

        // The next command on the restarted identity, now that no journal is pending.
        byte[] nowLive = Files.readAllBytes(r.live);
        RuntimeIdentity restarted = identity(Arrays.equals(nowLive, r.jar) ? NEW : RUNNING, nowLive);
        assertFalse(journalOf(r.upd).isPresent(), "no journal is pending for the next command");
        int stagedBefore = stagingCount(r.upd);
        int backupsBefore = backupCount(r.upd);
        follow = transaction(r, restarted, r.artifact, new StateStore(r.upd), DurabilityOps.system())
                .execute(new UpdateRequest(restarted, cap, NO_OP));
        TransactionOutcome expected = expectedFollow(cap, liveNew, rr.status());
        assertEquals(expected, follow.outcome(), follow.messages().toString());
        assertFollowDisk(r, expected, nowLive, stagedBefore, backupsBefore);
        assertPredecessorsUnchanged(r.upd, predecessors);
        assertMonotone(before, new StateStore(r.upd).load().orElseThrow());
        assertMonotone(crashTime, new StateStore(r.upd).load().orElseThrow());
        replayRefused(r, cap);
        System.out.println("CASE " + name + " liveNew=" + liveNew + " recovery=" + rr.status()
                + " follow=" + follow.outcome());
    }

    /** The exact next-command outcome for a recovered state (12.10 admission, 12.11 recovery). */
    private static TransactionOutcome expectedFollow(InstallCapability cap, boolean liveNew, RecoveryStatus status) {
        if (status == RecoveryStatus.RECOVERY_REQUIRED) {
            return TransactionOutcome.NOT_ADMITTED;
        }
        if (liveNew) {
            return TransactionOutcome.NO_UPDATE;
        }
        return cap == InstallCapability.VERIFIED_STAGING_ONLY
                ? TransactionOutcome.STAGED_ONLY : TransactionOutcome.INSTALLED_RESTART_PENDING;
    }

    /** The disk state that each recovered outcome leaves behind. */
    private static void assertFollowDisk(Rig r, TransactionOutcome outcome, byte[] liveBefore, int stagedBefore,
            int backupsBefore) throws Exception {
        byte[] live = Files.readAllBytes(r.live);
        switch (outcome) {
            case NO_UPDATE:
                assertArrayEquals(liveBefore, live, "no update leaves the live jar alone");
                assertFalse(journalOf(r.upd).isPresent(), "no update leaves no journal");
                assertEquals(0, stagingCount(r.upd), "no update stages nothing");
                break;
            case NOT_ADMITTED:
                assertArrayEquals(liveBefore, live, "a refused command leaves the live jar alone");
                assertEquals(stagedBefore, stagingCount(r.upd), "a refused command leaves staging alone");
                assertEquals(backupsBefore, backupCount(r.upd), "a refused command leaves the backups alone");
                break;
            case STAGED_ONLY:
                assertArrayEquals(OLD, live, "a staged-only command never replaces the live jar");
                assertEquals(1, stagingCount(r.upd), "the verified jar is staged");
                assertEquals(0, backupCount(r.upd) - backupsBefore, "a staged-only command makes no backup");
                break;
            case INSTALLED_RESTART_PENDING:
                assertArrayEquals(r.jar, live, "the atomic command installs the new jar");
                assertTrue(journalOf(r.upd).isPresent(), "an installed jar keeps its journal until health");
                assertEquals(backupsBefore + 1, backupCount(r.upd), "the install keeps one new backup");
                break;
            default:
                fail("unexpected follow-up outcome " + outcome);
        }
    }

    private static Map<String, byte[]> backupBytes(Path upd) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        UpdateDirectory ud = new UpdateDirectory(upd, DurabilityOps.system());
        for (String txid : ud.backupTxids()) {
            out.put(txid, Files.readAllBytes(ud.backupJar(txid)));
        }
        return out;
    }

    /** Backups the next command could not have deleted (only a later transaction that passes health may). */
    private static void assertPredecessorsUnchanged(Path upd, Map<String, byte[]> predecessors) throws Exception {
        UpdateDirectory ud = new UpdateDirectory(upd, DurabilityOps.system());
        for (Map.Entry<String, byte[]> e : predecessors.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(ud.backupJar(e.getKey())),
                    "a predecessor backup is unchanged: " + e.getKey());
        }
    }

    /**
     * An older revocations document and an older manifest are served after recovery. The document omits
     * {@link #PUB_X}, so accepting it would remove a restriction. Nothing is installed and nothing is removed.
     */
    private static void replayRefused(Rig r, InstallCapability cap) throws Exception {
        byte[] revokedBefore = Files.readAllBytes(r.upd.resolve("revoked.json"));
        byte[] liveBefore = Files.readAllBytes(r.live);
        TransactionFixtures.serveMetadata(r.metadata, PipelineFixtures.revocationsSeq(2,
                PipelineFixtures.revokedKey(KID_OLD, PUB_OLD)), TransactionFixtures.manifestFor(r.jar, NEW, 41));
        RuntimeIdentity id = identity(Arrays.equals(liveBefore, r.jar) ? NEW : RUNNING, liveBefore);
        TransactionResult replay = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system())
                .execute(new UpdateRequest(id, cap, NO_OP));
        assertTrue(replay.outcome() != TransactionOutcome.STAGED_ONLY
                && replay.outcome() != TransactionOutcome.INSTALLED_RESTART_PENDING, replay.messages().toString());
        assertArrayEquals(liveBefore, Files.readAllBytes(r.live), "a replayed older release leaves the live jar");
        assertArrayEquals(revokedBefore, Files.readAllBytes(r.upd.resolve("revoked.json")),
                "a replayed older revocations document removes nothing");
        assertTrue(pubs(new StateStore(r.upd).load().orElseThrow().revoked()).contains(PUB_X),
                "the restriction stays after the replay");
    }

    @TestFactory
    Stream<DynamicTest> everyDurabilityBoundaryRecoversToAConsistentState() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (InstallCapability cap : new InstallCapability[] {
                InstallCapability.VERIFIED_STAGING_ONLY, InstallCapability.ATOMIC_REPLACEMENT}) {
            int max = countBoundaries(cap);
            assertTrue(max > 0, "the transaction has durability boundaries");
            for (boolean after : new boolean[] {false, true}) {
                for (int n = 1; n <= max; n++) {
                    final int at = n;
                    final boolean isAfter = after;
                    tests.add(DynamicTest.dynamicTest(cap + " crash " + (isAfter ? "after" : "before")
                            + " boundary " + at, () -> crashCase(cap, isAfter, at)));
                }
            }
        }
        return tests.stream();
    }

    // ---- named scenario: crash between the two state-file writes -------------------------------

    private static Snapshot withRevocationAndFloor(Snapshot s) {
        List<RevokedKey> keys = new ArrayList<>(s.revoked().revokedKeys());
        if (keys.stream().noneMatch(k -> k.pub().equals(PUB_X))) {
            keys.add(new RevokedKey(KID_X, PUB_X));
        }
        Map<String, KidSeq> seqs = new HashMap<>(s.state().seqByKid());
        seqs.putIfAbsent(KID_X, new KidSeq(2, HEX_C, Instant.parse(SignedFixtures.TS1)));
        UpdaterState st = s.state();
        return new Snapshot(new UpdaterState(st.epoch(), st.keySetFingerprint(), st.revocationKey(),
                st.revocations(), seqs, st.withdrawn(), st.lastAnnounced(), st.warnedBad(), st.tornSince()),
                new RevokedState(keys, s.revoked().blockedArtifacts()));
    }

    @Test
    void crashBetweenTheStateFileWritesRecoversAndConvergesIdempotently() throws Exception {
        Path upd = PipelineFixtures.privateDir(tmp.resolve("upd"));
        new StateStore(upd).initialiseIfAbsent(new StateDelta(PipelineFixtures.seedState(), RevokedState.empty()),
                true);
        Snapshot before = new StateStore(upd).load().orElseThrow();
        boolean[] armed = {true};
        StateStore crashing = new StateStore(upd, () -> {
            if (armed[0]) {
                armed[0] = false;
                throw new SimulatedCrash("between the revoked.json and state.json writes");
            }
        });
        assertThrows(SimulatedCrash.class, () -> crashing.commit(UpdateCrashMatrixTest::withRevocationAndFloor));

        Snapshot mid = new StateStore(upd).load().orElseThrow();
        assertTrue(pubs(mid.revoked()).contains(PUB_X), "revoked.json is newer");
        assertFalse(mid.state().seqByKid().containsKey(KID_X), "state.json is still the older file");
        assertMonotone(before, mid);

        Snapshot once = new StateStore(upd).commit(UpdateCrashMatrixTest::withRevocationAndFloor);
        assertTrue(once.state().seqByKid().containsKey(KID_X), "the next commit converges state.json");
        assertMonotone(mid, once);
        byte[] revokedBytes = Files.readAllBytes(upd.resolve("revoked.json"));
        byte[] stateBytes = Files.readAllBytes(upd.resolve("state.json"));
        Snapshot again = new StateStore(upd).commit(UpdateCrashMatrixTest::withRevocationAndFloor);
        assertEquals(once, again, "merging the same change twice is idempotent");
        assertArrayEquals(revokedBytes, Files.readAllBytes(upd.resolve("revoked.json")),
                "a repeated merge rewrites nothing in revoked.json");
        assertArrayEquals(stateBytes, Files.readAllBytes(upd.resolve("state.json")),
                "a repeated merge rewrites nothing in state.json");
        assertEquals(1, pubs(again.revoked()).stream().filter(PUB_X::equals).count(), "the key is listed once");
    }

    // ---- named scenarios: trust, the backup key, and refusals ----------------------------------

    @Test
    void staleTrustSnapshotAfterAnotherCheckChangedTrustRefusesTheSwap() throws Exception {
        Rig r = release(tmp.resolve("stale-trust"));
        RuntimeIdentity id = identity(RUNNING, OLD);
        UpdaterTransaction txn = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system());
        txn.hooks.afterBackupReady = () -> mergeRevocation(r.upd, TestKeys.KID_1, TestKeys.PUB_1);
        TransactionResult result = txn.execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals(UpdaterTransaction.RELEASE_NO_LONGER_VERIFIES, result.messages().get(0));
        assertArrayEquals(OLD, Files.readAllBytes(r.live));
        assertFalse(journalOf(r.upd).isPresent());
        assertEquals(0, stagingCount(r.upd));
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    /** A key-set change (epoch 2) naming the backup key, as the staged jar's trust file. */
    private static final String KEY_SET_EPOCH2 = "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":2,\"built\":\""
            + SignedFixtures.TS1 + "\",\"revocationsSeqFloor\":0,\"manifestSeqFloor\":0,\"keys\":[{\"kid\":\""
            + TestKeys.KID_2 + "\",\"pub\":\"" + TestKeys.PUB_2 + "\",\"roles\":[\"release\",\"revocation\"]}]}";

    /**
     * The rig for the key-set change. The served manifest is co-signed by the release key and by the current
     * backup key (KID_2, revocation role), which KeySetChangeAuthorization requires for a changed fingerprint.
     */
    private Rig keySetRig(String name) throws Exception {
        Rig r = release(tmp.resolve(name));
        byte[] jar = TransactionFixtures.releaseJar(NEW, KEY_SET_EPOCH2);
        byte[] manifest = TransactionFixtures.manifestFor(jar, NEW);
        TransactionFixtures.serveMetadata(r.metadata, servedRevocations(), manifest);
        for (ReleaseHost host : PipelineFixtures.HOSTS.hosts()) {
            r.metadata.answer(PipelineFixtures.ENDPOINTS.manifestSignature(host), SignedFixtures.envelopeFor(manifest,
                    SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, manifest),
                    SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, manifest)));
        }
        r.jar = jar;
        r.artifact = new TransactionFixtures.ArtifactFetcher(r.metadata, jar);
        return r;
    }

    @Test
    void authorisedKeySetChangeWithBackupCoSignatureInstalls() throws Exception {
        Rig r = keySetRig("keyset-authorised");
        RuntimeIdentity id = identity(RUNNING, OLD);
        TransactionResult result = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system())
                .execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        assertTrue(journalOf(r.upd).isPresent(), "the install keeps its journal until health");
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    @Test
    void backupKeyRevokedAfterTheStagedKeySetCheckMustNotSwap() throws Exception {
        // The same authorised key-set change. The backup key is revoked after the step 5 check (the race): the
        // step 6 re-authorisation must refuse, the swap never starts, the live jar stays the old one and the
        // backup is kept (docs/updater.md 12.10 step 6).
        Rig r = keySetRig("keyset");
        RuntimeIdentity id = identity(RUNNING, OLD);
        UpdaterTransaction txn = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system());
        boolean[] reached = {false};
        txn.hooks.afterBackupReady = () -> {
            reached[0] = true;
            mergeRevocation(r.upd, TestKeys.KID_2, TestKeys.PUB_2);
        };
        TransactionResult result = txn.execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertTrue(reached[0], "the revocation hook ran after the step 5 check, before the swap");
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertArrayEquals(OLD, Files.readAllBytes(r.live), "the old live jar stays");
        assertEquals(0, stagingCount(r.upd), "staging is cleaned");
        assertFalse(journalOf(r.upd).isPresent(), "the journal is removed");
        assertEquals(1, backupCount(r.upd), "the backup is kept (12.10 step 6)");
    }

    @Test
    void keySetChangeWithoutBackupCoSignatureNeverStartsTheSwap() throws Exception {
        // A release-only signature on a changed key set: the step 5 check refuses, so the swap never starts.
        byte[] jar = TransactionFixtures.releaseJar(NEW, KEY_SET_EPOCH2);
        Rig r = release(tmp.resolve("keyset-release-only"));
        TransactionFixtures.serveMetadata(r.metadata, servedRevocations(), TransactionFixtures.manifestFor(jar, NEW));
        r.jar = jar;
        r.artifact = new TransactionFixtures.ArtifactFetcher(r.metadata, jar);
        RuntimeIdentity id = identity(RUNNING, OLD);
        UpdaterTransaction txn = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system());
        boolean[] reached = {false};
        txn.hooks.afterBackupReady = () -> reached[0] = true;
        TransactionResult result = txn.execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertFalse(reached[0], "the swap never starts");
        assertTrue(result.messages().get(0).contains("not authorized by the backup key"), result.messages().toString());
        assertArrayEquals(OLD, Files.readAllBytes(r.live));
        assertFalse(journalOf(r.upd).isPresent());
    }

    @Test
    void liveJarChangedBeforeTheCompareAndSwapRefusesWithNothingInstalled() throws Exception {
        Rig r = release(tmp.resolve("live-changed"));
        RuntimeIdentity id = identity(RUNNING, OLD);
        UpdaterTransaction txn = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system());
        byte[] other = "a different live jar, written by someone else".getBytes(StandardCharsets.UTF_8);
        txn.hooks.afterBackupReady = () -> {
            try {
                Files.write(r.live, other);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        TransactionResult result = txn.execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals(UpdaterTransaction.LIVE_CHANGED, result.messages().get(0));
        assertArrayEquals(other, Files.readAllBytes(r.live), "the other party's bytes are left alone");
        assertEquals(0, stagingCount(r.upd));
        assertFalse(journalOf(r.upd).isPresent());
        assertEquals(0, backupCount(r.upd), "this attempt's backup is removed");
    }

    @Test
    void rollbackLineNotLoggedRefusesTheInstallAndCleansEverything() throws Exception {
        Rig r = release(tmp.resolve("rollback-not-logged"));
        RuntimeIdentity id = identity(RUNNING, OLD);
        TransactionResult result = transaction(r, id, r.artifact, new StateStore(r.upd), DurabilityOps.system())
                .execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, line -> {
                    throw new IllegalStateException("the log sink is broken");
                }));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals(UpdaterTransaction.ROLLBACK_NOT_LOGGED, result.messages().get(0));
        assertArrayEquals(OLD, Files.readAllBytes(r.live));
        assertEquals(0, stagingCount(r.upd));
        assertFalse(journalOf(r.upd).isPresent());
        assertEquals(0, backupCount(r.upd));
    }

    @Test
    void postMoveRehashMismatchKeepsThePreparedJournalAsEvidence() throws Exception {
        Rig r = release(tmp.resolve("moved-mismatch"));
        RuntimeIdentity id = identity(RUNNING, OLD);
        byte[] tampered = "written over the live path after the move".getBytes(StandardCharsets.UTF_8);
        // A DurabilityOps whose move lands the staged bytes and then overwrites the live path.
        DurabilityOps tamper = new DurabilityOps() {
            private final DurabilityOps system = DurabilityOps.system();

            @Override
            public void force(FileChannel ch) throws IOException {
                system.force(ch);
            }

            @Override
            public void atomicMove(Path from, Path to) throws IOException {
                system.atomicMove(from, to);
                if (to.getFileName().toString().equals("quicraft.jar")) {
                    Files.write(to, tampered);
                }
            }

            @Override
            public boolean syncDirectory(Path dir) throws IOException {
                return system.syncDirectory(dir);
            }
        };
        TransactionResult result = transaction(r, id, r.artifact, new StateStore(r.upd), tamper)
                .execute(new UpdateRequest(id, InstallCapability.ATOMIC_REPLACEMENT, NO_OP));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().contains(UpdaterTransaction.MOVED_MISMATCH), result.messages().toString());
        assertArrayEquals(tampered, Files.readAllBytes(r.live), "nothing is rewritten after the move");
        assertEquals(JournalPhase.PREPARED, journalOf(r.upd).orElseThrow().phase());
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    // ---- named scenarios: cancel at each cancellable point --------------------------------------

    enum CancelPoint { DURING_CHECK, AFTER_DOWNLOAD, DURING_REVALIDATION, AT_BACKUP_READY, BEFORE_FLIP }

    @TestFactory
    Stream<DynamicTest> cancelAtEachCancellablePointLeavesStagingJournalAndLiveClean() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (InstallCapability cap : new InstallCapability[] {
                InstallCapability.VERIFIED_STAGING_ONLY, InstallCapability.ATOMIC_REPLACEMENT}) {
            for (CancelPoint point : CancelPoint.values()) {
                boolean atomic = cap == InstallCapability.ATOMIC_REPLACEMENT;
                if (!atomic && (point == CancelPoint.AT_BACKUP_READY || point == CancelPoint.BEFORE_FLIP)) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest(cap + " cancel " + point,
                        () -> cancelAt(cap, point)));
            }
        }
        return tests.stream();
    }

    private void cancelAt(InstallCapability cap, CancelPoint point) throws Exception {
        Rig r = release(tmp.resolve("cancel-" + cap + "-" + point));
        RuntimeIdentity id = identity(RUNNING, OLD);
        UpdaterTransaction[] ref = new UpdaterTransaction[1];
        boolean[] requested = {false};
        boolean[] downloaded = {false};
        Hooked fetcher = new Hooked(r.artifact);
        fetcher.afterDownload = () -> downloaded[0] = true;
        fetcher.beforeFetch = () -> {
            if (point == CancelPoint.DURING_CHECK && !requested[0]) {
                requested[0] = true;
                ref[0].cancelActive();
            }
            if (point == CancelPoint.DURING_REVALIDATION && downloaded[0] && !requested[0]) {
                requested[0] = true;
                ref[0].cancelActive();
            }
        };
        UpdaterTransaction txn = transaction(r, id, fetcher, new StateStore(r.upd), DurabilityOps.system());
        ref[0] = txn;
        if (point == CancelPoint.AFTER_DOWNLOAD) {
            fetcher.afterDownload = () -> {
                requested[0] = true;
                txn.cancelActive();
            };
        }
        if (point == CancelPoint.AT_BACKUP_READY) {
            txn.hooks.afterBackupReady = () -> {
                requested[0] = true;
                txn.cancelActive();
            };
        }
        if (point == CancelPoint.BEFORE_FLIP) {
            txn.hooks.beforeSwapFlip = () -> {
                requested[0] = true;
                txn.cancelActive();
            };
        }
        TransactionResult result = txn.execute(new UpdateRequest(id, cap, NO_OP));
        assertTrue(requested[0], "the cancel was requested at " + point);
        assertEquals(TransactionOutcome.CANCELLED, result.outcome(), result.messages().toString());
        assertArrayEquals(OLD, Files.readAllBytes(r.live));
        assertEquals(0, stagingCount(r.upd), "staging is cleaned");
        assertFalse(journalOf(r.upd).isPresent(), "the journal is cleaned");
        boolean backupKept = cap == InstallCapability.ATOMIC_REPLACEMENT
                && (point == CancelPoint.AT_BACKUP_READY || point == CancelPoint.BEFORE_FLIP);
        assertEquals(backupKept ? 1 : 0, backupCount(r.upd), "the backup is kept only after it was made");
    }

    // ---- named scenarios: restart with the old jar, and with the new jar ------------------------

    /** A completed atomic install: the live jar is new, the journal says SWAPPED, the backup holds the old jar. */
    private Rig installed(String name) throws Exception {
        Rig r = release(tmp.resolve(name));
        TransactionResult result = runPlain(r, InstallCapability.ATOMIC_REPLACEMENT);
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        return r;
    }

    @Test
    void restartWithTheOldJarAfterASwapTakesTheManualRestorePath() throws Exception {
        Rig r = installed("restart-old");
        // The owner copied the backup back by hand; the server restarts on the old bytes.
        Files.write(r.live, OLD);
        RecoveryResult rr = StartupRecovery.run(recoveryContext(r, identity(RUNNING, OLD)));
        assertEquals(RecoveryStatus.IDLE, rr.status(), rr.messages().toString());
        assertTrue(rr.messages().contains(StartupRecovery.MSG_MANUAL_RESTORE), rr.messages().toString());
        assertArrayEquals(OLD, Files.readAllBytes(r.live));
        assertFalse(journalOf(r.upd).isPresent(), "the journal is removed");
        assertEquals(0, stagingCount(r.upd), "the staged jar is discarded");
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    @Test
    void restartWithTheNewJarHealthyCompletesAndRemovesTheJournal() throws Exception {
        Rig r = installed("restart-healthy");
        RecoveryContext ctx = recoveryContext(r, identity(NEW, r.jar));
        RecoveryResult rr = StartupRecovery.run(ctx);
        assertEquals(RecoveryStatus.HEALTH_PENDING, rr.status(), rr.messages().toString());
        assertEquals(HealthCompletion.HEALTHY, complete(ctx, rr.ticket().orElseThrow(), signal(true)));
        assertFalse(journalOf(r.upd).isPresent());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
    }

    @Test
    void restartWithTheNewJarUnhealthyKeepsTheJournalAndTheBackup() throws Exception {
        Rig r = installed("restart-failed");
        RecoveryContext ctx = recoveryContext(r, identity(NEW, r.jar));
        RecoveryResult rr = StartupRecovery.run(ctx);
        assertEquals(RecoveryStatus.HEALTH_PENDING, rr.status(), rr.messages().toString());
        assertEquals(HealthCompletion.FAILED, complete(ctx, rr.ticket().orElseThrow(), signal(false)));
        assertEquals(JournalPhase.BOOT_PENDING, journalOf(r.upd).orElseThrow().phase());
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    @Test
    void restartWithTheNewJarWithoutAVerdictTimesOutAndKeepsEverything() throws Exception {
        Rig r = installed("restart-timeout");
        RecoveryContext ctx = recoveryContext(r, identity(NEW, r.jar));
        RecoveryResult rr = StartupRecovery.run(ctx);
        assertEquals(RecoveryStatus.HEALTH_PENDING, rr.status(), rr.messages().toString());
        assertEquals(HealthCompletion.TIMED_OUT, complete(ctx, rr.ticket().orElseThrow(), timingOut()));
        assertEquals(JournalPhase.BOOT_PENDING, journalOf(r.upd).orElseThrow().phase());
        assertEquals(1, backupCount(r.upd), "the backup is kept");
    }

    // ---- helpers for the revocation merge ------------------------------------------------------

    /** Merges one revoked key into the persisted revocations, as a concurrent command would. */
    private static void mergeRevocation(Path upd, String kid, String pub) {
        try {
            new StateStore(upd).commit(snap -> {
                List<RevokedKey> keys = new ArrayList<>(snap.revoked().revokedKeys());
                keys.add(new RevokedKey(kid, pub));
                return new Snapshot(snap.state(), new RevokedState(keys, snap.revoked().blockedArtifacts()));
            });
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }
}
