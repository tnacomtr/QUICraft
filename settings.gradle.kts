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
        // Velocity API (velocity/ only).
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroupAndSubgroups("com.velocitypowered") }
        }
    }
}

rootProject.name = "quicraft"

include("core")
include("bridge-netty41")
include("bridge-netty42")
include("velocity")
include("testkit")
