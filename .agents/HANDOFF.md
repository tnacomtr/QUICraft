# Claude Code handoff: Phase 4

GOAL: Continue Phase 4 public-alpha preparation on `feat/phase4-alpha`. This is a handoff, not approval to release.

READ FIRST: `CLAUDE.md`, this file, `.agents/plans/2026-10-13-phase4-alpha.md` (Progress, "Wave 2"), `.agents/memory/decisions.md`.

## STATE (2026-10-13, end of wave 2)
- Full root build `./gradlew build --rerun-tasks --no-build-cache` on 8df6c07+: BUILD SUCCESSFUL, 77 tasks; tests core 196, shadedTest 4, testUpstreamNative 19, velocity 16, bridge-netty41 23, bridge-netty42 23, build-logic 5, 0 failures.
- Listener protections are WIRED and merged (945ed86, 8df6c07): `IngressGuard` before the QUIC codec in both binds, `AdmissionTokenHandler` (HMAC Retry, token-less and validated attempt budgets, per-IP first, IPv6 per /64), open-connection leases with a 10 s handshake deadline. Settings: `quicraft.admission.*` (docs/server-security.md section 3, protocol.md §9). Verifier PASS, Sol approve, Opus security review clean for every round.
- Owner docs updated (e24cddc). docs/release.md ticks the Retry-handler and limits items; the UDP/DDoS docs review item is still open.
- Updater: `docs/updater.md` §12 is a reviewed PROPOSAL (feef4c2), with security and peer review done over 4 rounds. Implementation stays BLOCKED until the user decides the §12 USER DECISION items and signs off the live-swap semantics (restart-required; Velocity reads no own-jar resources after a swap). Don't silently replace the requested atomic live swap.
- Nothing is pushed. `feat/phase4-alpha` is local only; main = 22b4e80 on both remotes.
- Quarantined, do not merge or delete: `agent/phase4-retry-tests` (6cdf7a8, `.worktrees/phase4-retry-tests`).
- Never stage `.claude/`, root `AGENTS.md`, `docs/.plans/`, `agent_skills/`, `agent_config.yaml`.

## NEXT
1. Present the updater decisions and the live-swap implication to the user; record the answers in `.agents/memory/decisions.md`.
2. Platform/fault-injection gates with the new listener code: Fabric gametests (26.x and 1.21.11), Velocity tests, testkit join/ping over QUIC with default admission settings, and the fault-injection TCP-join test.
3. Release checklist (docs/release.md), the UDP/DDoS docs review item, Phase 3 prerequisites.
4. Updater implementation only after step 1 and its remaining blockers (signing tool, endpoint/redirect policy, Windows helper, loader tests on JDK 21 and macOS).
5. Still waiting on the user: default initial cwnd of 64 packets (protocol.md §9).

CONSTRAINTS: No alpha-complete claim until listener protections (done), platform/fault-injection gates, Phase 3 prerequisites, release checks and updater release blockers are all satisfied. Do not push, publish, deploy, create keys, or open PRs.
