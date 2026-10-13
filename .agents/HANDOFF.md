# Handoff: Phase 4 (2026-10-13)

Branch `feat/phase4-alpha` @ 0ae5fe6, local only. `main` = 22b4e80 on both remotes. Nothing pushed.
Read first: `CLAUDE.md`, `.agents/plans/2026-10-13-phase4-alpha.md` ("Wave 2"), `.agents/memory/decisions.md`.

## Done (verified)
- **Listener protections merged.**
  - `IngressGuard`: drops Initials under 1200 bytes and enforces the Initial budget.
  - `AdmissionTokenHandler`: HMAC Retry, separate token-less and validated budgets, per-IP checks first, IPv6 per /64.
  - Open-connection leases with a 10 s handshake deadline.
  - Settings `quicraft.admission.*` (protocol.md §9, docs/server-security.md §3).
  - Gates: verifier PASS, Sol approve, Opus security clean.
- **Full clean build:** 77 tasks, 0 failures. Tests: core 196, shadedTest 4, testUpstreamNative 19, velocity 16, bridges 23+23, build-logic 5.
- **Platform gates on the new code:**
  - Fabric gametests (26.x and 1.21.11) PASS: QUIC, 0-RTT, fallback, fault injection with a TCP join.
  - `run-real-client.sh` PASS: real client over QUIC, 0-RTT rejoin, earlyReleases 0→1. Run it as `MOCKSESSION_PORT=18080 xvfb-run -a testkit/run-real-client.sh` (searxng holds 8080).
- **Updater spec:** `docs/updater.md` §12 is a **proposal**, reviewed over 4 security + peer rounds. The live-swap evidence is in the plan.

## Blocked on the user (ask, don't decide)
1. **Seven updater decisions in §12.** Recommendations in brackets:
   - Manifest expiry/re-sign [30 d / 14 d].
   - Revocation lifetime [365 d; the backup key signs yearly].
   - Staging quorum [2 of 3 hosts].
   - `reset-state` command [add, console only].
   - Update dir on another filesystem [fail with manual instructions].
   - Backup-key co-signature on jars that change the key set [yes; needs a format change].
   - `rollback` command [undecided; manual restore is the baseline].
2. **Live-swap semantics.** The atomic rename is safe, but the new version only runs after a restart, and on Velocity the plugin must read no own-jar resources after the swap. The user asked for an "atomic live swap": get sign-off and don't silently downgrade it.
3. **Initial cwnd default.** Make 64 packets the default (protocol.md §9), keeping `-Dquicraft.initialCwndPackets` as the override. Recommended: yes.

## Next (after the answers)
- Implement the updater (Velocity and Fabric dedicated servers). Its remaining blockers:
  - signing tool (key-set fingerprint bytes, seq-floor checks)
  - URL/redirect policy
  - Windows post-exit helper
  - loader tests on JDK 21 and macOS
- Release checklist (`docs/release.md`): the UDP/DDoS owner-docs review is still unticked. Phase 3 prerequisites.
- No alpha-complete claim until all of the above is done. Releases, pushes and Modrinth uploads each need the user's go-ahead. Never open PRs.

## Housekeeping
- **Quarantined, don't merge or delete:** `agent/phase4-retry-tests` (`.worktrees/phase4-retry-tests`).
- **Old worktrees, retained until the user OKs cleanup:** phase4-admission, -admission-tests, -retry-reviewed, -retry-token, -spdx-tests, -updater-docs. Their work is merged.
- **Never stage:** `.claude/`, `AGENTS.md`, `docs/.plans/`, `agent_skills/`, `agent_config.yaml`.
- **Agents:**
  - Briefs start with `.agents/plans/contract-L0.txt`.
  - Advisor: Astra via opencode, never the built-in advisor().
  - At most 6 parallel units.
  - `opencode run` has no `--dir`; `cd` into the worktree first.
- **Older open items:**
  - Forgejo CI (runner labels, Docker/DinD, ARM64, actions mirror)
  - online-mode real-client run
  - the netty-quic-natives workflow has never run
  - the relay's `--work` mode hangs
