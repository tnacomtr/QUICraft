// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty

/** Fails if a source file lacks `SPDX-License-Identifier: GPL-3.0-or-later` in its first lines. */
@CacheableTask
abstract class SpdxHeaderCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val baseDir: DirectoryProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val header = "SPDX-License-Identifier: GPL-3.0-or-later"
        val root = baseDir.get().asFile
        val missing = sources.files
            .filter { file -> file.useLines { lines -> lines.take(5).none { it.contains(header) } } }
            .map { it.relativeTo(root).path }
            .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException("Missing '$header' header in:\n  " + missing.joinToString("\n  "))
        }
        marker.get().asFile.writeText("ok\n")
    }
}
