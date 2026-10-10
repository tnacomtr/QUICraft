// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        // QUICraft's patched Netty QUIC build (natives/build-linux.sh, never committed). This group
        // comes only from here, and nothing else does.
        exclusiveContent {
            forRepository {
                maven(file("natives/build/out/repo")) { name = "quicraftNettyQuic" }
            }
            filter { includeGroup("rs.sudoe.quicraft.netty") }
        }
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
