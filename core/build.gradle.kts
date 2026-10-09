// SPDX-License-Identifier: GPL-3.0-or-later
//
// Transport, handshake, discovery and fallback. No Minecraft classes. Java 8 bytecode so
// every supported game version can load it.
//
// Platforms consume the shaded jar (`shadowJar`): Netty 4.2 relocated under
// rs.sudoe.quicraft.shaded, with the quiche natives renamed to match (docs/protocol.md §11).
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("quicraft.java-conventions")
    id("com.gradleup.shadow")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

/** Relocation adds a prefix only; Netty derives the native library name from it. */
val nettyRelocationPrefix = "rs.sudoe.quicraft.shaded"
val nativePrefix = nettyRelocationPrefix.replace("_", "_1").replace('.', '_') + "_"
val nativeClassifiers = listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64", "windows-x86_64")

val quicNatives = configurations.create("quicNatives") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.quic.classes) {
        // NIO datagram transport only: quiche is the single native we ship.
        exclude(group = "io.netty", module = "netty-transport-classes-epoll")
    }
    for (classifier in nativeClassifiers) {
        quicNatives(variantOf(libs.netty.quic.native) { classifier(classifier) })
    }
    // Plain (unshaded) natives so ordinary unit tests can run QUIC on this machine.
    testRuntimeOnly(files(quicNatives))
}

// Native libraries renamed for the relocated loader, e.g.
// libnetty_quiche42_linux_x86_64.so -> librs_sudoe_quicraft_shaded_netty_quiche42_linux_x86_64.so
val renamedNatives = tasks.register<RenameQuicNatives>("renamedNatives") {
    nativeJars.from(quicNatives)
    prefix = nativePrefix
    outputDir = layout.buildDirectory.dir("renamed-natives")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    relocate("io.netty", "$nettyRelocationPrefix.io.netty")
    // The native jars' licence/notice files are byte-identical to Netty's, which come in with
    // the classes; checkShadedNotices verifies that.
    from(renamedNatives)
    exclude(
        "**/module-info.class",
        "META-INF/versions/*/module-info.class",
        "META-INF/native-image/**",
        "META-INF/services/reactor.blockhound.integration.BlockHoundIntegration",
        "META-INF/maven/**",
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
}

tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

// Tests that run against the shaded jar only, with no unshaded Netty on the classpath.
// Deliberately not derived from `test`: testImplementation inherits implementation (= Netty).
val shadedTest = sourceSets.create("shadedTest")
dependencies {
    "shadedTestImplementation"(files(tasks.named("shadowJar")))
    "shadedTestImplementation"(platform(libs.junit.bom))
    "shadedTestImplementation"(libs.junit.jupiter)
    "shadedTestRuntimeOnly"(libs.junit.launcher)
}

val shadedTestTask = tasks.register<Test>("shadedTest") {
    group = "verification"
    description = "Runs tests against the shaded jar (relocated Netty, renamed natives)."
    testClassesDirs = shadedTest.output.classesDirs
    classpath = shadedTest.runtimeClasspath
    useJUnitPlatform()
}

val checkShadedNotices = tasks.register<ShadedNoticeCheck>("checkShadedNotices") {
    group = "verification"
    description = "Checks the shaded jar keeps every licence/notice file of the jars it bundles."
    shadedJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    sourceJars.from(configurations.runtimeClasspath, quicNatives)
    marker = layout.buildDirectory.file("shaded-notice-check.ok")
}

tasks.check {
    dependsOn(shadedTestTask, checkShadedNotices)
}
