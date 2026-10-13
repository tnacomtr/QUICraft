# Claude Code handoff: Phase 4

GOAL: Continue Phase 4 public-alpha preparation on `feat/phase4-alpha`. This is a handoff, not approval to release.

READ FIRST: `CLAUDE.md`, this file, `.agents/plans/2026-10-13-phase4-alpha.md`, `.agents/memory/decisions.md`.

## STATE
- Retry-token primitive and bounded admission-state primitive are merged, independently verified, peer-reviewed, and security-reviewed. They are foundations only, not connected to the live listener.
- Evidence: 52 Retry tests plus 10 killed behavioral mutants; 21 admission tests plus 5 killed mutants. Both units passed fresh `:core:check :core:build` in their worktrees. Root merge check freshly passed the 52 Retry tests.
- The server still uses `NoRetryTokenHandler`; admission limits do not currently protect runtime traffic. Phase 4 is not complete.
- Updater/release/security docs are merged. The updater is not implemented. The original user-authored `docs/.plans/updater-plan.md` is untracked input and must remain untouched and unstaged.
- Approved updater direction: server-only for Velocity and Fabric dedicated servers; Modrinth primary plus GitHub and `git.sudoe.rs`; hourly checks-only default with disclosure; explicit command for download; owner-managed offline signing keys; requested Unix atomic live replacement remains conditional on loader safety and crash-recovery proof.
- Loader-source research found no live-swap guarantee. Fabric Loader 0.19.5 was inspected; Velocity source was not pinned to the exact sha256-pinned build. Keep live replacement blocked pending exact-loader runtime tests.
- A root full build previously failed because SPDX scanning traversed retained `.worktrees/`. Fix commit `5429948` excludes `.worktrees/**`; regression-test branch `agent/phase4-spdx-tests` at `973b8b8` has three real Gradle TestKit tests and red/green proof, but is NOT merged. Verifier and peer passed; security review could not run because usage limit was reached.
- Root tracked working changes include plan/README/CLAUDE/docs wording. Root untracked `.claude/`, `AGENTS.md`, and `docs/.plans/` are user work: do not inspect broadly, edit, stage, or delete. The handoff itself is task-owned and untracked.
- No pushes, publishing, deployments, private-key generation, or updater hosting setup were authorized. The original cheap Retry-test branch/worktree is quarantined; do not merge or delete it.

## NEXT
1. Resume with the pending security review of the SPDX/build-logic change on `.worktrees/phase4-spdx-tests` (`973b8b8`). Do not merge until that review is clean; do not launch a second local/cheap tester.
2. Merge the reviewed test branch into `feat/phase4-alpha`, then run a fresh full root `./gradlew build --rerun-tasks --no-build-cache` serially. Confirm both Retry/admission XML counts, license/SPDX/shaded-notice/native tasks, and all platform build tasks. Keep temporary worktrees; the fix should exclude them, not delete them.
3. Review the integrated diff and markdown links; update plan/status evidence. Commit only task-owned tracked changes and `.agents/HANDOFF.md`. Never stage `.claude/`, root `AGENTS.md`, or `docs/.plans/`.
4. Next implementation wave: wire Retry and bounded admission into both ordinary and hosted server listeners, choose reviewed platform settings/defaults, and add lifecycle, packet ownership, native QUIC, concurrency, and TCP-fallback tests. Preserve TCP behavior and low-load 0-RTT. See plan sections “Admission integration facts” and “Current milestone”.
5. Before alpha, also finish owner UDP/firewall guidance and gates. Updater implementation remains a separate blocked unit until manifest/signature/revocation freshness, endpoint/redirect policy, confined serialized staging/journal/recovery, dedicated-server authorization, Windows helper, signing workflow, and exact loader-generation safety are resolved and reviewed.

CONSTRAINTS: No alpha-complete claim until listener protections, platform/fault-injection gates, Phase 3 prerequisites, release checks, and updater release blockers are actually satisfied. Do not push, publish, deploy, create keys, or silently replace requested atomic live swap with manual-only behavior.

REPORT: State exact commit/build/test evidence, what remains inactive, and any blockers. Keep Phase 4 preparation distinct from a release-ready public alpha.
