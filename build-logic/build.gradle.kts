// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.github.jk1:gradle-license-report:3.1.4")
    // Apache-2.0; build tooling only, never shipped.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.6.1")
}
