// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Process and host numbers read from procfs, for the load harness's STAT lines. */
final class ProcStats {
    private ProcStats() {}

    /** VmRSS of this process, in MiB. */
    static long rssMb() {
        return statusKb("VmRSS") / 1024;
    }

    /** Thread count of this process. */
    static long threads() {
        return statusKb("Threads");
    }

    /** utime + stime of this process, in clock ticks (CLK_TCK). */
    static long cpuTicks() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.US_ASCII);
            // Fields after the parenthesised comm: index 0 is field 3 (state), so utime (14) is 11.
            String[] rest = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** MemAvailable of the host, in KiB. */
    static long memAvailableKb() {
        return meminfoKb("MemAvailable");
    }

    private static long statusKb(String key) {
        return field(Path.of("/proc/self/status"), key);
    }

    private static long meminfoKb(String key) {
        return field(Path.of("/proc/meminfo"), key);
    }

    /** The first number on the line starting with {@code key:}; -1 if unreadable. */
    private static long field(Path file, String key) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
            for (String line : lines) {
                if (line.startsWith(key + ":")) {
                    return Long.parseLong(line.substring(key.length() + 1).trim().split("\\s+")[0]);
                }
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return -1;
    }
}
