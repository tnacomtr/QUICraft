// SPDX-License-Identifier: GPL-3.0-or-later
//
// Transport, handshake, discovery and fallback. No Minecraft classes. Java 8 bytecode so
// every supported game version can load it.
plugins {
    id("quicraft.java-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}
