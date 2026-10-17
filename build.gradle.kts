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

// Release guard: a non-SNAPSHOT build must not ship a legacy or unsafe transport setting. The clause
// that failed on TransportConfig.LEGACY_LANE_SESSION (the legacy 3-lane session's 8 KiB reverse CHUNK
// window, 13.13) was removed together with the legacy session; new release guards belong in this task.
val checkReleaseGuards by tasks.registering {
    doLast {
        // No release clause remains after the legacy 3-lane session removal.
    }
}

tasks.named("check") {
    dependsOn(checkReleaseGuards)
}
