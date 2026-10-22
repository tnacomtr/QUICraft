// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit expected cross-product and attempted artifacts. Never discovers evidence by glob. */
final class RunManifest {
    record Key(String profile, String arm, int round) {}
    record Attempt(Key key, String status, Path path) {}
    final Map<Key, Attempt> expected = new LinkedHashMap<>();
    final Set<Key> passed = new HashSet<>();
    final Map<Path, Attempt> files = new LinkedHashMap<>();

    static RunManifest read(Path manifest, Path dir, List<String> profiles, List<String> arms) throws IOException {
        List<String> lines = Files.readAllLines(manifest);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("#")) {
                String[] parts = line.substring(1).split("\t", -1);
                if (parts.length != 2 || headers.putIfAbsent(parts[0], parts[1]) != null)
                    throw new IllegalArgumentException("invalid manifest header: " + line);
            }
        }
        int rounds = Integer.parseInt(headers.getOrDefault("rounds", "0"));
        if (rounds < 1 || rounds > 10000
                || !List.of(headers.getOrDefault("profiles", "").trim().split("\\s+")).equals(profiles)
                || !List.of(headers.getOrDefault("arms", "").trim().split("\\s+")).equals(arms))
            throw new IllegalArgumentException("manifest expected set does not match requested matrix");
        RunManifest result = new RunManifest();
        for (String profile : profiles) for (String arm : arms) for (int round = 0; round < rounds; round++)
            result.expected.put(new Key(profile, arm, round), null);
        Set<Path> paths = new HashSet<>();
        Path base = dir.toAbsolutePath().normalize();
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            if (p.length != 5) throw new IllegalArgumentException("invalid manifest row: " + line);
            Key key = new Key(p[0], p[1], Integer.parseInt(p[2]));
            Path path = base.resolve(p[4]).normalize();
            if (!result.expected.containsKey(key) || result.expected.get(key) != null
                    || !Set.of("ok", "failed").contains(p[3]) || p[4].isBlank()
                    || !path.startsWith(base) || !paths.add(path)
                    || !path.toString().endsWith(".json") || path.toString().endsWith(".status.json"))
                throw new IllegalArgumentException("invalid/duplicate manifest attempt: " + line);
            Attempt attempt = new Attempt(key, p[3], path);
            result.expected.put(key, attempt);
            if (p[3].equals("ok") && Files.isRegularFile(path)) result.files.put(path, attempt);
        }
        return result;
    }

    boolean matches(Path file, String profile, RunResult run, String variant) {
        Attempt attempt = files.get(file);
        String arm = run.transportVersion() == TransportVersion.TCP ? "tcp"
                : run.transportVersion() == TransportVersion.QUIC_2 ? "quic2"
                : variant.equals("busy") ? "quic3-busy" : "quic3";
        return attempt != null && attempt.key.equals(new Key(profile, arm, run.run()));
    }

    boolean complete() { return passed.size() == expected.size(); }

    String render(String profile, String arm, String value) {
        boolean complete = expected.keySet().stream().filter(k -> k.profile.equals(profile) && k.arm.equals(arm))
                .allMatch(passed::contains);
        return complete ? value : "INCOMPLETE (" + value + ")";
    }

    void append(StringBuilder md) {
        md.append("\n| Profile | Arm | Expected | Attempted | Passed | Failed | Missing | Status |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Set<String> cells = new java.util.LinkedHashSet<>();
        for (Key key : expected.keySet()) cells.add(key.profile + "\t" + key.arm);
        for (String cell : cells) {
            int total = 0, attempted = 0, ok = 0, failed = 0, missing = 0;
            for (var entry : expected.entrySet()) {
                Key key = entry.getKey();
                if (!(key.profile + "\t" + key.arm).equals(cell)) continue;
                total++;
                Attempt attempt = entry.getValue();
                if (attempt != null) attempted++;
                if (passed.contains(key)) ok++;
                else if (attempt == null || (attempt.status.equals("ok") && !Files.isRegularFile(attempt.path))) missing++;
                else failed++;
            }
            md.append("| ").append(cell.replace("\t", " | ")).append(" | ").append(total)
                    .append(" | ").append(attempted).append(" | ").append(ok).append(" | ").append(failed)
                    .append(" | ").append(missing).append(" | ").append(ok == total ? "COMPLETE" : "INCOMPLETE")
                    .append(" |\n");
        }
    }
}
