// SPDX-License-Identifier: GPL-3.0-or-later
//
// Per-module setup lives in build-logic (quicraft.java-conventions applies the license
// check to every module); the root only checks SPDX headers across the whole tree.
plugins {
    id("quicraft.spdx")
    id("quicraft.release-trust")
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

// Release guard (protocol.md 13.13): the legacy 3-lane session keeps an 8 KiB window on server-opened
// reverse CHUNK streams. A release must ship the 1-byte window, so a non-SNAPSHOT build fails while the
// legacy flag is on. Remove this clause together with the flag.
val checkReleaseGuards by tasks.registering {
    val transportConfig = layout.projectDirectory.file(
        "core/src/main/java/rs/sudoe/quicraft/core/transport/TransportConfig.java")
    val releaseVersion = providers.gradleProperty("version")
    inputs.file(transportConfig)
    inputs.property("version", releaseVersion)
    doLast {
        val version = releaseVersion.get()
        val legacy = Regex("""LEGACY_LANE_SESSION\s*=\s*true""").containsMatchIn(transportConfig.asFile.readText())
        if (legacy && !version.endsWith("-SNAPSHOT")) {
            throw GradleException(
                "release $version still has TransportConfig.LEGACY_LANE_SESSION = true (8 KiB reverse CHUNK window); " +
                    "restore the 1-byte window (13.13) before releasing")
        }
    }
}

tasks.named("check") {
    dependsOn(checkReleaseGuards)
}
