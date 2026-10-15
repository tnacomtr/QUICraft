// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import rs.sudoe.quicraft.core.Faults;

/**
 * The write discipline the pin and re-bind state files share (docs/protocol.md 12.15): write a
 * temporary file in the same directory, flush its data to the platform, then move it over the target
 * atomically and flush the directory, so a reader never sees a half-written file and a crash cannot
 * lose the new state while keeping the old. The temporary file is created owner-only
 * ({@code rw-------}) before anything is written to it where the file system supports POSIX
 * permissions, and the move carries those permissions onto the target; where POSIX is not supported
 * the permissions are silently skipped, exactly as 12.15 allows.
 *
 * <p>Atomic replacement is required, not preferred: when the file system reports
 * {@code AtomicMoveNotSupportedException} the write fails and the state is disabled rather than
 * falling back to a plain replacement a reader could catch half-written. The directory flush is
 * skipped on Windows, which cannot open a directory for reading; everywhere else a failure to flush
 * is a write failure too. That flush happens after the rename, so a failure there leaves the new
 * content already in place while the write is reported as failed and the caller disables the state:
 * fail closed, the file is not read again in this process and is never overwritten, and the state on
 * disk is the new one rather than a mixture.</p>
 *
 * <p>A caller that gets an {@link IOException} (or a {@link RuntimeException} from the fault
 * injection of {@link #FAULT_FORCE} and {@link #FAULT_MOVE}) must treat the state as unusable: it
 * either left the old content in place (a failure before the rename, or a rename that failed) or the
 * new content in place but unproven (a failed directory flush), and never a partial write.</p>
 */
final class StateFile {
    /**
     * Fault injection stage for the project's {@link Faults}: flushing the temporary file's data
     * fails, as if the platform refused it.
     */
    static final String FAULT_FORCE = "pin state force";
    /** Fault injection stage for the project's {@link Faults}: the atomic replacement fails. */
    static final String FAULT_MOVE = "pin state move";

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private StateFile() {
    }

    /**
     * Replaces {@code file} with {@code content}, creating its directory when it is missing.
     *
     * @throws IOException when the content could not be stored durably; {@code file} is then either
     *     its previous content or absent, never a partial write
     */
    static void write(Path file, byte[] content) throws IOException {
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temporary = createTemporary(directory, target.getFileName().toString());
        try {
            writeAndFlush(temporary, content);
            Faults.check(FAULT_MOVE);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The content of {@code file}, at most {@code maxBytes} long.
     *
     * @throws NoSuchFileException when the file is not there, the only failure a caller that treats
     *     a missing state as empty may swallow
     * @throws IOException when the path is not a regular file (a directory, say), cannot be read
     *     (permissions, I/O) or is larger than {@code maxBytes}
     */
    static byte[] read(Path file, long maxBytes) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("state file is not a regular file: " + file);
        }
        if (attributes.size() > maxBytes) {
            throw new IOException("state file is larger than " + maxBytes + " bytes: " + file);
        }
        return Files.readAllBytes(file);
    }

    private static void writeAndFlush(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            Faults.check(FAULT_FORCE);
            channel.force(true);
        }
    }

    /**
     * Flushes the directory entry the rename created, where the platform allows it: Windows cannot
     * open a directory for reading, so there the rename is atomic but the entry itself is not
     * forced, while on every other platform a failure here means the write is not durable.
     */
    private static void forceDirectory(Path directory) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Path createTemporary(Path directory, String name) throws IOException {
        String prefix = name.length() < 3 ? "state-" + name : name;
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            FileAttribute<Set<PosixFilePermission>> attributes =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY);
            try {
                return Files.createTempFile(directory, prefix, ".tmp", attributes);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                // The file system says it has POSIX views but will not take these permissions.
            }
        }
        return Files.createTempFile(directory, prefix, ".tmp");
    }
}
