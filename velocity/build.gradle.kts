// SPDX-License-Identifier: GPL-3.0-or-later
//
// Velocity plugin: terminates QUIC at the proxy and feeds each stream into Velocity's own
// pipeline through bridge-netty42 (Velocity 4.2 bundles Netty 4.2). The plugin jar bundles the
// shaded core and the bridge as they are; core's Netty is already relocated.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("quicraft.java-conventions")
    id("com.gradleup.shadow")
}

tasks.withType<JavaCompile>().configureEach {
    // velocity-api 4.2.0 publishes Gradle metadata requiring Java 25.
    options.release = 25
}

// The proxy jar (GPL-3.0) for the internals we hook (docs/platforms/velocity.md). Compile-only:
// never bundled. Same build and checksum as testkit/docker/velocity/Dockerfile.
val velocityBuild = "4.2.0-30"
val velocitySha256 = "35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8"
val velocityProxy = tasks.register<VerifiedDownload>("downloadVelocityProxy") {
    url = "https://fill-data.papermc.io/v1/objects/$velocitySha256/velocity-$velocityBuild.jar"
    sha256 = velocitySha256
    outputFile = layout.buildDirectory.file("velocity-proxy/velocity-$velocityBuild.jar")
}

dependencies {
    // MIT, but the POMs of velocity-api and velocity-brigadier declare no license; both are
    // allowlisted by exact version in config/license/allowed-licenses.json (checked Oct 2026:
    // Velocity's api/LICENSE and PaperMC/velocity-brigadier are MIT). aopalliance (public
    // domain, via Guice) isn't needed to compile and stays out.
    compileOnly(libs.velocity.api) { exclude(group = "aopalliance") }
    annotationProcessor(libs.velocity.api)
    compileOnly(files(velocityProxy))
    implementation(project(path = ":core", configuration = "shadowRuntimeElements"))
    implementation(project(":bridge-netty42"))
    // The proxy jar carries Velocity's API, Netty and the rest, as at runtime.
    testImplementation(files(velocityProxy))
}

// @Plugin(version = ...) needs a compile-time constant.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/sources/buildinfo")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("rs/sudoe/quicraft/velocity/BuildInfo.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// SPDX-License-Identifier: GPL-3.0-or-later\n" +
                "package rs.sudoe.quicraft.velocity;\n\n" +
                "final class BuildInfo {\n" +
                "    static final String VERSION = \"$version\";\n\n" +
                "    private BuildInfo() {\n    }\n}\n",
        )
    }
}
sourceSets.main { java.srcDir(generateBuildInfo) }

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    // Both bundled jars carry the project licence; the rest (Netty, quiche, BoringSSL notices)
    // comes once, from the shaded core.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Stable name for the testkit's Velocity image (testkit/docker/compose.yaml).
tasks.register<Sync>("testkitPlugin") {
    from(tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("testkit"))
    rename { "quicraft-velocity.jar" }
}

tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}

tasks.named<Test>("test") {
    // The proxy jar's log4j writes logs/ into the working directory.
    val work = layout.buildDirectory.dir("test-work")
    workingDir = work.get().asFile
    doFirst { work.get().asFile.mkdirs() }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
