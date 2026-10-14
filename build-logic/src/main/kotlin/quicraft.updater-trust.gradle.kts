// SPDX-License-Identifier: GPL-3.0-or-later
//
// Server jars (Velocity, Fabric 26.x and 1.21.11) carry META-INF/quicraft/build.json and
// META-INF/quicraft/updater-trust.json in the OUTER jar: the one the updater swaps (docs/updater.md,
// build metadata; 12.2). The project name is the platform name. The trust file defaults to the
// development file; a release passes -Pquicraft.updaterTrustFile=<path> (checkReleaseTrust refuses
// the development file and the RFC 8032 test keys).
//
// Configuration-cache rule: task actions must not capture script-level values (a script val is a
// field of the script object, which cannot be serialized). Every action copies what it needs into
// a local first.
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.zip.ZipFile

val platform: String = project.name
require(platform in UpdaterTrust.PLATFORMS) {
    "${project.path} is not an updater platform (expected one of ${UpdaterTrust.PLATFORMS})"
}
val buildVersion: String = project.version.toString()

// Resolved against the repository root so the same -P value works from any module.
val trustFile = providers.gradleProperty("quicraft.updaterTrustFile")
    .map { rootProject.layout.projectDirectory.file(it) }
    .orElse(rootProject.layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH))

val metadataDir = layout.buildDirectory.dir("generated/updater-metadata")

// Fail closed (fix 1): an updatable version (UpdateVersion grammar) with a development trust, the
// dev file or any RFC 8032 TEST key, does not package. The only way through is the opt-in below,
// which exists for a release-shaped TEST jar used by local tests. The documented command is
// -Pversion=0.1.0-alpha.1 -Pquicraft.allowDevTrust=true (velocity/build.gradle.kts).
val devTrustFile: File = rootProject.layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH).asFile
val allowDevTrust = providers.gradleProperty("quicraft.allowDevTrust").map { it == "true" }.orElse(false)

val generateUpdaterMetadata = tasks.register("generateUpdaterMetadata") {
    group = "build"
    description = "Writes META-INF/quicraft/build.json and copies the configured trust file. Refuses a " +
        "development trust for an updatable version unless -Pquicraft.allowDevTrust=true."
    val platformName = platform
    val projectPath = project.path
    val versionName = buildVersion
    val trustSource = trustFile
    val devSource = devTrustFile
    val devOptIn = allowDevTrust
    val outDir = metadataDir
    inputs.property("platform", platformName)
    inputs.property("version", versionName)
    inputs.property("allowDevTrust", devOptIn)
    inputs.file(trustSource)
    outputs.dir(outDir)
    doLast {
        val trustFileNow = trustSource.get().asFile
        if (UpdaterTrust.isUpdaterVersion(versionName) && !devOptIn.get()) {
            val development = try {
                UpdaterTrust.isDevelopmentTrust(trustFileNow, devSource)
            } catch (e: IllegalArgumentException) {
                throw GradleException("$projectPath: the trust file $trustFileNow cannot be decoded safely " +
                    "(${e.message}); an updatable version needs a valid release trust file")
            }
            if (development) {
                throw GradleException(
                    "$projectPath: version $versionName is updatable but the trust file $trustFileNow is a " +
                        "development trust (${UpdaterTrust.DEV_TRUST_PATH} or RFC 8032 TEST keys). A release " +
                        "needs -Pquicraft.updaterTrustFile=<release trust>. A release-shaped TEST jar for " +
                        "local tests needs -Pquicraft.allowDevTrust=true.",
                )
            }
        }
        val dir = outDir.get().asFile.resolve("META-INF/quicraft")
        dir.mkdirs()
        dir.resolve("build.json").writeText(UpdaterTrust.buildJson(platformName, versionName), Charsets.UTF_8)
        trustSource.get().asFile.copyTo(dir.resolve("updater-trust.json"), overwrite = true)
    }
}

// Known only after evaluation: the Loom plugins apply after this one in some modules.
afterEvaluate {
    val outerJarName = when {
        platform == "velocity" -> "shadowJar"
        plugins.hasPlugin("net.fabricmc.fabric-loom-remap") -> "remapJar"
        else -> "jar"
    }
    val outer = tasks.named<Jar>(outerJarName) {
        from(generateUpdaterMetadata)
    }
    val outerArchive = outer.flatMap { it.archiveFile }

    val checkUpdaterJarMetadata = tasks.register("checkUpdaterJarMetadata") {
        group = "verification"
        description = "Fails unless the outer jar has exactly one build.json (this platform and " +
            "version) and exactly one updater-trust.json equal to the configured trust file."
        val platformName = platform
        val versionName = buildVersion
        val trustSource = trustFile
        dependsOn(outer)
        inputs.file(outerArchive)
        inputs.file(trustSource)
        inputs.property("platform", platformName)
        inputs.property("version", versionName)
        doLast {
            val jar = outerArchive.get().asFile
            val expectedBuild = UpdaterTrust.buildJson(platformName, versionName).toByteArray(Charsets.UTF_8)
            val trustPath = trustSource.get().asFile
            val expectedTrust = trustPath.readBytes()
            ZipFile(jar).use { zip ->
                fun single(entry: String): ByteArray {
                    val count = java.util.Collections.list(zip.entries()).count { it.name == entry }
                    if (count != 1) {
                        throw GradleException("${jar.name}: expected exactly one $entry, found $count")
                    }
                    return zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                }
                if (!single(UpdaterTrust.BUILD_ENTRY).contentEquals(expectedBuild)) {
                    throw GradleException("${jar.name}: ${UpdaterTrust.BUILD_ENTRY} is not " +
                        "{platform=$platformName, version=$versionName}")
                }
                if (!single(UpdaterTrust.TRUST_ENTRY).contentEquals(expectedTrust)) {
                    throw GradleException("${jar.name}: ${UpdaterTrust.TRUST_ENTRY} differs from " +
                        "the configured trust file $trustPath")
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(checkUpdaterJarMetadata)
    }
}
