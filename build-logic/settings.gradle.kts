// SPDX-License-Identifier: GPL-3.0-or-later
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        // Fabric Loom (fabric-*/ modules).
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
            content { includeGroupAndSubgroups("net.fabricmc") }
        }
    }
}

rootProject.name = "build-logic"
