// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The write discipline the pin and re-bind state files share (docs/protocol.md 12.15): write a
 * temporary file in the same directory, then move it over the target atomically, so a reader never
 * sees a half-written file. The temporary file is created owner-only ({@code rw-------}) before
 * anything is written to it where the file system supports POSIX permissions, and the move carries
 * those permissions onto the target; where POSIX is not supported the permissions are silently
 * skipped, exactly as 12.15 allows.
 *
 * <p>A caller that gets an {@link IOException} must treat the state as unusable: a partial write
 * never leaves a truncated file, but it does mean the new state was not stored.</p>
 */
final class StateFile {
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private StateFile() {
    }

    /**
     * Replaces {@code file} with {@code content}, creating its directory when it is missing.
     *
     * @throws IOException when the content could not be stored; {@code file} is then unchanged
     */
    static void write(Path file, byte[] content) throws IOException {
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temporary = createTemporary(directory, target.getFileName().toString());
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same directory, so a plain replacement is still a rename; it is just not promised
                // to be atomic against a crash on this file system.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
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
