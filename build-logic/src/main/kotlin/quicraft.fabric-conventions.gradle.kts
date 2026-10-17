// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fabric hook group for 26.x + 1.21.11 (docs/version-matrix.md): one shared source tree
// (fabric/), one module per jar, plus a small per-module source dir (src/client/java) for the
// calls that differ between them. The module applies Loom first: net.fabricmc.fabric-loom for
// unobfuscated 26.x, net.fabricmc.fabric-loom-remap (Mojang names in source, Intermediary in the
// jar) for 1.21.11. Module gradle.properties set:
//   minecraftVersion   game version compiled and run against (override: -P<module>.minecraft=…
//                      together with -P<module>.fabricApi=…, to test other versions in the range)
//   minecraftRange     fabric.mod.json "minecraft" dependency
//   javaRelease        bytecode level (25 for 26.x, 21 for 1.21.11)
//   fabricApiVersion   Fabric API for the client gametests only (never a runtime dependency)
import com.github.jk1.license.LicenseReportExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("quicraft.java-conventions")
    id("quicraft.updater-trust")
}

val loom = extensions.getByType<LoomGradleExtensionAPI>()
val remapped = plugins.hasPlugin("net.fabricmc.fabric-loom-remap")

// findProperty: module gradle.properties aren't visible to providers.gradleProperty.
fun moduleProperty(name: String): String =
    requireNotNull(findProperty(name)) { "${project.name}/gradle.properties must set $name" }.toString()
val minecraftVersion: String = providers.gradleProperty("${project.name}.minecraft").orNull
    ?: moduleProperty("minecraftVersion")
val minecraftRange: String = moduleProperty("minecraftRange")
val javaRelease: Int = moduleProperty("javaRelease").toInt()
val fabricApiVersion: String = providers.gradleProperty("${project.name}.fabricApi").orNull
    ?: moduleProperty("fabricApiVersion")
val fabricLoader = "0.19.5"

val shared = rootProject.layout.projectDirectory.dir("fabric/src")

loom.splitEnvironmentSourceSets()
sourceSets {
    main {
        java.setSrcDirs(listOf(shared.dir("main/java")))
        resources.setSrcDirs(listOf(shared.dir("main/resources")))
    }
    named("client") {
        // Plus the module's own src/client/java: the few classes whose calls differ per jar.
        java.setSrcDirs(listOf(shared.dir("client/java"), layout.projectDirectory.dir("src/client/java")))
        resources.setSrcDirs(listOf(shared.dir("client/resources")))
    }
    // Minecraft-free unit tests, shared by both jars (ServerSettings parsing and the /3 switch's
    // inputs). They never start a game; each module's own src/test/java applies on top.
    named("test") {
        java.srcDirs(shared.dir("test/java"), layout.projectDirectory.dir("src/test/java"))
        // The client classes' pure logic (settings files, pin paths, notices) is unit-tested too.
        // Only classes whose constructors and statics touch no game class are loaded there.
        compileClasspath += sourceSets.getByName("client").output + sourceSets.getByName("client").compileClasspath
        runtimeClasspath += sourceSets.getByName("client").output
    }
}
// Separate run directories, so a client and a server can run side by side (both gitignored).
// Loom 1.18 deprecates runDir without a replacement yet.
@Suppress("DEPRECATION")
loom.runs.named("client") { runDir("run/client") }
@Suppress("DEPRECATION")
loom.runs.named("server") { runDir("run/server") }
loom.mods.register("quicraft") {
    sourceSet(sourceSets.main.get())
    sourceSet(sourceSets.getByName("client"))
}

// Client gametests (fabric/src/gametest): a real client and an in-process dedicated server, run
// headless with runClientGameTest (under xvfb-run on Linux). Fabric API (Apache-2.0) is needed
// for the test harness only; the mod itself doesn't depend on it.
extensions.getByType<net.fabricmc.loom.api.fabricapi.FabricApiExtension>().configureTests {
    createSourceSet = true
    modId = "quicraft-gametest"
    enableGameTests = false
    enableClientGameTests = true
    eula = true
}
// The harness's network synchronizer counts packets sent through Connection.sendPacket against
// packets received; QUICraft's status query writes raw bytes, which it can't account for. Fabric
// says to disable it for mods that work at the Netty level; tests wait in ticks instead.
loom.runs.matching { it.name == "clientGameTest" }.configureEach {
    property("fabric.client.gametest.disableNetworkSynchronizer", "true")
    // Opt-in join timing measurement (JoinTimingGameTest): -Pquicraft.joinTimings=N
    providers.gradleProperty("quicraft.joinTimings").orNull?.let {
        property("quicraft.gametest.joinTimings", it)
    }
    // Opt-in: write and print the live PLAY packet inventory (RoutingTableGameTest, protocol 775):
    // -Pquicraft.emitPlayIds=true (default path) or -Pquicraft.emitPlayIds=<file>
    providers.gradleProperty("quicraft.emitPlayIds").orNull?.let {
        property("quicraft.gametest.emitPlayIds", it)
    }
    // Opt-in: the real client against an outside server (ExternalServerGameTest), e.g. the
    // testkit's Velocity through testkit/run-real-client.sh: -Pquicraft.externalServer=host:port
    providers.gradleProperty("quicraft.externalServer").orNull?.let {
        property("quicraft.gametest.externalServer", it)
    }
    // Opt-in: online-mode login against the testkit's mock session server. The real client reads
    // its session/services/profiles hosts only when all three are set:
    // -Pquicraft.minecraftApi=http://127.0.0.1:8080
    providers.gradleProperty("quicraft.minecraftApi").orNull?.let {
        property("minecraft.api.session.host", it)
        property("minecraft.api.services.host", it)
        property("minecraft.api.profiles.host", it)
    }
}
// Fedora's NVIDIA GLX vendor is preferred by libglvnd and breaks Xvfb context creation; CI runners
// have no such vendor, so this is a no-op there. The run task is a JavaExec.
tasks.withType<JavaExec>().matching { it.name == "runClientGameTest" }.configureEach {
    environment("__GLX_VENDOR_LIBRARY_NAME", "mesa")
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
}
sourceSets.named("gametest") {
    java.setSrcDirs(listOf(shared.dir("gametest/java")))
    resources.setSrcDirs(listOf(shared.dir("gametest/resources")))
    compileClasspath += sourceSets.main.get().output + sourceSets.getByName("client").output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.getByName("client").output
}

// What the mod jar carries besides its own classes: the shaded core (Netty relocated, natives
// renamed) and the game-Netty bridge, both as nested jars (Fabric's jar-in-jar).
val shipped = configurations.create("shipped") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (remapped) {
        "mappings"(loom.officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:$fabricLoader")
    } else {
        "implementation"("net.fabricmc:fabric-loader:$fabricLoader")
    }
    if (remapped) {
        "modGametestImplementation"("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    } else {
        "gametestImplementation"("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    }
    for (dep in listOf(
        project.dependencies.project(mapOf("path" to ":core", "configuration" to "shadowRuntimeElements")),
        project.dependencies.project(mapOf("path" to ":bridge-netty42")),
        project.dependencies.project(mapOf("path" to ":updater")),
    )) {
        "implementation"(dep)
        "include"(dep)
        shipped(dep)
    }
}

// The license check covers what we ship and the loader we build against. Minecraft and the
// libraries it brings (on the compile classpath through Loom) are the platform: proprietary or
// under their own terms, never bundled, like the Velocity proxy jar.
val licensePlatform = configurations.create("licensePlatform") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    licensePlatform("net.fabricmc:fabric-loader:$fabricLoader")
}
// Fabric API (tests only) and its modules.
val licenseTests = configurations.create("licenseTests") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    licenseTests("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}
extensions.configure<LicenseReportExtension> {
    configurations = arrayOf(shipped.name, licensePlatform.name, licenseTests.name)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraftRange" to minecraftRange,
        "java" to javaRelease.toString(),
        "loader" to fabricLoader,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

base {
    archivesName = "quicraft-${project.name}"
}

// Stable name for the testkit's Fabric image (testkit/docker/compose.yaml).
tasks.register<Sync>("testkitMod") {
    from(tasks.named(if (remapped) "remapJar" else "jar"))
    into(layout.buildDirectory.dir("testkit"))
    rename { "quicraft-fabric.jar" }
}
