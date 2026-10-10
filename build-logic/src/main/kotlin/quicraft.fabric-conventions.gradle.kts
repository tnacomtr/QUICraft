// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fabric hook group for 26.x + 1.21.11 (docs/version-matrix.md): one shared source tree
// (fabric/), one module per jar. The module applies Loom first: net.fabricmc.fabric-loom for
// unobfuscated 26.x, net.fabricmc.fabric-loom-remap (Mojang names in source, Intermediary in the
// jar) for 1.21.11. Module gradle.properties set:
//   minecraftVersion   game version compiled and run against (override: -P<module>.minecraft=…)
//   minecraftRange     fabric.mod.json "minecraft" dependency
//   javaRelease        bytecode level (25 for 26.x, 21 for 1.21.11)
import com.github.jk1.license.LicenseReportExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("quicraft.java-conventions")
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
val fabricLoader = "0.19.5"

val shared = rootProject.layout.projectDirectory.dir("fabric/src")

loom.splitEnvironmentSourceSets()
sourceSets {
    main {
        java.setSrcDirs(listOf(shared.dir("main/java")))
        resources.setSrcDirs(listOf(shared.dir("main/resources")))
    }
    named("client") {
        java.setSrcDirs(listOf(shared.dir("client/java")))
        resources.setSrcDirs(listOf(shared.dir("client/resources")))
    }
}
loom.mods.register("quicraft") {
    sourceSet(sourceSets.main.get())
    sourceSet(sourceSets.getByName("client"))
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
    for (dep in listOf(
        project.dependencies.project(mapOf("path" to ":core", "configuration" to "shadowRuntimeElements")),
        project.dependencies.project(mapOf("path" to ":bridge-netty42")),
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
extensions.configure<LicenseReportExtension> {
    configurations = arrayOf(shipped.name, licensePlatform.name)
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
