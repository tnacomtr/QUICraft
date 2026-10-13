# Brief upd-0.5: packaging and bootstrap spike for the updater module

GOAL: prove the Java 21 `updater` module can be packaged into the Velocity plugin jar (shaded) and the Fabric jars (fabric-26x and fabric-1.21.11, jar-in-jar via `include`), and report evidence. This is a feasibility spike.
SCOPE: build files only: `velocity/build.gradle.kts`, the fabric-26x and fabric-1.21.11 build files (find them; fabric modules share source from `fabric/`), and anything in `build-logic/` ONLY if strictly needed (report it). No Java source changes. Write a short evidence note at `.agents/plans/spike-upd-0.5.md` in the worktree.
BRANCH/WORKTREE: `agent/phase4-upd-spike` in `/home/tna/QUICraft/.worktrees/phase4-upd-spike`. Run Gradle only there.
READ FIRST: `updater/build.gradle.kts`, `velocity/build.gradle.kts` (shading lines ~36-67), `build-logic/src/main/kotlin/quicraft.fabric-conventions.gradle.kts` (~119-127 include), `settings.gradle.kts`.

STEPS:
1. Add `implementation(project(":updater"))` to velocity; for both Fabric modules add it the same way existing shipped project deps are added (look for how `:core` or bridge modules are wired; mirror it, including `include(...)` if that is the pattern).
2. Build: `./gradlew :velocity:shadowJar` (or whatever the velocity jar task is; find it) and the fabric remapJar/jar tasks for both modules. Then `./gradlew :velocity:check :fabric-26x:check :fabric-1.21.11:check` (these include the license and shaded-notice checks).
3. Evidence: `unzip -l` the produced jars: list where `rs/sudoe/quicraft/updater/*.class` appear (directly, or as a nested jar under META-INF/jars/ for Fabric). Check the class file major version of one updater class (`javap -v` or read bytes 6-7): expect 65. Check whether Velocity's shading relocates or leaves `rs.sudoe.quicraft.updater` as-is. Note the fabric-1.21.11 target is Java 21: confirm nothing in the build rejects major 65 there.
4. Write the note: what you changed (diff summary), commands run with their final lines (BUILD SUCCESSFUL / failure), jar listings (trimmed), class version, any problem. Commit the build changes and the note on the branch, message "spike: wire updater module into platform jars" with trailer lines:
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: no new dependencies; no network beyond Gradle's normal dependency cache; do not run testkit, Docker, gametests or runServer. Never use the built-in advisor() tool. If a loader-level problem appears (e.g. Fabric refuses the nested jar), STOP and report it.
DONE WHEN: note committed with evidence, or a clear blocker reported.
REPORT: RESULT, CHANGED, EVIDENCE (key lines), CONCERNS, BRANCH + COMMIT.
