# Handoff: Phase 4 (2026-10-13)

Branch `feat/phase4-alpha`, implementation integration through `f96738b`, local only. Nothing pushed.
Read first: `CLAUDE.md`, `.agents/plans/2026-10-13-updater-implementation.md`, Phase 4 plan, `.agents/memory/decisions.md` and `gotchas.md`.

## Approved choices (do not ask again)
- User approved 30-day manifests / 14-day refresh, 365-day revocations / yearly backup signing, 2-of-3 staging coverage, console `reset-state`, cross-filesystem fail-manual, and backup authentication for key-set changes.
- Explicit clarification: manual restore only, no v1 rollback command; atomic on-disk replacement while running, new code activates after restart. Own-jar resources must be preloaded; JDK21/macOS and crash safety remain release gates.
- Initial cwnd default64, retaining `quicraft.initialCwndPackets`; explicit0 selects native10,1..1000 supported, malformed/out-of-range ->64.
- No publishing/push/deploy/PR, generated signing keys, real updater requests or worktree cleanup authorized.

## Merged and independently gated
- Prior listener protections remain merged: HMAC Retry, ingress/attempt/open budgets, IPv6 /64,10s deadline. Prior Fabric gametests and real-client QUIC/0-RTT/fallback gates are historical, not freshly rerun after cwnd64.
- New Java21 `updater/` module, separate from Java8 core: bounded strict JSON, immutable four-document schemas, hex/kid derivation and semantic version ordering. 229 tests and8killed isolated mutants; verifierPASS, peerapprove, securityclean. Merged `6734de9` and earlier parser merge.
- Cwnd unit merged `f96738b`:209core tests,20upstream (1expectedskip),4shaded;13config tests prove6base failures/13fixed passes. Native loss-ratio fixture is explicitly calibrated at10; separate64test verifies2MiB byte-exact delivery. No64reorder-performance-parity claim.
- UDP/DDoS owner-doc security review passed after warning about TCP-only frontend bypass and hidden-origin exposure. Checklist tick is documentation-only.
- Fresh root `./gradlew build --rerun-tasks --no-build-cache` at `0c4fd8e`:84tasks executed,528tests passed,1expectedupstream-native skip. SPDX/licenses/shadednotices/platformjars passed; coremajor52/updater65. Independent verifier `ses_ec916abdeffeJGJWNrtxD8CmSM` PASS; final peer `ses_ec912d8b7ffeqPNtpie2o0V2k6` approved. Log `/tmp/opencode/phase4-integrated-build.log`. Later status edits are documentation-only.

## Next
1. Freeze Wave3B signature/fingerprint/freshness APIs, critique, then implement and test using public RFC8032 fixtures (no signing-key generation). Parsing alone is NOT authentication. Retain all valid signers; staged key-set changes require current non-revoked backup authorization at final locked revalidation too. Existing `.sig` envelope can carry both roles; no bypass flag. A non-frozen handoff candidate is `/tmp/opencode/phase4-next-auth-draft.md`.
2. Persistent per-key floors/revocation unions/coverage, confined locks/state/journal, atomic transaction/crash recovery. Then offline signing validation tool, fixed URL/redirect policy, and dedicated-only platform lifecycle/config/commands. No updater is runtime-wired or shipped yet.
3. Windows post-exit helper, exact-loader JDK21/macOS safety, platform/TCP-fallback/health/release gates. Controlled10/64 reorder benchmark before claiming parity. Actual hosting paths and owner public signing keys are not configured; do not invent them or enable traffic.
4. Preserve unrelated user/config paths; no concurrent root Gradle builds. Current integrated evidence covers the first slice only, not future authentication or platform work.

## Safety and retained work
- Quarantined, never merge/delete: `agent/phase4-retry-tests` and new `agent/phase4-cwnd-tests` (`c7b8536`, unauthorized evidence file). Only authorized cwnd test content was salvaged into the reviewed branch; all proof regenerated independently.
- All old and new worktrees retained. `.worktrees/phase4-cwnd-red-proof` intentionally contains the test fixture against old production; don't merge it. Exact agent roster/disposition is in the implementation plan; all editing agents are stopped/done.
- Never stage/edit/delete unrelated `.claude/`, `.opencode/`, root `AGENTS.md`, `docs/.plans/`, `agent_skills/`, `agent_config.yaml`/`.bak`, or existing `.agents/opencode-runs/`.
- At most6parallel units and ONE llama.cpp tester; plan-only checkpoints prevent drift. Do not use built-in advisor; prior setup uses Astra via opencode. `opencode run` has no `--dir`.
- Older open items: Forgejo CI environment, online-mode real-client run, native workflow never run, relay `--work` hangs. Historical real-client invocation: `MOCKSESSION_PORT=18080 xvfb-run -a testkit/run-real-client.sh`.
- Phase4 remains incomplete. No public-alpha or live-loader-safety claim without all release gates and user publication approval.
