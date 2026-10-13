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
 * exercise each boundary. A failure always propagates; callers decide what it means.
 */
public interface DurabilityOps {

    /** Flushes file content and metadata to disk. */
    void force(FileChannel ch) throws IOException;

    /** Renames {@code from} over {@code to} atomically, or fails. Never falls back to a copy. */
    void atomicMove(Path from, Path to) throws IOException;

    /** Flushes a directory so that the names created or renamed in it are durable. */
    void syncDirectory(Path dir) throws IOException;

    /** The real implementation. {@link #syncDirectory} throws when the directory cannot be synced. */
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
        public void syncDirectory(Path dir) throws IOException {
            try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
                ch.force(true);
            }
        }
    }
}
