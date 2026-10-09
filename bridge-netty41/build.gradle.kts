// SPDX-License-Identifier: GPL-3.0-or-later
// Bridge for games and platforms on Netty 4.1 (MC 1.20.1, 1.21–1.21.10).
plugins {
    id("quicraft.bridge-conventions")
}

dependencies {
    compileOnly(libs.netty41.transport)
    testImplementation(libs.netty41.transport)
}
