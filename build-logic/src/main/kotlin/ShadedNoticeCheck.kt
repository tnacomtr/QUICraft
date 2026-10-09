// SPDX-License-Identifier: GPL-3.0-or-later
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * CLAUDE.md "Notices in shaded jars": the shaded jar must carry every licence and notice file
 * from the jars it bundles, byte for byte. Also fails if two bundled jars ship different content
 * under the same path, because then one copy would silently be lost.
 */
@CacheableTask
abstract class ShadedNoticeCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shadedJar: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = sortedMapOf<String, String>()
        val problems = mutableListOf<String>()
        for (jar in sourceJars.files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }) {
            for ((path, hash) in notices(jar)) {
                val previous = expected.putIfAbsent(path, hash)
                if (previous != null && previous != hash) {
                    problems += "$path differs between bundled jars (seen again in ${jar.name}); merge it explicitly"
                }
            }
        }
        if (expected.isEmpty()) {
            problems += "no licence/notice files found in the bundled jars; is the source set wrong?"
        }
        val actual = notices(shadedJar.get().asFile)
        for ((path, hash) in expected) {
            when (actual[path]) {
                null -> problems += "missing from shaded jar: $path"
                hash -> {}
                else -> problems += "content differs in shaded jar: $path"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Shaded jar licence/notice check failed:\n  " + problems.joinToString("\n  "))
        }
        marker.get().asFile.writeText(expected.keys.joinToString("\n", postfix = "\n"))
    }

    private fun notices(jar: java.io.File): Map<String, String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && isNotice(it.name) }
            .associate { entry ->
                val digest = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(entry).readBytes())
                entry.name to digest.joinToString("") { "%02x".format(it) }
            }
    }

    private fun isNotice(path: String): Boolean {
        if (!path.startsWith("META-INF/") || path == "META-INF/LICENSE_quicraft.txt") return false
        val upper = path.uppercase()
        return path.startsWith("META-INF/license/") || "LICENSE" in upper || "NOTICE" in upper
    }
}
