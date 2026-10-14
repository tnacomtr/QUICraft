// SPDX-License-Identifier: GPL-3.0-or-later
//
// Offline release-metadata tool (docs/updater.md blocker 15): it composes release.v1.json,
// revocations.v1.json and their detached *.sig files, and re-checks every pair with the updater's
// own parser and verifier code. It never touches a private key: the owner signs the exact payload
// bytes by hand with OpenSSL and hands the raw or hex signature back.
//
// Never shipped: no other module depends on it, so no shadow/release jar picks it up. The
// `application` plugin supplies the JavaExec `run` task; nothing depends on `distZip`/`distTar`
// outside `assemble` of this module.
import org.gradle.api.tasks.JavaExec

plugins {
    id("quicraft.java-conventions")
    application
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    implementation(project(":updater"))
}

application {
    mainClass = "rs.sudoe.quicraft.releasetool.ReleaseTool"
    applicationName = "quicraft-release-tool"
}

// Every default path (the trust file updater/release-trust/updater-trust.json) and any path the
// owner passes on the command line is relative to the repository root, so `:release-tool:run`
// runs there rather than in release-tool/.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
