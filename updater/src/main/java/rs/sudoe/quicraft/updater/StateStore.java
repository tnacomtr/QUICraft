// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Owns {@code state.json} and {@code revoked.json} (plus their {@code .prev} copies) in the updater
 * directory (docs/updater.md 12.8 and 12.9).
 *
 * <p>Every operation takes an in-process guard, then the OS lock on {@code state.lock} without
 * waiting. A lock held by another process raises {@link StoreBusyException}. The two files are
 * replaced independently, each atomically; {@code revoked.json} is always written first. Corruption
 * falls back to the per-file {@code .prev} copy, and a file is never reset silently.
 *
 * <p>Filesystem safety: symlinks are refused, the directory and files must be owned by the user that
 * runs the server, and on POSIX the directory is created {@code rwx------} and files {@code rw-------}.
 */
public final class StateStore {

    static final String STATE = "state.json";
    static final String REVOKED = "revoked.json";
    static final String LOCK = "state.lock";
    static final String TXN_LOCK = "txn.lock";
    static final String STATE_FILES_MISSING = "updater state files are missing; not rebuilt silently";
    private static final String PREV = ".prev";
    private static final String TMP = ".tmp";
    private static final String OWNER_MISMATCH = "update directory not owned by the server user";

    private static final ConcurrentHashMap<Path, ReentrantLock> GUARDS = new ConcurrentHashMap<>();

    /**
     * True on a thread while it holds the state lock. Used to refuse re-entry into the store from a
     * locked callback, and to refuse taking {@link TransactionLock} inside a state lock (lock order:
     * txn before state).
     */
    private static final ThreadLocal<Boolean> IN_STATE_LOCK = ThreadLocal.withInitial(() -> false);

    private final Path dir;
    private final Runnable afterRevokedWrite;
    private final DirectoryForce directoryForce;
    private final AtomicWriteHook atomicWriteHook;
    private static final ConcurrentHashMap<Path, Boolean> FSYNC_SUPPORT = new ConcurrentHashMap<>();
    private static final int MAX_FILE_BYTES = RevokedCodec.MAX_BYTES + 1;
    private static final long PROBE_STALE_SECONDS = 60;

    /** Forces a directory channel to disk. The seam lets tests inject an fsync failure. */
    interface DirectoryForce {
        void force(FileChannel directory) throws IOException;
    }
    private volatile List<String> lastWarnings = List.of();

    /** Opens the store for {@code updateDir}. Nothing is read or written until an operation runs. */
    public StateStore(Path updateDir) {
        this(updateDir, () -> { });
    }

    /** Test seam: {@code afterRevokedWrite} runs after revoked.json is replaced, before state.json. */
    StateStore(Path updateDir, Runnable afterRevokedWrite) {
        this(updateDir, afterRevokedWrite, directory -> directory.force(true));
    }

    /** Test seam with an injectable directory fsync. */
    StateStore(Path updateDir, Runnable afterRevokedWrite, DirectoryForce directoryForce) {
        this(updateDir, afterRevokedWrite, directoryForce, (step, fileName) -> { });
    }

    /** Test seam with an injectable directory fsync and a hook at every step of {@link #atomicWrite}. */
    StateStore(Path updateDir, Runnable afterRevokedWrite, DirectoryForce directoryForce,
            AtomicWriteHook atomicWriteHook) {
        this.dir = Objects.requireNonNull(updateDir, "updateDir").toAbsolutePath().normalize();
        this.afterRevokedWrite = Objects.requireNonNull(afterRevokedWrite, "afterRevokedWrite");
        this.directoryForce = Objects.requireNonNull(directoryForce, "directoryForce");
        this.atomicWriteHook = Objects.requireNonNull(atomicWriteHook, "atomicWriteHook");
    }

    /** The steps of {@link #atomicWrite}, each reported before and after its file operation. */
    enum AtomicStep {
        TMP_WRITE_BEFORE, TMP_WRITE_AFTER,
        PREV_TMP_WRITE_BEFORE, PREV_TMP_WRITE_AFTER,
        PREV_RENAME_BEFORE, PREV_RENAME_AFTER,
        TARGET_RENAME_BEFORE, TARGET_RENAME_AFTER
    }

    /** Test seam: called around every file operation of {@link #atomicWrite}. Production uses a no-op. */
    @FunctionalInterface
    interface AtomicWriteHook {
        void at(AtomicStep step, String fileName) throws IOException;
    }

    /**
     * Whether this platform can open and fsync the directory. Probed once per canonical directory
     * and cached. Never throws: any failure means "not supported".
     */
    static boolean directoryFsyncSupported(Path dir) {
        Path key;
        try {
            key = dir.toRealPath();
        } catch (IOException e) {
            return false;
        }
        return FSYNC_SUPPORT.computeIfAbsent(key, k -> {
            try (FileChannel ch = FileChannel.open(k, StandardOpenOption.READ)) {
                ch.force(true);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
    }

    /** Fixed-message warnings raised by the last operation on this store (never file content). */
    public List<String> warnings() {
        return lastWarnings;
    }

    /**
     * Loads both files. Returns empty when neither file exists at all.
     *
     * @throws StateUnavailableException when a file has no valid copy
     */
    public Optional<StateRecords.Snapshot> load()
            throws IOException, UpdateRejectedException {
        return withLocks(warnings -> {
            if (!anyFileExists()) {
                return Optional.empty();
            }
            return Optional.of(readSnapshot(warnings).snapshot());
        });
    }

    /**
     * Writes the initial files when neither exists and {@code firstRunAllowed} is true; otherwise
     * returns the loaded state unchanged. {@code revoked.json} is written first. With no files and
     * {@code firstRunAllowed} false, throws {@link StateUnavailableException} (updater disabled): the
     * owner then removes the whole update directory, which makes the next start a first run.
     *
     * <p>A missing {@code state.json} with a present {@code revoked.json} is reseeded only when
     * {@code firstRunAllowed} is true. Platforms pass true only when no QUICraft update directory
     * existed before this start (they determine this before creating it). Otherwise the store
     * stays unavailable, because a state file vanished after the directory was in use and must
     * not be rebuilt silently (docs/updater.md, Corruption and Initialisation).
     */
    public StateRecords.Snapshot initialiseIfAbsent(StateRecords.StateDelta initial, boolean firstRunAllowed)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(initial, "initial");
        return withLocks(warnings -> {
            if (!anyFileExists()) {
                if (!firstRunAllowed) {
                    // Both files vanished after the directory was in use: never rebuild them silently.
                    throw new StateUnavailableException(false, STATE_FILES_MISSING);
                }
                atomicWrite(REVOKED, RevokedCodec.encode(initial.newRevoked()),
                        StateStore::validRevoked, warnings);
                atomicWrite(STATE, StateCodec.encode(initial.newState()),
                        StateStore::validState, warnings);
                return new StateRecords.Snapshot(initial.newState(), initial.newRevoked());
            }
            if (firstRunAllowed
                    && !Files.exists(dir.resolve(STATE), LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(dir.resolve(STATE + PREV), LinkOption.NOFOLLOW_LINKS)) {
                // Interrupted first run: revoked.json was written, state.json never was. Only this
                // case reseeds; a later deletion still goes through load() and stays unavailable.
                Loaded<StateRecords.RevokedState> revoked =
                        readFile(REVOKED, RevokedCodec::decode, new ArrayList<>());
                if (revoked.value() != null) {
                    warnings.add("state.json missing after interrupted initialisation; reseeded");
                    atomicWrite(STATE, StateCodec.encode(initial.newState()),
                            StateStore::validState, warnings);
                    return new StateRecords.Snapshot(initial.newState(), revoked.value());
                }
            }
            return readSnapshot(warnings).snapshot();
        });
    }

    /** A callback that runs while both locks are held and receives the freshly read state. */
    public interface LockedStateOperation<T> {
        T run(StateRecords.Snapshot freshlyRead) throws IOException, UpdateRejectedException;
    }

    /**
     * Re-reads both files under the state lock (with the corruption rules) and runs {@code op} while
     * both locks are held. The callback must not call any other {@link StateStore} method on this
     * thread (re-entry raises IllegalStateException) and must not take {@link TransactionLock}.
     *
     * @throws IllegalStateException if the store was never initialised
     */
    public <T> T withLockedSnapshot(LockedStateOperation<T> op)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(op, "op");
        return withLocks(warnings -> {
            if (!anyFileExists()) {
                throw new IllegalStateException("state store not initialised");
            }
            return op.run(readSnapshot(warnings).snapshot());
        });
    }

    /**
     * Read-merge-write under both locks. Writes {@code revoked.json} first, then {@code state.json};
     * a file is skipped when its content did not change. Each write is independent, so a crash in
     * between leaves a state the next commit converges from.
     *
     * @throws IllegalStateException if the store was never initialised
     */
    public StateRecords.Snapshot commit(UnaryOperator<StateRecords.Snapshot> merge)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(merge, "merge");
        return withLocks(warnings -> {
            if (!anyFileExists()) {
                throw new IllegalStateException("state store not initialised");
            }
            Read current = readSnapshot(warnings);
            StateRecords.Snapshot next = merge.apply(current.snapshot());
            byte[] revokedBytes = RevokedCodec.encode(next.revoked());
            byte[] stateBytes = StateCodec.encode(next.state());

            boolean writeRevoked = !current.revokedMainValid()
                    || !next.revoked().equals(current.snapshot().revoked());
            boolean writeState = !current.stateMainValid()
                    || !next.state().equals(current.snapshot().state());
            if (writeRevoked) {
                atomicWrite(REVOKED, revokedBytes, StateStore::validRevoked, warnings);
                afterRevokedWrite.run();
            }
            if (writeState) {
                atomicWrite(STATE, stateBytes, StateStore::validState, warnings);
            }
            return next;
        });
    }

    /**
     * Rebuilds {@code state.json} from {@code rebuilt}. Requires a valid revoked.json (current or
     * {@code .prev}) and never writes revoked.json.
     *
     * @throws UpdateRejectedException "revoked.json invalid; refetch required" when none is valid
     */
    public StateRecords.Snapshot resetState(StateRecords.UpdaterState rebuilt)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(rebuilt, "rebuilt");
        return withLocks(warnings -> {
            Loaded<StateRecords.RevokedState> revoked = readFile(REVOKED, RevokedCodec::decode, warnings);
            if (revoked.value() == null) {
                throw new UpdateRejectedException("revoked.json invalid; refetch required");
            }
            byte[] bytes = StateCodec.encode(rebuilt);
            atomicWrite(STATE, bytes, StateStore::validState, warnings);
            return new StateRecords.Snapshot(rebuilt, revoked.value());
        });
    }

    /**
     * What the files held when a reset started, read under the state lock. {@code state} and
     * {@code revoked} are empty when the file has no valid copy (a missing or an unreadable file).
     *
     * @param anyFileExists false when neither state file exists at all
     */
    record ResetInput(boolean anyFileExists, Optional<StateRecords.UpdaterState> state,
            Optional<StateRecords.RevokedState> revoked) {
    }

    /** Decides a reset from its input: a refusal with messages, or the rebuilt state to write. */
    interface ResetPlanner {
        ResetDecision plan(ResetInput input) throws UpdateRejectedException;
    }

    /**
     * The planner's decision. Exactly one of the two holds: {@code rebuilt} is present (write it)
     * or {@code refusals} is non-empty (write nothing).
     */
    record ResetDecision(Optional<StateRecords.UpdaterState> rebuilt, List<String> refusals) {
        ResetDecision {
            Objects.requireNonNull(rebuilt, "rebuilt");
            refusals = List.copyOf(refusals);
            if (rebuilt.isPresent() == !refusals.isEmpty()) {
                throw new IllegalArgumentException("a decision either rebuilds or refuses");
            }
        }

        static ResetDecision write(StateRecords.UpdaterState rebuilt) {
            return new ResetDecision(Optional.of(rebuilt), List.of());
        }

        static ResetDecision refuse(String... messages) {
            return new ResetDecision(Optional.empty(), List.of(messages));
        }
    }

    /**
     * Reads both files, asks {@code planner} for a decision and writes {@code state.json} only when
     * the decision rebuilds it. The read, the decision and the write all happen under one lock
     * acquisition, so a commit from another thread cannot land between them. The planner runs with
     * the state lock held and must not call any {@link StateStore} method.
     */
    ResetDecision resetLocked(ResetPlanner planner) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(planner, "planner");
        return withLocks(warnings -> {
            boolean any = anyFileExists();
            Optional<StateRecords.RevokedState> revoked = Optional.empty();
            Optional<StateRecords.UpdaterState> state = Optional.empty();
            if (any) {
                revoked = Optional.ofNullable(readFile(REVOKED, RevokedCodec::decode, warnings).value());
                state = Optional.ofNullable(readFile(STATE, StateCodec::decode, warnings).value());
            }
            ResetDecision decision = planner.plan(new ResetInput(any, state, revoked));
            if (decision.rebuilt().isPresent()) {
                atomicWrite(STATE, StateCodec.encode(decision.rebuilt().get()), StateStore::validState,
                        warnings);
            }
            return decision;
        });
    }

    /**
     * Raises {@code revocations.seq} to the trust floor and {@code minManifestSeq} to the manifest
     * floor. A raised seq invalidates the stored hash, so the seed form is used instead. Pure.
     */
    public static StateRecords.UpdaterState clampToTrust(
            StateRecords.UpdaterState s, UpdateDocuments.Trust trust) {
        Objects.requireNonNull(s, "state");
        Objects.requireNonNull(trust, "trust");
        StateRecords.CurrentRevocations r = s.revocations();
        long seq = Math.max(r.seq(), trust.revocationsSeqFloor());
        long minManifest = Math.max(r.minManifestSeq(), trust.manifestSeqFloor());
        if (seq == r.seq() && minManifest == r.minManifestSeq()) {
            return s;
        }
        StateRecords.CurrentRevocations raised;
        if (seq > r.seq() && !r.seed()) {
            raised = new StateRecords.CurrentRevocations(seq, "", null, null, minManifest);
        } else {
            raised = new StateRecords.CurrentRevocations(
                    seq, r.sha256(), r.issued(), r.expires(), minManifest);
        }
        return new StateRecords.UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(),
                raised, s.seqByKid(), s.withdrawn(), s.lastAnnounced(), s.warnedBad(),
                s.tornSince());
    }

    /**
     * Checks that {@code path} (not following links) is owned by {@code expected}.
     *
     * @throws UpdateRejectedException when the owner differs
     */
    static void requireOwner(Path path, UserPrincipal expected)
            throws IOException, UpdateRejectedException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (!owner.equals(expected)) {
            throw new UpdateRejectedException(OWNER_MISMATCH);
        }
    }

    // ---- locking -----------------------------------------------------------------------------

    /** Runs {@code op} with the in-process guard and the OS lock held. */
    private <T> T withLocks(Operation<T> op) throws IOException, UpdateRejectedException {
        if (IN_STATE_LOCK.get()) {
            throw new IllegalStateException("StateStore re-entered while the state lock is held");
        }
        prepareDirectory(dir);
        ReentrantLock guard = GUARDS.computeIfAbsent(dir.toRealPath(), k -> new ReentrantLock());
        // The in-process guard may wait for another thread in this JVM. Only the OS lock never waits.
        guard.lock();
        IN_STATE_LOCK.set(true);
        try {
            try (FileChannel channel = openLock(dir, LOCK)) {
                FileLock osLock;
                try {
                    osLock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    throw new StoreBusyException();
                }
                if (osLock == null) {
                    throw new StoreBusyException();
                }
                try {
                    List<String> warnings = new ArrayList<>();
                    try {
                        return op.run(warnings);
                    } finally {
                        lastWarnings = List.copyOf(warnings);
                    }
                } finally {
                    osLock.release();
                }
            }
        } finally {
            IN_STATE_LOCK.remove();
            guard.unlock();
        }
    }

    /** True when the current thread holds the state lock (inside a store callback). */
    static boolean holdsStateLock() {
        return IN_STATE_LOCK.get();
    }

    private interface Operation<T> {
        T run(List<String> warnings) throws IOException, UpdateRejectedException;
    }

    /** Opens (creating if needed) a lock file in {@code dir}, refusing links. */
    static FileChannel openLock(Path dir, String name) throws IOException {
        Path lock = dir.resolve(name);
        return FileChannel.open(lock,
                Set.<OpenOption>of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                fileAttributes());
    }

    // ---- directory and filesystem safety ------------------------------------------------------

    /**
     * Creates the directory if needed, refuses links, and requires that the directory and every
     * existing managed file belong to the user running this process. The process user is taken from
     * a probe file created here, so no attacker-planted file can supply the reference owner.
     */
    static void prepareDirectory(Path dir) throws IOException, UpdateRejectedException {
        if (Files.isSymbolicLink(dir)) {
            throw new UpdateRejectedException("update directory is a symbolic link");
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, directoryAttributes());
        }
        if (!Files.isDirectory(dir)) {
            throw new UpdateRejectedException("update directory is not a directory");
        }
        for (String name : managedNames()) {
            if (Files.isSymbolicLink(dir.resolve(name))) {
                throw new UpdateRejectedException("state file is a symbolic link");
            }
        }
        if (posixSupported()) {
            requireTightPermissions(dir);
            for (String name : managedNames()) {
                Path p = dir.resolve(name);
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    requireTightPermissions(p);
                }
            }
        }
        sweepStaleProbes(dir);
        Path probe = Files.createTempFile(dir, ".owner-", ".probe", fileAttributes());
        try {
            UserPrincipal self = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            requireOwner(dir, self);
            for (String name : managedNames()) {
                Path p = dir.resolve(name);
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                    requireOwner(p, self);
                }
            }
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Refuses any group or other permission bit on a POSIX file system. */
    private static void requireTightPermissions(Path p) throws IOException, UpdateRejectedException {
        for (PosixFilePermission perm : Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)) {
            if (perm.name().startsWith("GROUP_") || perm.name().startsWith("OTHERS_")) {
                throw new UpdateRejectedException("update directory permissions too open");
            }
        }
    }

    /**
     * Deletes probe files left behind by a crashed process. Files younger than a minute are kept, so
     * a concurrent process's probe is never removed underneath it.
     */
    private static void sweepStaleProbes(Path dir) throws IOException {
        Instant cutoff = Instant.now().minusSeconds(PROBE_STALE_SECONDS);
        try (DirectoryStream<Path> probes = Files.newDirectoryStream(dir, ".owner-*.probe")) {
            for (Path probe : probes) {
                deleteIfStale(probe, cutoff);
            }
        }
    }

    /**
     * Deletes one probe older than {@code cutoff}. The sweep runs before the in-process guard, so
     * another thread may delete its own fresh probe between the listing and the timestamp read; a
     * probe that vanished is simply gone.
     */
    static void deleteIfStale(Path probe, Instant cutoff) throws IOException {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(probe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(probe);
            }
        } catch (NoSuchFileException gone) {
            // Deleted concurrently by the thread that created it.
        }
    }

    static List<String> managedNames() {
        return List.of(STATE, STATE + PREV, REVOKED, REVOKED + PREV, LOCK, TXN_LOCK);
    }

    /** True when a state or revoked file (or its {@code .prev}) exists. The lock file does not count. */
    private boolean anyFileExists() {
        for (String name : managedNames()) {
            if (LOCK.equals(name) || TXN_LOCK.equals(name)) {
                continue;
            }
            if (Files.exists(dir.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    private static FileAttribute<?>[] directoryAttributes() {
        return posixAttributes("rwx------");
    }

    private static FileAttribute<?>[] fileAttributes() {
        return posixAttributes("rw-------");
    }

    private static FileAttribute<?>[] posixAttributes(String perms) {
        // Windows has no POSIX permission bits; the owner check is the protection there.
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms))
        };
    }

    // ---- reading and writing ------------------------------------------------------------------

    private interface Codec<T> {
        T decode(byte[] bytes) throws UpdateRejectedException;
    }

    /** A file value, or null when no valid copy exists; {@code mainValid} says the file itself is good. */
    private record Loaded<T>(T value, boolean mainValid) {
    }

    /** The loaded snapshot together with the validity of each main file. */
    private record Read(StateRecords.Snapshot snapshot, boolean stateMainValid,
            boolean revokedMainValid) {
    }

    private Read readSnapshot(List<String> warnings) throws IOException, UpdateRejectedException {
        Loaded<StateRecords.RevokedState> revoked = readFile(REVOKED, RevokedCodec::decode, warnings);
        Loaded<StateRecords.UpdaterState> state = readFile(STATE, StateCodec::decode, warnings);
        if (revoked.value() == null) {
            throw new StateUnavailableException(false,
                    "revoked.json has no valid copy; the updater is disabled until reset");
        }
        if (state.value() == null) {
            throw new StateUnavailableException(true,
                    "state.json has no valid copy; staging is disabled until reset");
        }
        return new Read(new StateRecords.Snapshot(state.value(), revoked.value()),
                state.mainValid(), revoked.mainValid());
    }

    /** Reads {@code name}, falling back to {@code name.prev} with a WARN when the main file is bad. */
    private <T> Loaded<T> readFile(String name, Codec<T> codec, List<String> warnings)
            throws IOException {
        byte[] current = readOptional(name);
        T value = tryDecode(codec, current);
        if (value != null) {
            return new Loaded<>(value, true);
        }
        byte[] prev = readOptional(name + PREV);
        T prevValue = tryDecode(codec, prev);
        String reason = current == null ? "missing" : "invalid";
        if (prevValue != null) {
            warnings.add(name + " " + reason + "; using " + name + PREV);
            return new Loaded<>(prevValue, false);
        }
        warnings.add(name + " " + reason + "; no valid " + name + PREV);
        return new Loaded<>(null, false);
    }

    private static <T> T tryDecode(Codec<T> codec, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return codec.decode(bytes);
        } catch (UpdateRejectedException e) {
            return null;
        }
    }

    /** Null when the file does not exist. Refuses links without following them. */
    private byte[] readOptional(String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(p)) {
            throw new IOException("state file is a symbolic link");
        }
        try (SeekableByteChannel ch = Files.newByteChannel(p, LinkOption.NOFOLLOW_LINKS)) {
            // One byte past the largest cap: a longer file is then rejected by the decoder.
            ByteBuffer buf = ByteBuffer.allocate(MAX_FILE_BYTES);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // keep reading until the cap or end of file
            }
            return Arrays.copyOf(buf.array(), buf.position());
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    static boolean validRevoked(byte[] bytes) {
        return tryDecode(RevokedCodec::decode, bytes) != null;
    }

    static boolean validState(byte[] bytes) {
        return tryDecode(StateCodec::decode, bytes) != null;
    }

    /**
     * Atomic replace of one file: write {@code name.tmp} and force it, copy the current file to
     * {@code name.prev} only when the current file is itself valid, move the temp over the target,
     * then fsync the directory. The new content must pass {@code validCurrent}, the same validator
     * used on reads, before anything is written, so an invalid encoding never reaches disk.
     *
     * <p>Package-private so tests can inject a bad encoding directly.
     */
    void atomicWrite(String name, byte[] content, Predicate<byte[]> validCurrent,
            List<String> warnings) throws IOException {
        if (!validCurrent.test(content)) {
            throw new IOException("refusing to write an invalid state file");
        }
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + TMP);
        atomicWriteHook.at(AtomicStep.TMP_WRITE_BEFORE, name);
        writeSynced(tmp, content);
        atomicWriteHook.at(AtomicStep.TMP_WRITE_AFTER, name);
        byte[] current = readOptional(name);
        if (current != null && validCurrent.test(current)) {
            Path prevTmp = dir.resolve(name + PREV + TMP);
            atomicWriteHook.at(AtomicStep.PREV_TMP_WRITE_BEFORE, name);
            writeSynced(prevTmp, current);
            atomicWriteHook.at(AtomicStep.PREV_TMP_WRITE_AFTER, name);
            atomicWriteHook.at(AtomicStep.PREV_RENAME_BEFORE, name);
            Files.move(prevTmp, dir.resolve(name + PREV), StandardCopyOption.ATOMIC_MOVE);
            atomicWriteHook.at(AtomicStep.PREV_RENAME_AFTER, name);
        }
        atomicWriteHook.at(AtomicStep.TARGET_RENAME_BEFORE, name);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        atomicWriteHook.at(AtomicStep.TARGET_RENAME_AFTER, name);
        syncDirectory(warnings);
    }

    private void writeSynced(Path path, byte[] content) throws IOException {
        try (FileChannel ch = FileChannel.open(path,
                Set.<OpenOption>of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                fileAttributes())) {
            ByteBuffer buf = ByteBuffer.wrap(content);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
    }

    /**
     * Fsyncs the directory after a rename. Only a failure to OPEN the directory is tolerated (some
     * platforms, such as Windows, cannot open one), and is recorded as a warning. A failing force()
     * is rethrown, so the caller sees the write as failed.
     */
    private void syncDirectory(List<String> warnings) throws IOException {
        FileChannel ch;
        try {
            ch = FileChannel.open(dir, StandardOpenOption.READ);
        } catch (IOException e) {
            warnings.add("directory fsync unsupported");
            return;
        }
        try (FileChannel directory = ch) {
            directoryForce.force(directory);
        }
    }
}
