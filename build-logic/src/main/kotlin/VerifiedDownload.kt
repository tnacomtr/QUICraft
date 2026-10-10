// SPDX-License-Identifier: GPL-3.0-or-later
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Downloads a file that isn't on a Maven repository (e.g. the Velocity proxy jar, which we
 * compile against for its internals) and fails unless it matches the pinned sha256, like the
 * testkit images do.
 */
@CacheableTask
abstract class VerifiedDownload : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != sha256.get().lowercase()) {
            throw GradleException("sha256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        outputFile.get().asFile.writeBytes(bytes)
    }
}
