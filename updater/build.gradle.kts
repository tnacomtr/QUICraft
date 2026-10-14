// SPDX-License-Identifier: GPL-3.0-or-later
// Loader-independent server updater primitives. Never used by the Java 8 core.
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("quicraft.java-conventions")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.named<Test>("test") {
    // DevTrustFileTest parses the development trust file from the module directory, so a changed
    // file must re-run the tests rather than leave them UP-TO-DATE.
    inputs.file(layout.projectDirectory.file("dev-trust/updater-trust.json"))
        .withPropertyName("devTrustFile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
