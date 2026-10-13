# Handoff: Phase 4 updater runtime (2026-10-13, session cut by usage limit)

Branch `feat/phase4-alpha` (local only, nothing pushed). Plan: `.agents/plans/2026-10-13-updater-runtime.md` (Progress section).
Sonnet stands in for peer-reviewer/superior while the ChatGPT limit lasts. Test keys are RFC 8032 TEST seeds only; tell the user when the implementation is done so they can swap keys.

## Merged on feat/phase4-alpha (gated)
Waves 0-2, 3.0-3.5, 3.7 recovery (c2dd937). Updater suite 747/0. Docs 6a5943b, 1e74dce (build.json, journal without .prev, host names, redirect allowlist, header size).

## Ready but NOT yet merged (gates were stopped by the usage limit; rerun them)
1. `agent/phase4-upd-harden2` (2H, Wave 2 review fixes; HEAD 7f7adb5). Sonnet peer APPROVED; its should-fix (empty-dir firstRunAllowed) fixed in 7f7adb5. Needs a DeepSeek verifier PASS (brief: scratchpad brief-verify-2H.txt, expected 709 tests).
2. `agent/phase4-upd-pipeline` (3.6a; HEAD a6a70d8, already contains 2H + recovery + feat). 777 tests green locally. Orchestrator added: revocation stateFull fails closed (no candidate), reset staging needs non-full fresh state, reported freshness includes minRevocationsSeq. Needs verifier + Sonnet peer. Merge after 2H.
3. `agent/phase4-upd-harden3` (3H, Wave 3 security review fixes, 4 commits on 2296a90; 760 tests). Medium fixed: KeySetChangeAuthorization now enforced in ArtifactVerifier. Needs verifier + Opus security re-check.
Note: two DeepSeek verifier runs hung with 0-byte logs and no opencode session; a smoke `opencode run` worked. If it recurs, run the verifier via a Sonnet agent and say so.

## Next
- 3.6b transaction (brief `.agents/plans/brief-upd-3.6b-transaction.md`; worktree not created). Must call KeySetChangeAuthorization again under the lock before ATOMIC_MOVE. completeHealth now takes an Executor.
- Wave 3.5 crash matrix gate, then Wave 4: platform-neutral UpdaterRuntime facade in `updater/`, then thin Velocity (inject PluginContainer, CommandManager) and Fabric adapters (no Fabric API at runtime: command registration needs a mixin; no server entrypoint today). Build must emit META-INF/quicraft/build.json (none exists yet). Spike wiring on `agent/phase4-upd-spike`.
- Open spec gaps: reset with invalid revoked.json does not re-merge (rebuild API deferred); withdrawn attribution limit documented.
- All worktrees retained (cleanup needs user OK), incl. new phase4-upd-harden3 and phase4-upd-secrev3 (detached, read-only review).
