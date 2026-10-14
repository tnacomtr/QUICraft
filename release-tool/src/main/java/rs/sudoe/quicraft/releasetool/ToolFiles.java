// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small file helpers shared by the subcommands. */
final class ToolFiles {

    private ToolFiles() {
    }

    /**
     * Writes {@code bytes} to {@code file}, creating missing parent directories. Nothing else is
     * touched: an existing file is replaced, never deleted first.
     *
     * @throws ToolException if the path has no file name or the write fails
     */
    static void write(Path file, byte[] bytes) throws ToolException {
        if (file.getFileName() == null) {
            throw new ToolException("--out must name a file: " + file);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new ToolException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Reads the whole file, failing with the path in the message. */
    static byte[] read(Path file) throws ToolException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Reads at most {@code cap + 1} bytes and fails when the file exceeds {@code cap}. */
    static byte[] readCapped(Path file, int cap) throws ToolException {
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(cap + 1);
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + ": " + e.getMessage(), e);
        }
        if (bytes.length > cap) {
            throw new ToolException(file + " exceeds the " + cap + " byte limit");
        }
        return bytes;
    }

    /** UTF-8 bytes of {@code text}. */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
