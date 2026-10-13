// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-disk layout of one platform's update directory below the state files (docs/updater.md
 * 12.8, 12.10): {@code staging/<txid>.part|.jar}, {@code backup/<txid>.jar} and {@code journal.json}.
 *
 * <p>Every name is built from a validated 32-hex txid. Every open uses {@code NOFOLLOW_LINKS}. Every
 * durable step goes through the injected {@link DurabilityOps}, so tests can fail any boundary.
 * Methods never swallow a durability failure.
 */
public final class UpdateDirectory {

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
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Path staging;
    private final Path backup;
    private final DurabilityOps ops;
    private final UserPrincipal owner;

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
     */
    public void commitPart(String txid, FileChannel ch) throws IOException {
        try (FileChannel c = ch) {
            ops.force(c);
        }
        ops.atomicMove(stagingPart(txid), stagingJar(txid));
        ops.syncDirectory(staging);
    }

    /**
     * Copies the live jar to {@code backup/<txid>.jar} ({@code CREATE_NEW}, {@code NOFOLLOW_LINKS}),
     * forces it, syncs {@code backup/}, re-hashes the copy and returns its SHA-512 (lowercase hex).
     * The caller compares that value with the live jar's hash. A failed copy is deleted.
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
        ops.syncDirectory(backup);
        return sha512Of(target);
    }

    /**
     * Reads {@code journal.json}. Returns empty when no journal exists. Refuses a symbolic link, a
     * foreign owner and any file over {@link JournalCodec#MAX_BYTES}; decode errors propagate.
     */
    public Optional<JournalRecord> readJournal() throws IOException, UpdateRejectedException {
        Path p = journal();
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new UpdateRejectedException("journal is not a regular file");
        }
        StateStore.requireOwner(p, owner);
        byte[] bytes;
        try (FileChannel ch = FileChannel.open(p, Set.of(StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))) {
            bytes = readAtMost(ch, JournalCodec.MAX_BYTES + 1);
        }
        return Optional.of(JournalCodec.decode(bytes));
    }

    /**
     * Writes {@code journal.json} atomically: write {@code journal.json.tmp}, force, rename over the
     * target, sync the directory. A failure before the rename leaves the previous journal intact.
     * No {@code .prev} copy is kept for the journal.
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
            Files.deleteIfExists(tmp);
            throw e;
        }
        ops.syncDirectory(dir);
    }

    /**
     * Refuses any symbolic link, and any entry not owned by the server user, in the directory,
     * {@code staging/} and {@code backup/} (docs/updater.md 12.8). Read-only; the caller decides
     * whether the updater disables itself.
     *
     * @throws UpdateRejectedException on the first link or foreign owner found
     */
    public void requireOwnedContents() throws IOException, UpdateRejectedException {
        for (Path base : List.of(dir, staging, backup)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                for (Path p : stream) {
                    if (Files.isSymbolicLink(p)) {
                        throw new UpdateRejectedException("update file is a symbolic link");
                    }
                    StateStore.requireOwner(p, owner);
                }
            }
        }
    }

    /** Deletes {@code journal.json} if present and syncs the directory. */
    public void deleteJournal() throws IOException {
        Files.deleteIfExists(journal());
        ops.syncDirectory(dir);
    }

    /** Deletes the part and jar of one transaction. Missing files are ignored. */
    public void deleteStaging(String txid) throws IOException {
        Files.deleteIfExists(stagingPart(txid));
        Files.deleteIfExists(stagingJar(txid));
    }

    /** Txids that have at least one entry named {@code <txid>.part} or {@code <txid>.jar} in {@code staging/}. */
    public List<String> stagingTxids() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        for (Path p : listStaging()) {
            Matcher m = STAGING_NAME.matcher(p.getFileName().toString());
            if (m.matches() && !Files.isSymbolicLink(p)) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Entries in {@code staging/} whose names do not match the fixed patterns, or that are links. */
    public List<Path> unexpectedStagingEntries() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : listStaging()) {
            Matcher m = STAGING_NAME.matcher(p.getFileName().toString());
            if (!m.matches() || Files.isSymbolicLink(p)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** True when the live jar and {@code staging/} are on the same file store (ATOMIC_MOVE is possible). */
    public boolean sameFileStore(Path liveJar) throws IOException {
        return Files.getFileStore(liveJar).equals(Files.getFileStore(staging));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private List<Path> listStaging() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(staging)) {
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

    /** SHA-512 of a file, opened with {@code NOFOLLOW_LINKS}, as lowercase hex. */
    private static String sha512Of(Path file) throws IOException {
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
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms))
        };
    }
}
