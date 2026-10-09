// SPDX-License-Identifier: GPL-3.0-or-later
// Bridge for games and platforms on Netty 4.2 (MC 1.21.11, 26.x, Velocity 4.2, testkit).
plugins {
    id("quicraft.bridge-conventions")
}

dependencies {
    compileOnly(libs.netty42.transport)
    testImplementation(libs.netty42.transport)
}
