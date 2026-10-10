// SPDX-License-Identifier: GPL-3.0-or-later
//
// Root-project plugin: every source file carries the project's SPDX header (CLAUDE.md).
plugins {
    base
}

val checkSpdxHeaders = tasks.register<SpdxHeaderCheck>("checkSpdxHeaders") {
    group = "verification"
    description = "Checks that every source file carries the GPL-3.0-or-later SPDX header."
    baseDir = layout.projectDirectory
    marker = layout.buildDirectory.file("spdx-check.ok")
    sources.from(
        fileTree(layout.projectDirectory) {
            include(
                "**/*.java", "**/*.kt", "**/*.kts", "**/*.sh", "**/*.yml", "**/*.yaml",
                "**/*.toml", "**/*.properties", "**/Dockerfile*",
            )
            exclude(
                "**/build/**", "**/.gradle/**", "**/.kotlin/**", "gradle/wrapper/**",
                "testkit/results/**",
                // Agent worktrees: other checkouts of this repo, checked in their own builds.
                ".claude/**",
            )
        },
    )
}

tasks.check {
    dependsOn(checkSpdxHeaders)
}
