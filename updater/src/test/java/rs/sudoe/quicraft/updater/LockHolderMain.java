// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Test helper run in a child JVM: takes the OS lock on state.lock in the given directory, prints
 * "locked", and holds it until stdin reaches end of file.
 */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] args) throws IOException {
        Path lock = Path.of(args[0]).resolve("state.lock");
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock held = ch.lock();
            System.out.println("locked");
            System.out.flush();
            System.in.readAllBytes();
            held.release();
        }
    }
}
