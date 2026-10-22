// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes a gametest's one JSON document without ever overwriting existing evidence. */
final class GameTestJson {
    private GameTestJson() {}

    /**
     * Writes {@code doc} to {@code out}, refusing an existing name. The bytes go to a fresh
     * temporary sibling and a hard link publishes the complete file atomically; unlike a rename,
     * that cannot replace a file created after the existence check.
     */
    static void write(Path out, JsonObject doc) {
        String json = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(doc) + "\n";
        if (Files.exists(out)) {
            throw new AssertionError("refusing to overwrite " + out);
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        boolean ownsTmp = false;
        try {
            // One O_EXCL handle: fails if the name exists (symlink included), so ownership starts
            // here and the bytes go to the file this call created.
            try (SeekableByteChannel ch = Files.newByteChannel(tmp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ownsTmp = true;
                ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    ch.write(bytes);
                }
            }
            Files.createLink(out, tmp);
        } catch (IOException e) {
            throw new AssertionError("could not write " + out, e);
        } finally {
            try {
                if (ownsTmp) { // never remove a temp file someone else left there
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException ignored) {
                // the runner reports a leftover side file
            }
        }
    }
}
