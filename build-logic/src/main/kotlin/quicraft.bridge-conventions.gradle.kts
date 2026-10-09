// SPDX-License-Identifier: GPL-3.0-or-later
//
// The Netty bridges share one source tree (bridge-netty/) and differ only in the game Netty
// they compile and test against. Each module adds that Netty as compileOnly + test dependency.
plugins {
    id("quicraft.java-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

sourceSets {
    main { java.srcDir(rootProject.file("bridge-netty/src/main/java")) }
    test { java.srcDir(rootProject.file("bridge-netty/src/test/java")) }
}

dependencies {
    // The shaded core: its relocated Netty never meets the game's Netty, as in production.
    compileOnly(project(path = ":core", configuration = "shadowRuntimeElements"))
    testImplementation(project(path = ":core", configuration = "shadowRuntimeElements"))
}
