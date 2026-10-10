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

// Netty's QUIC classes always come from QUICraft's patched build (natives/, docs/protocol.md §11).
// Natives come from it for the platforms listed here (-Pquicraft.patchedNatives=a,b or =all) and
// from upstream Netty for the rest, where the patched-only options are reported unavailable.
val patchedNatives: List<String> = providers.gradleProperty("quicraft.patchedNatives")
    .getOrElse("linux-x86_64")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    .let { if (it == listOf("all")) nativeClassifiers else it }
require(nativeClassifiers.containsAll(patchedNatives)) { "unknown classifier in quicraft.patchedNatives: $patchedNatives" }

/** Netty's classifier for the build host, e.g. linux-x86_64. */
val hostClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val osPart = when {
        os.startsWith("linux") -> "linux"
        os.startsWith("mac") -> "osx"
        os.startsWith("windows") -> "windows"
        else -> os.replace(' ', '_')
    }
    val archPart = when (val arch = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> arch
    }
    "$osPart-$archPart"
}

/** Tests assert the relaxed loss threshold is available exactly when this host runs a patched native. */
val expectRelaxedLossThreshold = hostClassifier in patchedNatives

val patchedRepo = rootProject.layout.projectDirectory.dir("natives/build/out/repo")

val quicNatives = configurations.create("quicNatives") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(platform(libs.netty.bom))
    // Its POM lists no epoll classes: NIO datagram transport only, quiche is the single native.
    implementation(libs.quicraft.netty.quic.classes)
    for (classifier in nativeClassifiers) {
        val native: Provider<MinimalExternalModuleDependency> =
            if (classifier in patchedNatives) libs.quicraft.netty.quic.native else libs.netty.quic.native
        quicNatives(variantOf(native) { classifier(classifier) })
    }
    // Plain (unshaded) natives so ordinary unit tests can run QUIC on this machine.
    testRuntimeOnly(files(quicNatives))
}

// Without the patched build, resolution would fail with a bare "could not find"; say what to run.
for (name in listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath", "quicNatives")) {
    configurations.named(name) {
        incoming.beforeResolve {
            if (!patchedRepo.dir("rs/sudoe/quicraft/netty").asFile.isDirectory) {
                throw GradleException(
                    "QUICraft's patched Netty QUIC build is missing from natives/build/out/repo. " +
                        "Run natives/build-linux.sh (Docker) first.",
                )
            }
        }
    }
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
    systemProperty("quicraft.test.expectRelaxedLossThreshold", expectRelaxedLossThreshold)
}

tasks.named<Test>("test") {
    systemProperty("quicraft.test.expectRelaxedLossThreshold", expectRelaxedLossThreshold)
}

// The patched classes with upstream Netty's native for this host, which is what platforms without
// a patched native run. QUIC must still work, with the relaxed loss threshold reported unavailable.
val upstreamHostNative = configurations.create("upstreamHostNative") {
    isCanBeConsumed = false
    isTransitive = false
}
if (hostClassifier in nativeClassifiers) {
    dependencies {
        upstreamHostNative(variantOf(libs.netty.quic.native) { classifier(hostClassifier) })
    }
    val testUpstreamNative = tasks.register<Test>("testUpstreamNative") {
        group = "verification"
        description = "Runs the native-feature and loopback tests on upstream Netty's native for this platform."
        val test = sourceSets.test.get()
        testClassesDirs = test.output.classesDirs
        classpath = test.runtimeClasspath.minus(quicNatives) + upstreamHostNative
        useJUnitPlatform()
        systemProperty("quicraft.test.expectRelaxedLossThreshold", false)
        filter {
            includeTestsMatching("rs.sudoe.quicraft.core.transport.NativeFeaturesTest")
            includeTestsMatching("rs.sudoe.quicraft.core.transport.QuicLoopbackTest")
        }
    }
    tasks.check {
        dependsOn(testUpstreamNative)
    }
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
