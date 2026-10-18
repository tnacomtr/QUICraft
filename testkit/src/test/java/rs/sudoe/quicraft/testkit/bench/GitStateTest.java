// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bench scripts must not record a clean commit when tracked changes are staged. The check lives
 * in {@code testkit/lib/git-state.sh}; this drives it against a real temporary repository.
 */
class GitStateTest {
    @TempDir
    Path dir;

    @Test
    void stagedAndUnstagedTrackedChangesAreDirty() throws Exception {
        assumeTrue(available("bash") && available("git"), "needs bash and git");
        Path script = script();
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);

        git(repo, "init", "-q");
        git(repo, "config", "user.email", "bench@example.invalid");
        git(repo, "config", "user.name", "Bench Test");
        Files.writeString(repo.resolve("tracked.txt"), "one\n", StandardCharsets.UTF_8);
        git(repo, "add", "tracked.txt");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "first");

        String head = git(repo, "rev-parse", "HEAD").trim();
        String shortHead = git(repo, "rev-parse", "--short", "HEAD").trim();
        assertEquals(shortHead, shell(script, repo, "git_state"));
        assertEquals(head, shell(script, repo, "git_build_commit"));

        Files.writeString(repo.resolve("tracked.txt"), "two\n", StandardCharsets.UTF_8);
        assertEquals(shortHead + " (dirty)", shell(script, repo, "git_state"));
        assertEquals(head + "-dirty", shell(script, repo, "git_build_commit"));

        // A staged change alone is dirty. The previous `git diff --quiet` check missed it.
        git(repo, "add", "tracked.txt");
        assertEquals(shortHead + " (dirty)", shell(script, repo, "git_state"));
        assertEquals(head + "-dirty", shell(script, repo, "git_build_commit"));

        // Untracked files do not change the build, so they do not mark it dirty.
        Files.writeString(repo.resolve("untracked.txt"), "x\n", StandardCharsets.UTF_8);
        git(repo, "reset", "-q", "--hard", "HEAD");
        assertEquals(shortHead, shell(script, repo, "git_state"));
        assertEquals(head, shell(script, repo, "git_build_commit"));
    }

    private static Path script() {
        for (String candidate : List.of("lib/git-state.sh", "testkit/lib/git-state.sh")) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("testkit/lib/git-state.sh not found from " + Path.of("").toAbsolutePath());
    }

    private static String shell(Path script, Path repo, String function) throws Exception {
        Process process = new ProcessBuilder("bash", "-c",
                "source \"$1\" && " + function + " \"$2\"", "_", script.toString(), repo.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bash timed out");
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git timed out");
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static boolean available(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
