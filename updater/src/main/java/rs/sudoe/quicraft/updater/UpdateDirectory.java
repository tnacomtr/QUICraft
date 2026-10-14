// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-disk layout of one platform's update directory below the state files (docs/updater.md
 * 12.8, 12.10): {@code staging/<txid>.part|.jar}, {@code backup/<txid>.jar} and {@code journal.json}.
 *
 * <p>Every name is built from a validated 32-hex txid. Every open uses {@code NOFOLLOW_LINKS}. Every
 * durable step goes through the injected {@link DurabilityOps}, so tests can fail any boundary.
 *
 * <p><b>Directory sync.</b> A directory that cannot be opened for sync (for example on Windows) is
 * recorded as the warning {@value #DIRECTORY_SYNC_WARNING} and the step continues; a failing
 * {@code force} always propagates. {@link #directorySyncSupported()} tells the caller whether atomic
 * replacement is possible; the caller must refuse it when this is false.
 *
 * <p><b>Failure after a rename.</b> When {@code syncDirectory} throws after a successful rename
 * ({@link #commitPart}, {@link #writeJournal}, {@link #moveOverLive}), the rename may already have
 * happened. The caller must re-read the on-disk state (for example {@link #readJournal}) before it
 * acts, and must not assume the rename failed.
 *
 * <p><b>Cleanup order.</b> When a transaction ends, delete the staging files first
 * ({@link #deleteStaging}), then {@code journal.json} ({@link #deleteJournal}). A crash between the two
 * leaves a journal that recovery can match to a known txid, rather than staging files that no journal
 * refers to. The backup of the transaction recorded in the current journal must never be deleted
 * ({@link #deleteBackup}); only backups of earlier transactions may be.
 */
public final class UpdateDirectory {

    /** Warning recorded when the directory cannot be opened for sync. Fixed text, shared with StateStore. */
    public static final String DIRECTORY_SYNC_WARNING = "directory fsync unsupported";

    static final String STAGING = "staging";
    static final String BACKUP = "backup";
    static final String JOURNAL = "journal.json";
    private static final String TMP = ".tmp";
    private static final String PART = ".part";
    private static final String JAR = ".jar";
    private static final int TXID_BYTES = 16;
    private static final int COPY_BUFFER = 65536;
    private static final Pattern TXID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern STAGING_NAME = Pattern.compile("([0-9a-f]{32})\\.(part|jar)");
    private static final Pattern BACKUP_NAME = Pattern.compile("([0-9a-f]{32})\\.jar");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Path staging;
    private final Path backup;
    private final DurabilityOps ops;
    private final UserPrincipal owner;
    private final List<String> warnings = new ArrayList<>();

    /**
     * Prepares the directory tree: refuses links and foreign owners (via {@link StateStore}), then
     * creates {@code staging/} and {@code backup/} as {@code 0700} if missing.
     *
     * @throws UpdateRejectedException when the directory fails the link or owner rules
     */
    public UpdateDirectory(Path updateDir, DurabilityOps ops) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(updateDir, "updateDir");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.dir = updateDir.toAbsolutePath().normalize();
        StateStore.prepareDirectory(dir);
        this.owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
        this.staging = dir.resolve(STAGING);
        this.backup = dir.resolve(BACKUP);
        prepareSubdirectory(staging);
        prepareSubdirectory(backup);
    }

    /** A fresh random txid: 32 lowercase hex characters from {@link SecureRandom}. */
    public static String newTxid() {
        byte[] raw = new byte[TXID_BYTES];
        RANDOM.nextBytes(raw);
        return Hex.encode(raw);
    }

    public Path stagingPart(String txid) {
        return staging.resolve(requireTxid(txid) + PART);
    }

    public Path stagingJar(String txid) {
        return staging.resolve(requireTxid(txid) + JAR);
    }

    public Path backupJar(String txid) {
        return backup.resolve(requireTxid(txid) + JAR);
    }

    public Path journal() {
        return dir.resolve(JOURNAL);
    }

    /** True when this platform can sync directories, so atomic replacement may be offered. */
    public boolean directorySyncSupported() {
        return ops.directorySyncSupported(dir);
    }

    /** Warnings recorded so far by this instance (a copy). Currently only {@link #DIRECTORY_SYNC_WARNING}. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Creates {@code staging/<txid>.part} with {@code CREATE_NEW}, {@code NOFOLLOW_LINKS}, {@code 0600}. */
    public FileChannel createPart(String txid) throws IOException {
        return FileChannel.open(stagingPart(txid),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                privateFile());
    }

    /**
     * Makes a finished part durable and moves it to its jar name: force and close the channel,
     * atomically rename {@code .part} to {@code .jar}, then sync {@code staging/}.
     *
     * <p>If the directory sync throws after the rename, the jar is at its final name but the
     * rename's durability is unconfirmed; see the class note on failure after a rename.
     */
    public void commitPart(String txid, FileChannel ch) throws IOException {
        try (FileChannel c = ch) {
            ops.force(c);
        }
        ops.atomicMove(stagingPart(txid), stagingJar(txid));
        syncDir(staging);
    }

    /**
     * Copies the permission bits of the live jar onto the staged file just before the move. A
     * failing implementation is swallowed by {@link #moveOverLive(String, Path, PermissionCopier)}:
     * permissions are best effort.
     */
    @FunctionalInterface
    interface PermissionCopier {
        void copy(Path live, Path staged) throws IOException;
    }

    /**
     * Atomically moves the verified staged jar of {@code txid} over the live jar at {@code livePath},
     * then syncs the live jar's directory.
     *
     * <p>Immediately before the move, the live jar's POSIX permission bits are copied onto the staged
     * file, so a plugin or mod jar that was readable by panels, deploy users or backup tools stays
     * readable after the swap. The copy is best effort and never fails the swap: on a file system
     * without the {@code posix} view, or when reading or setting the bits fails, the live jar keeps
     * the staged file's {@code 0600}. The staging area stays private until this moment. Owner and
     * group are never changed: an unprivileged process cannot do that, and the rename keeps the
     * owner of the staging directory's files (the server user).
     *
     * <p>If the directory sync throws, the move may already have happened. The caller must check the
     * live jar's hash before deciding anything (see the class note on failure after a rename).
     *
     * @throws IllegalArgumentException if {@code livePath} is not absolute or has no parent
     */
    public void moveOverLive(String txid, Path livePath) throws IOException {
        moveOverLive(txid, livePath, UpdateDirectory::copyPermissions);
    }

    /**
     * Test seam at the best-effort permission copy: {@code copier} runs on the staged file just
     * before it is moved over the live jar, and a failure thrown by it never fails the swap.
     * Production passes {@link #copyPermissions} through {@link #moveOverLive(String, Path)}.
     */
    void moveOverLive(String txid, Path livePath, PermissionCopier copier) throws IOException {
        Objects.requireNonNull(livePath, "livePath");
        Objects.requireNonNull(copier, "copier");
        if (!livePath.isAbsolute() || livePath.getParent() == null) {
            throw new IllegalArgumentException("live path must be absolute");
        }
        try {
            copier.copy(livePath, stagingJar(txid));
        } catch (IOException | RuntimeException ignored) {
            // Permissions are best effort (see the public overload): keep the staged 0600 and go on.
            // Nothing is logged: the swap itself is not degraded, only less convenient afterwards.
        }
        ops.atomicMove(stagingJar(txid), livePath);
        syncDir(livePath.getParent());
    }

    /**
     * Copies the live jar to {@code backup/<txid>.jar} ({@code CREATE_NEW}, {@code NOFOLLOW_LINKS}),
     * forces it, syncs {@code backup/}, re-hashes the copy and returns its SHA-512 (lowercase hex).
     * The caller compares that value with the live jar's hash. A failed copy is deleted; a backup
     * that already exists is never touched.
     */
    public String copyBackup(String txid, Path liveJar) throws IOException {
        Objects.requireNonNull(liveJar, "liveJar");
        Path target = backupJar(txid);
        // CREATE_NEW fails here when a backup already exists; that failure must not delete it.
        FileChannel dst = FileChannel.open(target,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                privateFile());
        boolean ok = false;
        try {
            try (FileChannel src = FileChannel.open(liveJar,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER);
                while (src.read(buf) >= 0) {
                    buf.flip();
                    while (buf.hasRemaining()) {
                        dst.write(buf);
                    }
                    buf.clear();
                }
            }
            ops.force(dst);
            ok = true;
        } finally {
            dst.close();
            if (!ok) {
                Files.deleteIfExists(target);
            }
        }
        syncDir(backup);
        return sha512Of(target);
    }

    /**
     * Reads {@code journal.json}. Returns empty when no journal exists, and also when a journal
     * that existed was deleted between the existence check and the read: the on-disk state is then
     * the same as no journal at all. Refuses a symbolic link, a foreign owner and any file over
     * {@link JournalCodec#MAX_BYTES}; decode errors propagate.
     */
    public Optional<JournalRecord> readJournal() throws IOException, UpdateRejectedException {
        return readJournal(p -> { });
    }

    /**
     * Test seam at the journal read: {@code atReadWindow} runs in the two windows in which a
     * concurrent {@link #deleteJournal} can remove {@code journal.json} between the existence check
     * and the read, once after the file is known to exist and to be a regular file and before its
     * owner is checked, and again after the owner check and before the open. Production passes a
     * no-op through {@link #readJournal()}.
     */
    Optional<JournalRecord> readJournal(Consumer<Path> atReadWindow)
            throws IOException, UpdateRejectedException {
        Path p = journal();
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateRejectedException("journal is not a regular file");
        }
        byte[] bytes;
        try {
            atReadWindow.accept(p);
            StateStore.requireOwner(p, owner);
            atReadWindow.accept(p);
            try (FileChannel ch = FileChannel.open(p, Set.of(StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS))) {
                bytes = readAtMost(ch, JournalCodec.MAX_BYTES + 1);
            }
        } catch (NoSuchFileException gone) {
            // The journal was deleted after the existence check, by the owner check or by the open.
            // That is the same on-disk state as no journal at all, so report it as absent rather
            // than as a read failure. Only this exception is treated this way: a journal replaced
            // by a link makes the NOFOLLOW_LINKS open fail with a different exception, which still
            // propagates, and every other check still rejects.
            return Optional.empty();
        }
        return Optional.of(JournalCodec.decode(bytes));
    }

    /**
     * Writes {@code journal.json} atomically: write {@code journal.json.tmp}, force, rename over the
     * target, sync the directory. A failure before the rename leaves the previous journal intact.
     * No {@code .prev} copy is kept for the journal.
     *
     * <p>If the directory sync throws after the rename, the new journal may already be in place. The
     * caller must re-read it with {@link #readJournal} before acting. A failure while removing the
     * temporary file after an earlier failure is attached as suppressed and never replaces the original
     * exception.
     */
    public void writeJournal(JournalRecord record) throws IOException {
        byte[] bytes = JournalCodec.encode(record);
        Path tmp = dir.resolve(JOURNAL + TMP);
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    privateFile())) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ops.force(ch);
            }
            ops.atomicMove(tmp, journal());
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        syncDir(dir);
    }

    /**
     * Deletes a stale {@code journal.json.tmp} left by a crash during {@link #writeJournal}. Syncs the
     * directory only when a file was removed. Never touches {@code journal.json} itself.
     */
    public void deleteJournalTmp() throws IOException {
        if (Files.deleteIfExists(dir.resolve(JOURNAL + TMP))) {
            syncDir(dir);
        }
    }

    /**
     * Refuses any symbolic link, and any entry not owned by the server user, in the directory,
     * {@code staging/} and {@code backup/} (docs/updater.md 12.8). Read-only; the caller decides
     * whether the updater disables itself.
     *
     * <p>A top-level file that is already gone when its owner is checked is skipped: {@link
     * StateStore#prepareDirectory} creates and removes a {@code .owner-*.probe} file in the same
     * directory, so another thread of this process can remove a listed entry. Every other failure,
     * including a present entry with a foreign owner, a missing {@code staging/} or {@code backup/},
     * or a file that vanishes inside them, still rejects.
     *
     * @throws UpdateRejectedException on the first link or foreign owner found
     */
    public void requireOwnedContents() throws IOException, UpdateRejectedException {
        requireOwnedContents(p -> { });
    }

    /**
     * Test seam at the ownership scan: {@code afterListed} runs for every listed entry between the
     * directory listing and that entry's checks, so a test can remove the entry to reproduce the
     * concurrent-probe race. Production passes a no-op through {@link #requireOwnedContents()}.
     */
    void requireOwnedContents(Consumer<Path> afterListed) throws IOException, UpdateRejectedException {
        for (Path base : List.of(dir, staging, backup)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                for (Path p : stream) {
                    afterListed.accept(p);
                    if (Files.isSymbolicLink(p)) {
                        throw new UpdateRejectedException("update file is a symbolic link");
                    }
                    try {
                        StateStore.requireOwner(p, owner);
                    } catch (NoSuchFileException gone) {
                        // Only top-level files vanish legitimately (a concurrent prepareDirectory probe, a
                        // state-store temp file). A missing staging/ or backup/, or a file inside them,
                        // still fails closed.
                        if (base != dir || p.equals(staging) || p.equals(backup)) {
                            throw gone;
                        }
                    }
                }
            }
        }
    }

    /**
     * Deletes {@code journal.json} if present and syncs the directory. Call only after
     * {@link #deleteStaging} for the same transaction (see the class note on cleanup order).
     */
    public void deleteJournal() throws IOException {
        if (Files.deleteIfExists(journal())) {
            syncDir(dir);
        }
    }

    /**
     * Deletes the part and jar of one transaction. Missing files are ignored. Call this before
     * {@link #deleteJournal} (see the class note on cleanup order).
     */
    public void deleteStaging(String txid) throws IOException {
        Files.deleteIfExists(stagingPart(txid));
        Files.deleteIfExists(stagingJar(txid));
    }

    /**
     * Deletes {@code backup/<txid>.jar}. A missing backup is ignored. A symbolic link or any other
     * non-regular entry is refused with {@link IOException} and left in place. Syncs {@code backup/}
     * after a deletion.
     *
     * <p><b>Callers must never delete the backup of the txid recorded in the current journal.</b> That
     * backup is the rollback copy of the running generation; only backups of earlier transactions may
     * be deleted.
     */
    public void deleteBackup(String txid) throws IOException {
        Path p = backupJar(txid);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("backup is not a regular file");
        }
        Files.delete(p);
        syncDir(backup);
    }

    /** Txids that have a regular file named {@code <txid>.part} or {@code <txid>.jar} in {@code staging/}. */
    public List<String> stagingTxids() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        for (Path p : listDirectory(staging)) {
            Matcher m = STAGING_NAME.matcher(p.getFileName().toString());
            if (m.matches() && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Entries in {@code staging/} whose names do not match the fixed patterns, or that are not regular files. */
    public List<Path> unexpectedStagingEntries() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : listDirectory(staging)) {
            Matcher m = STAGING_NAME.matcher(p.getFileName().toString());
            if (!m.matches() || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Txids that have a regular file named {@code <txid>.jar} in {@code backup/}. */
    public List<String> backupTxids() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        for (Path p : listDirectory(backup)) {
            Matcher m = BACKUP_NAME.matcher(p.getFileName().toString());
            if (m.matches() && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Entries in {@code backup/} whose names do not match {@code <txid>.jar}, or that are not regular files. */
    public List<Path> unexpectedBackupEntries() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : listDirectory(backup)) {
            Matcher m = BACKUP_NAME.matcher(p.getFileName().toString());
            if (!m.matches() || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** True when the live jar and {@code staging/} are on the same file store (ATOMIC_MOVE is possible). */
    public boolean sameFileStore(Path liveJar) throws IOException {
        return Files.getFileStore(liveJar).equals(Files.getFileStore(staging));
    }

    /**
     * SHA-512 of a regular file, opened with {@code NOFOLLOW_LINKS}, as lowercase hex.
     *
     * @throws IOException if the path is a link or any other non-regular file
     */
    static String sha512Of(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file");
        }
        MessageDigest md = sha512();
        try (FileChannel ch = FileChannel.open(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER);
            while (ch.read(buf) >= 0) {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
        }
        return Hex.encode(md.digest());
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Syncs a directory through the seam; records the fixed warning when the directory cannot be opened. */
    private void syncDir(Path d) throws IOException {
        if (!ops.syncDirectory(d) && !warnings.contains(DIRECTORY_SYNC_WARNING)) {
            warnings.add(DIRECTORY_SYNC_WARNING);
        }
    }

    private static List<Path> listDirectory(Path base) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path p : stream) {
                out.add(p);
            }
        }
        out.sort(null);
        return out;
    }

    private void prepareSubdirectory(Path sub) throws IOException, UpdateRejectedException {
        if (Files.isSymbolicLink(sub)) {
            throw new UpdateRejectedException("update subdirectory is a symbolic link");
        }
        if (!Files.exists(sub, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(sub, privateDirectory());
        }
        if (!Files.isDirectory(sub, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateRejectedException("update subdirectory is not a directory");
        }
        StateStore.requireOwner(sub, owner);
    }

    private static String requireTxid(String txid) {
        if (txid == null || !TXID.matcher(txid).matches()) {
            throw new IllegalArgumentException("invalid txid");
        }
        return txid;
    }

    private static byte[] readAtMost(FileChannel ch, int limit) throws IOException, UpdateRejectedException {
        ByteBuffer buf = ByteBuffer.allocate(limit);
        while (buf.hasRemaining() && ch.read(buf) >= 0) {
            // keep reading until the limit or end of file
        }
        if (!buf.hasRemaining()) {
            throw new UpdateRejectedException("invalid journal");
        }
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private static FileAttribute<?>[] privateFile() {
        return posix("rw-------");
    }

    private static FileAttribute<?>[] privateDirectory() {
        return posix("rwx------");
    }

    private static FileAttribute<?>[] posix(String perms) {
        // Windows has no POSIX permission bits; the owner check is the protection there.
        if (!posixSupported()) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms))
        };
    }

    private static boolean posixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Copies the live jar's POSIX permission bits onto the staged file, so the swapped-in jar keeps
     * the access it had before. Returns without doing anything when the default file system has no
     * {@code posix} view, or when {@code live} is not a regular file (a symbolic link would give its
     * own wide bits, not the jar's), which leaves the staged file private. Owner and group are
     * deliberately not touched.
     */
    private static void copyPermissions(Path live, Path staged) throws IOException {
        if (!posixSupported() || !Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.setPosixFilePermissions(staged,
                Files.getPosixFilePermissions(live, LinkOption.NOFOLLOW_LINKS));
    }
}
