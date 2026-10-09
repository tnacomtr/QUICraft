// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared Java setup. Each module picks its bytecode level with `options.release`
// (core: 8, testkit: 25); the toolchain is always JDK 25.
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer

plugins {
    java
    id("com.github.jk1.dependency-license-report")
}

group = "rs.sudoe.quicraft"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val libs = versionCatalogs.named("libs")

dependencies {
    // JUnit 5 is EPL-2.0: allowed for tests only (user decision, 2026-10-09).
    // 5.x rather than 6.x because 6 needs Java 17 and core's tests must stay Java 8.
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -options: JDK 25 warns that --release 8 is obsolete; core targets 8 on purpose.
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-processing,-serial"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE_quicraft.txt" }
    }
}

// License check (CLAUDE.md "Licensing"): every dependency of this module, tests included,
// must carry a license on the allowlist. AGPL is never on it, so it fails the build.
licenseReport {
    configurations = arrayOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
    )
    filters = arrayOf(LicenseBundleNormalizer())
    allowedLicensesFile = rootProject.file("config/license/allowed-licenses.json")
    renderers = arrayOf<ReportRenderer>(InventoryMarkdownReportRenderer("licenses.md"), JsonReportRenderer())
}

tasks.check {
    dependsOn(tasks.named("checkLicense"))
}
