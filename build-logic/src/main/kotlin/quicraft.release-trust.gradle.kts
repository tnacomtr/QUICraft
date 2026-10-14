// SPDX-License-Identifier: GPL-3.0-or-later
//
// Root-project release guard (docs/updater.md 12.2). checkReleaseTrust fails when the configured
// trust file is the development file, holds an RFC 8032 TEST key, or when the project version is
// outside the UpdateVersion grammar (a SNAPSHOT build is never updatable).
//
// Standalone: no release or publish task exists in the build (checked Oct 2026: none in any
// build script). It is deliberately NOT a dependency of build, check or assemble, because normal
// builds use the development trust file. Any future release or publish task must depend on it.
val devTrustPath = layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH).asFile.canonicalPath

val releaseTrustFile = providers.gradleProperty("quicraft.updaterTrustFile")
    .map { layout.projectDirectory.file(it) }
    .orElse(layout.projectDirectory.file(UpdaterTrust.DEV_TRUST_PATH))

tasks.register("checkReleaseTrust") {
    group = "verification"
    description = "Release guard (standalone; no release task exists yet): fails on the development " +
        "trust file, RFC 8032 test keys, or a project version outside the UpdateVersion grammar."
    val version = project.version.toString()
    // Locals, not script vals: the action must not capture the script object (configuration cache).
    val trustSource = releaseTrustFile
    val devPath = devTrustPath
    inputs.file(trustSource)
    inputs.property("version", version)
    doLast {
        val file = trustSource.get().asFile
        val problems = mutableListOf<String>()
        if (file.canonicalPath == devPath) {
            problems += "the trust file is the development file (${UpdaterTrust.DEV_TRUST_PATH})"
        }
        val text = file.readText(Charsets.UTF_8)
        for (pub in UpdaterTrust.TEST_PUBS) {
            if (text.contains(pub)) problems += "the trust file holds the RFC 8032 test key $pub"
        }
        if (!UpdaterTrust.isUpdaterVersion(version)) {
            problems += "project version '$version' is outside the UpdateVersion grammar"
        }
        if (problems.isNotEmpty()) {
            throw GradleException("checkReleaseTrust failed: " + problems.joinToString("; "))
        }
        println("checkReleaseTrust passed: ${file.name}, version $version")
    }
}
