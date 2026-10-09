// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linux TCP counters from this network namespace ({@code /proc/net/snmp}, {@code /proc/net/netstat}).
 * The bench runs one connection at a time, so deltas around a run belong to that run.
 */
final class TcpCounters {
    private static final Set<String> KEEP = Set.of("RetransSegs", "TCPFastRetrans", "TCPSlowStartRetrans",
            "TCPLostRetransmit", "TCPSACKReorder", "TCPTSReorder", "TCPDSACKUndo", "TCPLossUndo",
            "TCPSpuriousRTOs", "TCPSpuriousRtxHostQueues", "TCPDSACKRecv", "TCPTimeouts");

    private TcpCounters() {}

    static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        read(Path.of("/proc/net/snmp"), out);
        read(Path.of("/proc/net/netstat"), out);
        return out;
    }

    static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> d = new LinkedHashMap<>();
        after.forEach((k, v) -> d.put(k, v - before.getOrDefault(k, 0L)));
        return d;
    }

    private static void read(Path file, Map<String, Long> out) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i + 1 < lines.size(); i += 2) {
                String[] names = lines.get(i).split("\\s+");
                String[] values = lines.get(i + 1).split("\\s+");
                if (!names[0].startsWith("Tcp")) {
                    continue;
                }
                for (int j = 1; j < names.length && j < values.length; j++) {
                    if (KEEP.contains(names[j])) {
                        out.put(names[j], Long.parseLong(values[j]));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // not Linux or not readable: no counters
        }
    }
}
