// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

/**
 * Functional regression tests for the scope of the root SPDX header check.
 *
 * The check must keep covering every real source file while skipping agent worktrees
 * (the `.claude` and `.worktrees` directories), so a nested checkout of this repository
 * does not fail the outer build with "Missing SPDX header".
 */
@Execution(ExecutionMode.SAME_THREAD)
class SpdxScopeFunctionalTest {

    private val header = "SPDX-License-Identifier: GPL-3.0-or-later"

    @Test
    fun ignoredOpenCodeWorktreeSources(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir) { root ->
            writeLicensed(File(root, "module/src/Licensed.java"), "public class Licensed {}\n")
            writeHeaderless(File(root, ".worktrees/probe/src/Foreign.java"), "public class Foreign {}\n")
            writeHeaderless(File(root, ".worktrees/probe/build.gradle.kts"), "plugins {}\n")
            writeHeaderless(File(root, ".worktrees/probe/config.properties"), "probe=true\n")
        }

        val result = runCheck(fixtureDir)

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkSpdxHeaders")?.outcome, result.output)
        assertEquals("ok\n", File(fixtureDir, "build/spdx-check.ok").readText())
    }

    @Test
    fun ignoredClaudeWorktreeSources(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir) { root ->
            writeLicensed(File(root, "module/src/Licensed.java"), "public class Licensed {}\n")
            writeHeaderless(File(root, ".claude/worktrees/probe/Foreign.java"), "public class Foreign {}\n")
        }

        val result = runCheck(fixtureDir)

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkSpdxHeaders")?.outcome, result.output)
        assertEquals("ok\n", File(fixtureDir, "build/spdx-check.ok").readText())
    }

    @Test
    fun rejectsHeaderlessProjectSources(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir) { root ->
            writeHeaderless(File(root, "module/src/Headerless.java"), "public class Headerless {}\n")
            writeHeaderless(File(root, ".worktrees/probe/src/Foreign.java"), "public class Foreign {}\n")
            writeHeaderless(File(root, ".claude/worktrees/probe/ClaudeForeign.java"), "public class ClaudeForeign {}\n")
        }

        val result = runCheckExpectingFailure(fixtureDir)

        assertEquals(TaskOutcome.FAILED, result.task(":checkSpdxHeaders")?.outcome, result.output)
        val missing = missingFiles(result.output)
        assertTrue(
            missing.contains("module/src/Headerless.java"),
            "diagnostic must name the headerless project source, was: $missing",
        )
        assertFalse(missing.any { it.contains(".worktrees") }, "worktree files must not be reported, was: $missing")
        assertFalse(missing.any { it.contains(".claude") }, "claude worktree files must not be reported, was: $missing")
    }

    @Test
    fun nestedWorktreeDirInsideModuleIsStillChecked(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir) { root ->
            writeHeaderless(File(root, "module/.worktrees/X.java"), "public class X {}\n")
        }

        val result = runCheckExpectingFailure(fixtureDir)

        assertEquals(TaskOutcome.FAILED, result.task(":checkSpdxHeaders")?.outcome, result.output)
        val missing = missingFiles(result.output)
        assertTrue(
            missing.contains("module/.worktrees/X.java"),
            "the .worktrees/** exclude is root-anchored and must not skip a nested .worktrees dir, was: $missing",
        )
    }

    @Test
    fun nestedAgentSkillsDirIsStillChecked(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir) { root ->
            writeHeaderless(File(root, "module/agent_skills/Y.java"), "public class Y {}\n")
        }

        val result = runCheckExpectingFailure(fixtureDir)

        assertEquals(TaskOutcome.FAILED, result.task(":checkSpdxHeaders")?.outcome, result.output)
        val missing = missingFiles(result.output)
        assertTrue(
            missing.contains("module/agent_skills/Y.java"),
            "the agent_skills/** exclude is root-anchored and must not skip a nested agent_skills dir, was: $missing",
        )
    }

    private fun writeFixture(root: File, body: (File) -> Unit) {
        writeLicensed(File(root, "settings.gradle.kts"), "rootProject.name = \"spdx-scope-fixture\"\n")
        writeLicensed(File(root, "build.gradle.kts"), "plugins { id(\"quicraft.spdx\") }\n")
        body(root)
    }

    private fun writeLicensed(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText("// $header\n$content")
    }

    private fun writeHeaderless(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun runCheck(fixtureDir: File): BuildResult = runner(fixtureDir).build()

    private fun runCheckExpectingFailure(fixtureDir: File): BuildResult = runner(fixtureDir).buildAndFail()

    private fun runner(fixtureDir: File): GradleRunner =
        GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withPluginClasspath()
            .withArguments("checkSpdxHeaders", "--offline", "--stacktrace")

    /** Extracts the relative paths listed after "header in:" in the task's failure diagnostic. */
    private fun missingFiles(output: String): List<String> {
        val marker = "header in:"
        val start = output.indexOf(marker)
        if (start < 0) return emptyList()
        return output.substring(start + marker.length)
            .lines()
            .map { it.trim().replace('\\', '/') }
            .dropWhile { it.isEmpty() }
            .takeWhile { it.isNotEmpty() }
    }
}
