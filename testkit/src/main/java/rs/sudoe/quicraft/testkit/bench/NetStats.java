// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kernel counters from {@code /proc/net/snmp} for this network namespace (the bench container's):
 * UDP datagrams in, UDP drops because the socket receive buffer was full, and TCP
 * retransmissions. Empty where the file doesn't exist.
 */
final class NetStats {
    private static final String[][] WANTED = {
        {"Udp", "InDatagrams"}, {"Udp", "RcvbufErrors"}, {"Udp", "InErrors"}, {"Tcp", "RetransSegs"},
    };

    private NetStats() {}

    static Map<String, Long> read() {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/net/snmp"));
            for (int i = 0; i + 1 < lines.size(); i += 2) {
                String[] names = lines.get(i).split("\\s+");
                String[] values = lines.get(i + 1).split("\\s+");
                String proto = names[0].replace(":", "");
                for (String[] w : WANTED) {
                    if (!w[0].equals(proto)) {
                        continue;
                    }
                    for (int k = 1; k < names.length && k < values.length; k++) {
                        if (names[k].equals(w[1])) {
                            out.put(proto + w[1], Long.parseLong(values[k]));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not Linux, or no procfs: no counters.
        }
        return out;
    }

    static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> out = new LinkedHashMap<>();
        after.forEach((k, v) -> out.put(k, v - before.getOrDefault(k, 0L)));
        return out;
    }
}
