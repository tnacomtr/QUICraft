# Plan: Phase 4 public alpha and server updater

Status: in progress | Owner: orchestrator

## Goal

Prepare the Phase 4 Velocity and Fabric public alpha, including the server-side updater described in `docs/.plans/updater-plan.md`.

## Non-goals

- No pushing, publishing, deployment, or uploads without explicit approval.
- No client updater, multi-stream implementation, or private signing key generation.
- Do not overwrite existing user work in `.claude/`, `AGENTS.md`, or `docs/.plans/`.

## Context

- `CLAUDE.md:126` requires the previous phase's gate before starting the next phase.
- `CLAUDE.md:139-142` requires production Retry tokens, per-IP/global connection limits, and UDP firewall/DDoS documentation for the alpha.
- The user confirmed that `docs/.plans/updater-plan.md` is the roadmap addition and that it belongs in Phase 4.
- The user selected Modrinth as primary with GitHub and `git.sudoe.rs` also supported, the draft's live atomic replacement on Linux/macOS, and existing hourly checks-only/warning defaults.
- `docs/benchmarks.md:552-557` records successful no-desync, fault-injection and fallback gametests on all six supported versions; `:682` records the user's acceptance of the bounded RTT cost with Phase 3. Historical evidence, not a fresh rerun.
- The updater draft is user-authored input, not a finalized implementation specification. Its open decisions and security boundaries must be resolved before implementation.
- Initial working tree: untracked `.claude/`, `AGENTS.md`, and `docs/.plans/`; no tracked changes reported by `git status --short`.

## Decision

Start with independent read-only readiness and updater feasibility audits. Ground implementation units in their findings before assigning writers. Use isolated branches/worktrees for implementation and `test4cheap` for test writing.

## Contracts

### Retry-token primitive

- New package-private `HmacRetryTokenHandler` implements Netty's `QuicTokenHandler` in `core/src/main/java/rs/sudoe/quicraft/core/transport/`.
- Production constructor: `HmacRetryTokenHandler(BooleanSupplier requireRetry)`; generates a listener-local 32-byte SecureRandom key and uses `System.nanoTime`.
- Test constructor: `HmacRetryTokenHandler(byte[] key, LongSupplier clock, BooleanSupplier requireRetry)`; clones an exactly 32-byte key.
- Lifetime: 30 seconds, valid when `0 <= now - issued < 30_000_000_000L`; signed negative nanoTime values are valid. Process/listener restart changes the secret and invalidates old tokens.
- Token: version byte `1`, issued-at nanos as 8 big-endian bytes, 32-byte HMAC tag, then original DCID suffix (0 to 20 bytes). Maximum length 61, minimum 41, ODCID offset 41.
- HMAC-SHA256 input: ASCII `quicraft/retry/v1` plus a NUL byte; raw IP length as one byte; raw IP; unsigned port as two big-endian bytes; version/timestamp prefix; DCID suffix. Constant-time tag comparison with `MessageDigest.isEqual`.
- `writeToken` returns false without output/index mutation when Retry is not required; otherwise emits a token. Reject invalid addresses/DCID lengths with an exception rather than silently bypassing Retry. Input buffers remain owned by the caller and their indices are unchanged.
- `validateToken` always authenticates presented tokens regardless of Retry policy; rejects malformed, changed, wrong-endpoint, future or expired tokens with `-1`, preserving indices/refcounts. Authenticate before trusting timestamp. Use a fresh JDK Mac per call, no shared mutable crypto state.
- No listener wiring in this unit. Follow-up admission integration supplies the adaptive Retry policy and preserves clean-link/0-RTT behavior. The existing placeholder remains until that integration is verified; this slice alone does not satisfy the Phase 4 release gate.

### Updater

Do not implement wire formats, trust/key rotation, or jar application from the draft alone. Production project paths and signing keys remain owner decisions. Security design review identified loader-generation safety, revocation freshness, replay protection, confined staging, serialized transactions, and dedicated-server gating as prerequisites. Preserve the requested Unix atomic live replacement, but block release until loader-specific evidence exists; never substitute in-place rewriting or unapproved manual-only behavior.

## Waves

### Wave 0: Intake and prerequisite audit

- Audit documented Phase 3 gate evidence and existing Phase 4 safeguards.
- Assess the updater draft against platform lifecycles and identify security/specification gaps.
- Establish bounded implementation units, test cases, and release decision gates from the findings.

### Wave 1: Authenticated Retry-token foundation

#### Unit 1.1: Retry-token implementation

- Executor: default-coder, L2; branch `agent/phase4-retry-token` from the intake checkpoint.
- FILES: create only `core/src/main/java/rs/sudoe/quicraft/core/transport/HmacRetryTokenHandler.java`.
- IMPLEMENT: the precise token contract above. Java 8, SPDX header, no new dependencies, no changes to current listener behavior.
- COPY THE PATTERN IN: `NoRetryTokenHandler.java`, the Netty `QuicTokenHandler` API, and existing core transport Java style.
- COMPILE CHECK: `./gradlew :core:compileJava` using the existing patched-Netty Maven repository supplied to the isolated worktree.
- TESTS: Unit 1.2.

#### Unit 1.2: Retry-token tests

- Executor: cheap-tester using test4cheap, L1; branch `agent/phase4-retry-tests` from integrated Unit 1.1.
- FILES: create only `core/src/test/java/rs/sudoe/quicraft/core/transport/HmacRetryTokenHandlerTest.java`.
- CASES: deterministic independently computed HMAC/layout; valid IPv4/IPv6; full DCID recovery; tamper every authenticated region; changed IP/port/key; length/version boundaries; exact expiry/future/negative nanoTime and wrap; policy false is output-preserving but does not accept bad tokens; nonzero buffer indices and reference ownership; injected-key defensive copy; invalid constructor/write inputs.
- RUN: `./gradlew :core:test --tests '*HmacRetryTokenHandlerTest'`.
- MUTATIONS: disposable independent implementation copies only; baseline passes before wrong MAC, endpoint binding, expiry, ODCID-offset, or policy-validation mutants are tested. Never edit assigned production files for mutation checks.
- DONE WHEN: cases pass and behavioral failures are proven by isolated mutation evidence; independent verifier PASS and peer approval, then security review of actual implementation.

### Later waves

Adaptive Retry/listener admission integration, platform configuration, server-owner DDoS/release documentation, updater specification and implementation remain follow-up waves. Test-writing units use `cheap-tester` with precise behavior specifications and isolated mutation checks. Verification and peer review are mandatory; network, cryptographic, and filesystem changes also require a fresh security reviewer.

## Risks

- Missing Phase 3 gate evidence blocks Phase 4 implementation; report gaps rather than treating preparation as a passed gate.
- Updater downloads and jar replacement create new trust and lifecycle boundaries. Resolve these before coding.
- No real release hosts or key material are configured until owner decisions are recorded.

## Rollback

Retain task branches/worktrees and user work. Integrate only reviewed task changes. Never reset or discard pre-existing work.

## Verification

Commands and numeric gate criteria pending readiness audit. A verifier must independently run relevant build/tests and check acceptance criteria before any completion claim. `test4cheap` test output also requires peer review and mutation evidence.

## Progress

| Session | Agent | Unit | Branch | Level | Status |
| --- | --- | --- | --- | --- | --- |
| ses_ecbe7ceffffedAwKTFkmIc8riA | explorer | Phase 4 readiness | read-only | L0 | done |
| ses_ecbe7ced7ffeBq6zBMKITpCmpX | explorer | Updater feasibility | read-only | L0 | done |
| ses_ecbe1425efferbZ3XYGOIJ8SPN | explorer | Admission hook facts | read-only | L0 | done |
| ses_ecbe04feaffeN64v3fY3yjIXHt | security-reviewer | Updater design | read-only | L0 | done |
| ses_ecbde37e3ffeIPbEMbc3hhb3Ou | advisor | Retry-token design critique | tool-less | L0 | done |

The advisor supported listener-local random keys plus short expiry over minute rotation for this first unit. The local Netty API requires the original DCID to be a suffix: the authenticated tag therefore precedes it, and validation returns offset 41, not 9. All design advice remains subject to actual implementation review.
