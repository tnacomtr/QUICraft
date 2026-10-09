// SPDX-License-Identifier: GPL-3.0-or-later
//
// Headless bench client and mock session server for the Docker testkit. Never shipped.
plugins {
    id("quicraft.java-conventions")
    application
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    // MCProtocolLib is approved for testkit only (CLAUDE.md). MinecraftAuth (LGPL-3.0) is
    // only needed for Microsoft-account login, which the testkit never does; its classes
    // are not referenced by the protocol jar, so leave it out.
    implementation(libs.mcprotocollib) {
        exclude(group = "net.raphimc", module = "MinecraftAuth")
    }
    implementation(libs.gson)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "rs.sudoe.quicraft.testkit.Main"
    applicationName = "quicraft-testkit"
    // Netty loads its native transports.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
