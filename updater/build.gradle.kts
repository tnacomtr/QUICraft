// SPDX-License-Identifier: GPL-3.0-or-later
// Loader-independent server updater primitives. Never used by the Java 8 core.
plugins {
    id("quicraft.java-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
