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

// CI only (the macOS/JDK 21 job in .github/workflows/updater-macos.yml): run the tests on another
// JDK, for example -Pquicraft.updaterTestJdk=21, to cover the oldest Java the 1.21.11 jar supports.
providers.gradleProperty("quicraft.updaterTestJdk").orNull?.let { jdk ->
    tasks.named<Test>("test") {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk.toInt()) }
    }
}

// Opt-in only, default unchanged: point the test JVM's java.io.tmpdir at another directory, for example
// a symlink, to reproduce the temp path form macOS produces (/var/folders -> /private/var/folders).
// Do not use JAVA_TOOL_OPTIONS for this: its banner breaks the two lock tests that read child output.
providers.gradleProperty("quicraft.testTmpDir").orNull?.let { tmpDir ->
    tasks.named<Test>("test") {
        // A system property alone is not a task input, so the build cache would serve the result of a
        // run with a different java.io.tmpdir. Record the value so each tmpdir is cached on its own.
        inputs.property("quicraftTestTmpDir", tmpDir)
        systemProperty("java.io.tmpdir", tmpDir)
    }
}
