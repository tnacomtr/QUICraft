// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

/**
 * Functional tests for the development-trust policy of generateUpdaterMetadata
 * (quicraft.updater-trust.gradle.kts). A minimal fixture named "velocity" applies the plugin with
 * the shadow plugin and its own copy of the development trust file, so no real module is built.
 *
 * The last case proves the opt-in is a task input: the same build directory, with nothing but the
 * opt-in removed, fails again instead of reusing the earlier success.
 */
@Execution(ExecutionMode.SAME_THREAD)
class UpdaterMetadataFunctionalTest {

    private val devMarker = "is a development trust"

    @Test
    fun snapshotWithDevTrustSucceeds(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir)
        val result = run(fixtureDir, "-Pversion=0.1.0-SNAPSHOT")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateUpdaterMetadata")?.outcome, result.output)
    }

    @Test
    fun updatableVersionWithDevTrustFailsAndOptInSucceeds(@TempDir fixtureDir: File) {
        writeFixture(fixtureDir)

        val refused = runExpectingFailure(fixtureDir, "-Pversion=0.1.0-alpha.1")
        assertEquals(TaskOutcome.FAILED, refused.task(":generateUpdaterMetadata")?.outcome, refused.output)
        assertTrue(refused.output.contains(devMarker), refused.output)

        val allowed = run(fixtureDir, "-Pversion=0.1.0-alpha.1", "-Pquicraft.allowDevTrust=true")
        assertEquals(TaskOutcome.SUCCESS, allowed.task(":generateUpdaterMetadata")?.outcome, allowed.output)

        // Same build directory, only the opt-in removed: the task must run again and refuse.
        val again = runExpectingFailure(fixtureDir, "-Pversion=0.1.0-alpha.1")
        assertEquals(TaskOutcome.FAILED, again.task(":generateUpdaterMetadata")?.outcome, again.output)
        assertTrue(again.output.contains(devMarker), again.output)
    }

    private fun writeFixture(root: File) {
        File(root, "settings.gradle.kts").apply { parentFile.mkdirs() }
            .writeText("// SPDX-License-Identifier: GPL-3.0-or-later\nrootProject.name = \"velocity\"\n")
        File(root, "gradle.properties").writeText(
            "# SPDX-License-Identifier: GPL-3.0-or-later\nversion=0.1.0-SNAPSHOT\n",
        )
        File(root, "build.gradle.kts").writeText(
            "// SPDX-License-Identifier: GPL-3.0-or-later\n" +
                "plugins {\n    java\n    id(\"com.gradleup.shadow\")\n    id(\"quicraft.updater-trust\")\n}\n",
        )
        // The development trust file lives where the policy looks for it (the fixture root).
        // A copy of the repository's file, so the fixture holds the same RFC 8032 TEST keys.
        val devSource = File("../updater/dev-trust/updater-trust.json")
        assertTrue(devSource.isFile, "the development trust file must exist at ${devSource.absolutePath}")
        File(root, "updater/dev-trust").mkdirs()
        devSource.copyTo(File(root, "updater/dev-trust/updater-trust.json"))
    }

    private fun run(fixtureDir: File, vararg extra: String): BuildResult =
        runner(fixtureDir, extra).build()

    private fun runExpectingFailure(fixtureDir: File, vararg extra: String): BuildResult =
        runner(fixtureDir, extra).buildAndFail()

    private fun runner(fixtureDir: File, extra: Array<out String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withPluginClasspath()
            .withArguments(listOf("generateUpdaterMetadata", "--offline") + extra)
}
