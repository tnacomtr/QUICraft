GOAL: Add one Gradle TestKit test proving the SPDX check's excludes are anchored at the project root.
WHY: Security review of 5429948 (low note): nothing pins that ".worktrees/**" only matches at the root; a later change to "**/.worktrees/**" would silently skip module sources.
SCOPE: edit ONLY build-logic/src/test/kotlin/SpdxScopeFunctionalTest.kt in this worktree; commit on the current branch (agent/phase4-spdx-anchor).
READ FIRST: build-logic/src/main/kotlin/quicraft.spdx.gradle.kts, build-logic/src/main/kotlin/SpdxHeaderCheck.kt, build-logic/src/test/kotlin/SpdxScopeFunctionalTest.kt (copy its fixture helpers and style exactly).
TEST: new test `nestedWorktreeDirInsideModuleIsStillChecked`: fixture project with a headerless Java file at `module/.worktrees/X.java` (and nothing headerless elsewhere); running checkSpdxHeaders must FAIL and the failure output must name `module/.worktrees/X.java`. Also add `nestedAgentSkillsDirIsStillChecked` for `module/agent_skills/Y.java` (the root-only exclude "agent_skills/**" was added in d9361af).
RUN: ./gradlew -p build-logic test --offline (in this worktree). All tests must pass. Then prove the test can fail: temporarily change ".worktrees/**" to "**/.worktrees/**" in quicraft.spdx.gradle.kts, rerun, record that the new test fails, then restore the file exactly (git diff must show only the test file).
CONSTRAINTS: Kotlin file keeps its SPDX header. No new dependencies. Do not push or merge.
DONE WHEN: tests pass, failure proof recorded, one commit "test(build): pin root anchoring of SPDX excludes".
REPORT: RESULT, test counts from the XML report, the failure-proof output lines, BRANCH + COMMIT hash. Max 20 lines.
