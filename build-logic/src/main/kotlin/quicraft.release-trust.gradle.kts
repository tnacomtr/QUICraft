// SPDX-License-Identifier: GPL-3.0-or-later
//
// Root-project release guard (docs/updater.md 12.2). checkReleaseTrust fails when the configured
// trust file is the development file, holds an RFC 8032 TEST key, or when the project version is
// outside the UpdateVersion grammar (a SNAPSHOT build is never updatable). It also inspects the
// built outer jars (velocity, fabric-26x, fabric-1.21.11): their META-INF/quicraft entries must
// name the right platform and version and must not carry a TEST key. Setting quicraft.allowDevTrust
// at all fails it: that opt-in builds TEST-key jars for local tests only, never a release.
//
// Standalone: no release or publish task exists in the build (checked Oct 2026: none in any
// build script). It is deliberately NOT a dependency of build, check or assemble, because normal
// builds use the development trust file. Any future release or publish task must depend on it.
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.zip.ZipFile

val devTrustPath = layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH).asFile.canonicalPath

val releaseTrustFile = providers.gradleProperty("quicraft.updaterTrustFile")
    .map { layout.projectDirectory.file(it) }
    .orElse(layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH))

// The outer jar per platform; must match quicraft.updater-trust.gradle.kts. Resolved lazily: the
// subprojects are not evaluated yet when this root script applies the plugin.
val outerJars: List<Pair<String, Provider<AbstractArchiveTask>>> = listOf(
    Triple("velocity", ":velocity", "shadowJar"),
    Triple("fabric-26x", ":fabric-26x", "jar"),
    Triple("fabric-1.21.11", ":fabric-1.21.11", "remapJar"),
).map { (platformName, path, taskName) ->
    Pair(platformName, providers.provider { project(path).tasks.named<AbstractArchiveTask>(taskName).get() })
}

tasks.register("checkReleaseTrust") {
    group = "verification"
    description = "Release guard (standalone; no release task exists yet): fails on the development " +
        "trust file, RFC 8032 test keys, quicraft.allowDevTrust, a project version outside the " +
        "UpdateVersion grammar, or test keys and mismatched metadata in the built outer jars."
    val version = project.version.toString()
    // Locals, not script vals: the action must not capture the script object (configuration cache).
    val trustSource = releaseTrustFile
    val devPath = devTrustPath
    val devOptIn = providers.gradleProperty("quicraft.allowDevTrust").map { true }.orElse(false)
    val jars = outerJars.map { (platformName, jar) -> Pair(platformName, jar.flatMap { it.archiveFile }) }
    inputs.file(trustSource)
    inputs.property("version", version)
    inputs.property("allowDevTrust", devOptIn)
    jars.forEach { inputs.file(it.second) }
    dependsOn(outerJars.map { it.second })
    doLast {
        val file = trustSource.get().asFile
        val problems = mutableListOf<String>()
        if (devOptIn.get()) {
            problems += "quicraft.allowDevTrust is set; it is for local TEST jars, never a release"
        }
        if (file.canonicalPath == devPath) {
            problems += "the trust file is the development file (${UpdaterTrust.DEV_TRUST_PATH})"
        }
        try {
            for (pub in UpdaterTrust.testPubsIn(file.readText(Charsets.UTF_8))) {
                problems += "the trust file holds the RFC 8032 test key $pub"
            }
        } catch (e: IllegalArgumentException) {
            problems += "the trust file cannot be decoded safely: ${e.message}"
        }
        if (!UpdaterTrust.isUpdaterVersion(version)) {
            problems += "project version '$version' is outside the UpdateVersion grammar"
        }
        for ((platformName, archive) in jars) {
            val jarFile = archive.get().asFile
            try {
                ZipFile(jarFile).use { zip ->
                    fun single(entry: String): ByteArray {
                        val matches = java.util.Collections.list(zip.entries()).filter { it.name == entry }
                        if (matches.size != 1) {
                            throw GradleException("${jarFile.name}: expected exactly one $entry, found ${matches.size}")
                        }
                        return zip.getInputStream(matches[0]).use { it.readBytes() }
                    }
                    val build = single(UpdaterTrust.BUILD_ENTRY)
                    if (!build.contentEquals(UpdaterTrust.buildJson(platformName, version).toByteArray(Charsets.UTF_8))) {
                        problems += "${jarFile.name}: ${UpdaterTrust.BUILD_ENTRY} is not {platform=$platformName, version=$version}"
                    }
                    val jarTrust = single(UpdaterTrust.TRUST_ENTRY).toString(Charsets.UTF_8)
                    for (pub in UpdaterTrust.testPubsIn(jarTrust)) {
                        problems += "${jarFile.name}: ${UpdaterTrust.TRUST_ENTRY} holds the RFC 8032 test key $pub"
                    }
                }
            } catch (e: IllegalArgumentException) {
                problems += "${jarFile.name}: ${UpdaterTrust.TRUST_ENTRY} cannot be decoded safely: ${e.message}"
            } catch (e: GradleException) {
                problems += e.message ?: "${jarFile.name}: invalid jar metadata"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("checkReleaseTrust failed: " + problems.joinToString("; "))
        }
        println("checkReleaseTrust passed: ${file.name}, version $version, ${jars.size} outer jars")
    }
}
