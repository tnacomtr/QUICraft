// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // MCProtocolLib (testkit only).
        maven("https://repo.opencollab.dev/maven-releases/") {
            content {
                includeGroupAndSubgroups("org.geysermc")
                includeGroupAndSubgroups("org.cloudburstmc")
                includeGroup("com.nukkitx.fastutil")
            }
        }
    }
}

rootProject.name = "quicraft"

include("core")
include("testkit")
