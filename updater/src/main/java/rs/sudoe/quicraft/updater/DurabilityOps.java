// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The durability and rename primitives behind every update file write (docs/updater.md 12.8, 12.10).
 * Production code uses {@link #system()}; tests inject failing or recording implementations to
 * exercise each boundary.
 *
 * <p>A failure of {@link #force} or of an atomic move always propagates. For {@link #syncDirectory}
 * only a failure to <em>open</em> the directory is tolerated (reported as {@code false}); a failing
 * force on an opened directory propagates.
 */
public interface DurabilityOps {

    /** Flushes file content and metadata to disk. */
    void force(FileChannel ch) throws IOException;

    /** Renames {@code from} over {@code to} atomically, or fails. Never falls back to a copy. */
    void atomicMove(Path from, Path to) throws IOException;

    /**
     * Flushes a directory so that the names created or renamed in it are durable.
     *
     * @return {@code true} when the directory was synced; {@code false} when it cannot be opened for
     *     sync on this platform (for example Windows). Any other failure is thrown.
     */
    boolean syncDirectory(Path dir) throws IOException;

    /**
     * Whether {@link #syncDirectory} can open {@code dir} for sync. The default probes by opening the
     * directory; tests may stub it.
     */
    default boolean directorySyncSupported(Path dir) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The real implementation. */
    static DurabilityOps system() {
        return SystemOps.INSTANCE;
    }

    /** Production implementation. */
    final class SystemOps implements DurabilityOps {
        static final SystemOps INSTANCE = new SystemOps();

        private SystemOps() {
        }

        @Override
        public void force(FileChannel ch) throws IOException {
            ch.force(true);
        }

        @Override
        public void atomicMove(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public boolean syncDirectory(Path dir) throws IOException {
            FileChannel ch;
            try {
                ch = FileChannel.open(dir, StandardOpenOption.READ);
            } catch (IOException unsupported) {
                // Only the open is tolerated: some platforms refuse to open a directory at all.
                return false;
            }
            try (FileChannel directory = ch) {
                directory.force(true);
            }
            return true;
        }
    }
}
