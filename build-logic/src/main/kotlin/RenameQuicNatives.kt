// SPDX-License-Identifier: GPL-3.0-or-later
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Extracts the quiche natives from the netty-codec-native-quic jars and renames them for a
 * relocated Netty: `libnetty_quiche42_linux_x86_64.so` becomes
 * `lib<prefix>netty_quiche42_linux_x86_64.so` (no `lib` on Windows). Netty's loader looks for
 * exactly that name, derived from the relocated package (docs/protocol.md §11).
 *
 * Also copies the licence and notice files: in Netty 4.2 only the native jars carry them
 * (Netty, BoringSSL, quiche and the rest), so the shaded jar gets them from here.
 * ShadedNoticeCheck verifies none is lost.
 */
@CacheableTask
abstract class RenameQuicNatives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nativeJars: ConfigurableFileCollection

    /** Mangled prefix, e.g. `rs_sudoe_quicraft_shaded_`. */
    @get:Input
    abstract val prefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun rename() {
        val mangled = prefix.get()
        fs.sync {
            into(outputDir)
            nativeJars.files.forEach { from(archives.zipTree(it)) }
            include("META-INF/native/*netty_quiche42*")
            include("META-INF/LICENSE.txt", "META-INF/NOTICE.txt", "META-INF/license/**")
            rename("^(lib)?netty_quiche42_", "$1${mangled}netty_quiche42_")
            // Identical in every native jar; ShadedNoticeCheck fails if they ever differ.
            duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        }
        val count = outputDir.get().dir("META-INF/native").asFile.listFiles()?.size ?: 0
        if (count != nativeJars.files.size) {
            throw org.gradle.api.GradleException("expected ${nativeJars.files.size} natives, found $count")
        }
    }
}
