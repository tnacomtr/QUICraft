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

### Admission-state primitive

- New package-private `ConnectionAdmission` in core transport, independent of Netty pipeline behavior.
- Constructors: `(int globalAttemptsPerSecond, int perIpAttemptsPerSecond, int maxOpen, int maxOpenPerIp, int retryThreshold, int maxTrackedIps)` and the same plus a final `LongSupplier clock`; production clock is `System.nanoTime`.
- Every limit is positive; `retryThreshold <= globalAttemptsPerSecond`; null clock is rejected. Use resolved `InetAddress` identities and reject null address arguments.
- Synchronized `boolean allowAttempt(InetAddress address)`, `Lease tryAcquire(InetAddress address)` (null if denied), `boolean requiresRetry()`; nested package-private `Lease implements AutoCloseable`, with idempotent `close()`.
- Global and per-IP attempt limits use one listener-wide fixed one-second monotonic window. Exactly the configured count is allowed; the next attempt is denied. Per-IP and tracking-cap rejections consume a global attempt when global budget remains; global rejections do not allocate state.
- At elapsed time at least one second, reset attempts, remove idle IP entries, and retain only entries with open leases with attempt counters reset. Wrapping `long` subtraction supports nanoTime wrap; backward clock values do not reset a window. Retry is required once consumed global attempts reach `retryThreshold`, and resets with the window.
- Track at most `maxTrackedIps` IP entries. Never evict active or current-window attempt entries to admit another IP; deny new identities when full. Tracking capacity is bounded independent of remote address cardinality.
- A lease counts pending and established connections alike. `tryAcquire` does not spend an attempt; it enforces global/per-IP open caps and bounded tracking. `close` releases both exactly once, removing an entry only if its current-window attempts and open count are both zero. Synchronize closure on the admission instance.
- No pipeline integration or default product limit values in this unit; subsequent integration owns lifecycle placement and platform settings.

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

The user permits parallel coding agents and regular testers, but only one llama.cpp cheap-tester may be active at once. The original Retry test agent has finished; that slot is currently free. Its submitted test file was audited and corrected by a regular tester in a fresh worktree.

### Parallel preparation units

#### Unit 1.3: Bounded admission-state implementation

- Executor: default-coder, L1; branch `agent/phase4-admission` from intake checkpoint.
- FILES: create only `core/src/main/java/rs/sudoe/quicraft/core/transport/ConnectionAdmission.java`.
- IMPLEMENT: the exact admission-state contract above, Java 8, SPDX, no dependencies or pipeline edits.
- COMPILE CHECK: `./gradlew :core:compileJava` in its isolated worktree with copied patched-Netty Maven artifacts.
- TESTS: regular default-tester following test4cheap, after implementation; exact limit boundaries, IP independence, shared-IP ports irrelevant, window expiry/wrap/backward clock, bounded tracking retention/recovery, idempotent lease close, and barrier-controlled concurrent reservation.

#### Unit 1.4: Updater and release design documentation

- Executor: default-coder, L1; branch `agent/phase4-updater-docs` from intake checkpoint.
- FILES: create only `docs/updater.md`, `docs/release.md`, `docs/server-security.md`.
- IMPLEMENT: preserve all user-approved updater behaviors and distinguish approved intent from unimplemented contracts. `docs/updater.md` is explicitly an in-progress specification, not an implemented feature or frozen wire format. Cover lifecycle states, trust boundaries, source priority, platform paths, dedicated-server gating, command/notification authorization, scheduling/disclosure, and the security review's blockers. Keep links to the original draft, which must not be edited/deleted.
- Required updater transaction states: idle, checking, downloading, verified-staged, backup-ready, installed-pending-restart, health-check-pending, healthy, recovery-required. Distinguish running and on-disk versions. Revalidate before swap; complete/fsync backup and journal first; refuse unavailable atomic replacement with no in-place fallback. Preserve backup until platform health criteria are proven. Cancellation must never delete the installed jar or the recovery backup.
- Manifest/signature/revocation wire schemas, release sequencing/freshness/clock policy, exact endpoints/CDN redirects, permission integration, Windows helper and loader safety matrix remain explicit unresolved release blockers, not arbitrary decisions assigned to the coder.
- `docs/release.md`: checklist for supported artifacts, build/license/notices/native gates, real Retry/admission integration, signed-release/offline-key process, three-host publishing/disclosure/rules recheck, rollback, loader safety and updater test matrix. No publishing commands executed; do not claim a signing tool or pipeline exists.
- `docs/server-security.md`: UDP/TCP firewall guidance including IPv6 and query-port alternative, bounded limits as mitigation not DDoS prevention, spoofed traffic/volumetric risk and upstream protection, TCP fallback invariants, HTTPS disclosure and updater-key owner responsibilities. Do not invent settings that do not exist.
- DONE WHEN: docs match source/draft and clearly list unimplemented release blockers; independent verifier/peer/security review of this preparation slice.

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
| ses_ecbd80d84ffeYLBalvShEbDxEZ | default-coder | Retry-token primitive | agent/phase4-retry-token | L2 | done, integrated through reviewed branch |
| ses_ecbd3f1e3ffevdUL7OI2kTdbt2 | cheap-tester | Original Retry tests | agent/phase4-retry-tests | L1 | stopped after report; branch/worktree retained quarantined |
| ses_ecbc71d47ffe2XKJ3QXe1bOKzT | default-coder | Admission primitive | agent/phase4-admission | L1 | done, integrated through reviewed branch |
| ses_ecbc71d0bffeE40WFEHgwkvHRX | default-coder | Updater/release/security docs | agent/phase4-updater-docs | L1 | stopped; docs gated and merged |
| ses_ecbc1335fffe27A09jUusyU5sl | default-tester | Admission tests and review fixes | agent/phase4-admission-tests | L2 | done, final 43ffccd gated and merged |
| ses_ecbbb6ff2ffeSEigYjFbBQfyNu | verifier | Admission verification 1 | read-only admission-tests worktree | L0 | done, FAIL: evidence access only |
| ses_ecbb1949cffe4xUPYJTRbrvI7V | verifier | Admission verification 2 | read-only admission-tests worktree | L0 | done, FAIL: scope/plan access and cached execution |
| ses_ecbaaf47cffeEKJ5JZQ3PzwsNO | verifier | Admission verification 3 | read-only admission-tests worktree | L0 | done, PASS on pre-review tests |
| ses_ecba74c58ffeGBsr5Q7UdQVDXO | peer-reviewer | Admission review 1 | read-only admission-tests worktree | L0 | done, clock/cleanup findings fixed in 62567fc |
| ses_ecbb9fa7effe9uYYT4kkashXDO | verifier | Documentation verification 1 | read-only updater-docs worktree | L0 | done, PASS |
| ses_ecbb3df27ffec1uz0LWr7etnKr | peer-reviewer | Documentation review 1 | read-only updater-docs worktree | L0 | done, three findings fixed |
| ses_ecbaead7affeOHA5r96LMLc1mh | verifier | Corrected documentation verification | read-only updater-docs worktree | L0 | done, PASS |
| ses_ecbaaf401ffeETkezFFFocfoEW | peer-reviewer | Corrected documentation review | read-only updater-docs worktree | L0 | done, approve; cross-reference nit fixed |
| ses_ecba7fecfffeUi4VSn5KIneD9t | security-reviewer | Documentation safety review | read-only updater-docs worktree | L0 | done, clean for preparation only |
| ses_ecb9c4abfffe1bUR1Dig5M4lH4 | default-tester | Audited Retry tests and renewed patched-dependency proof | agent/phase4-retry-reviewed | L2 | done, final 64e1ab9 gated and merged |
| ses_ecb8d3885ffeZ3y7L8d7ZV41fM | verifier | Retry implementation and audited tests | read-only retry-reviewed worktree | L0 | done, pre-review PASS |
| ses_ecb8d37fdffeHuzeN7jg70011I | verifier | Corrected admission tests | read-only admission-tests worktree | L0 | done, inline lease finding fixed |
| ses_ecb8b281affe1mP8rbAU5vK5xu | explorer | Official loader live-swap behavior | read-only | L0 | done; runtime proof still required |
| ses_ecb8620c9ffeK1CRYdbF7pi3Pt | peer-reviewer | Retry coverage review | read-only | L0 | done, genuine wrap/index findings fixed |
| ses_ecb6fa9c9ffe9l1RJj7cyKPg6b | verifier | Final Retry verification | read-only retry-reviewed worktree | L0 | done, PASS 52 tests and core check/build |
| ses_ecb6fa9c8ffeyxvqhcynJM7igg | verifier | Final admission verification | read-only admission-tests worktree | L0 | done, PASS 21 tests and core check/build |
| ses_ecb699fd2ffe4bIm9164njhn29 | peer-reviewer | Final Retry review | read-only | L0 | done, approve |
| ses_ecb699f9effeyR3uKGaiwTVBMl | peer-reviewer | Final admission review | read-only | L0 | done, approve |
| ses_ecb662a43ffeHu3IaBb47Okl45 | security-reviewer | Actual primitive code security | read-only | L0 | done, clean within unwired primitive scope |
| ses_ecb5fdd6affe3nJditgYISkQVJ | verifier | First code merge fast check | root integration branch | L0 | done, PASS 52 fresh tests |

The advisor supported listener-local random keys plus short expiry over minute rotation for this first unit. The local Netty API requires the original DCID to be a suffix: the authenticated tag therefore precedes it, and validation returns offset 41, not 9. All design advice remains subject to actual implementation review.

### Current branch disposition

- Integration branch: `feat/phase4-alpha`; planning/roadmap commits `64711f5`, `2abfbec`. No push or publication.
- Retry implementation: commit `9bb1f06`, integrated through the reviewed Retry branch; its original worktree is retained.
- Admission implementation/tests: final commit `43ffccd` (ancestors `a453b12`, `cd789c0`, `62567fc`), merged after final verifier PASS, peer approval and fresh code security review. 21 cases passed, with five isolated mutants renewed against exact final tests. All acquired test leases now receive assertion-failure cleanup.
- Documentation: commit `5ec479f` on `agent/phase4-updater-docs` was merged after verifier PASS, peer approval and clean preparation-only security review. The copied original draft remains untracked read-only input and was not staged.
- Original cheap-test output: commit `6cdf7a8`, branch `agent/phase4-retry-tests`, worktree `.worktrees/phase4-retry-tests`, retained quarantined. It created unapproved `mutation-isolated/` artifacts and omitted sufficient proof provenance. The agent is stopped. Do not merge or delete this branch/worktree; no root user work or production source changes were observed.
- Audited Retry output: final commit `64e1ab9` (ancestor `d9711f2`) on `agent/phase4-retry-reviewed`, merged after verifier PASS, peer approval and fresh code security review. No original cheap commit was merged. 52 cases now prove genuine signed-clock wrap, real nonzero buffer indices and immediate ownership assertions as well as inherited API behavior. Ten mutants were killed against exact patched dependencies and unchanged final tests.
- The root user's untracked `.claude/`, `AGENTS.md`, and `docs/.plans/` remain untouched. No worktree cleanup is authorized or planned while gates/writers are outstanding.

### Current milestone and remaining work

- This session prepares Phase 4; it does not complete the public-alpha gate. The listener still uses `NoRetryTokenHandler`; neither primitive enforces runtime protections yet.
- Final unit gate totals: 73 new tests, 15 killed behavior mutants, peer approvals and a clean security review limited to the primitives.
- Documentation is merged; README and companion status wording now distinguish implemented primitives from inactive protections and the unimplemented updater.
- Final integrated whole-project build and review remain to be run before reporting this preparation slice complete.
- Next implementation wave: adaptive Retry/admission pipeline integration and platform configuration, then actual native/TCP/fault-injection tests. Updater schemas, revocation coverage, signing tooling and loader-generation proof remain open release blockers.
- Loader exploration pinned Fabric Loader 0.19.5 but could only inspect an unpinned Velocity development branch. No guarantee of live-swap safety was found. Test the exact sha256-pinned Velocity 4.2.0-30 build and each supported Fabric/JDK combination with lazy classes/resources, nested jars and native paths before implementing the requested swap.

### Admission integration facts for the next wave

- Netty's public `QuicHeaderParser` can classify the first datagram header before codec allocation; the codec itself only creates channels for unknown-DCID Initial packets (`QuicheQuicServerCodec.java:114-119`). Prefer this parser over a hand-written version/type guess.
- `QuicheQuicCodec.java:175-203` parses the first packet and releases each received datagram. A preceding admission handler must forward accepted messages without releasing them and release rejected messages exactly once.
- The parser uses zero-based absolute offsets (`QuicHeaderParser.java:75-79`), so any new admission handler must parse a slice and preserve the incoming buffer's indices and byte order assumptions.
- Both ordinary and hosted listener binds need identical admission placement before the QUIC codec. Child connection reservations must cover pending and established channels and release on the close future, including failed initialization/TLS/shutdown.
- No integration defaults or pipeline contracts are finalized yet. In particular, distinguish conservative Initial-datagram budgets (including retries/retransmits) from unique logical connection counts; do not silently throttle established 1-RTT traffic or disable low-load 0-RTT.

### Live-swap evidence (2026-10-13, updater blocker 10)

Experiment only (scratch dirs, no repo code): a test plugin/mod with a lazily loaded class `Lazy`
(non-constant `V`), a resource `version.txt`, and, in v2 only, a new class `Lazy2` plus a 2 MB pad.
v1 runs, the jar is replaced, then the lazy reads run. 3 trials per mode, then a restart.

| Loader | Replace mode | Lazy (first load after swap) | Resource | v2-only class | Shutdown |
| --- | --- | --- | --- | --- | --- |
| Velocity 4.2.0-30, JDK 25.0.4.1 | atomic rename | v1 (old) | **v2 (new)** | not found | clean |
| Velocity 4.2.0-30 | in-place overwrite | NoClassDefFoundError | v2 | not found | clean |
| Fabric Loader 0.19.5, MC 26.1.2, JDK 25.0.4.1 | atomic rename | v1 (old) | v1 (old) | not found | clean |
| Fabric Loader 0.19.5 | in-place overwrite | v2 (mixed) | ZipException | not found | clean |
| Fabric Loader 0.19.5, MC 1.21.11, JDK 25 | atomic rename (x3) / in-place (x1) | v1 / v2 | v1 / ZipException | not found | clean |
| all three | restart after swap | v2 | v2 | found | clean |

Reading: an atomic rename never crashed either loader, and a restart picks up the new jar cleanly.
Fabric keeps a consistent old view until restart. Velocity mixes versions: classes come from the
old jar, resources from the new one. A live-swapped Velocity plugin must therefore read no
resources from its own jar after the swap (or preload them) and must require a restart before the
new version counts. In-place overwrite is unsafe on both loaders. Not yet tested: JDK 21 (none installed;
1.21.11 ran on JDK 25), and macOS. Logs: scratchpad `liveswap-velocity/`, `liveswap-fabric/`
(the first Fabric run inlined a constant and is invalid; the `*-fixed.log` files are the valid ones).

### Wave 2 (2026-10-13, orchestrator: Claude Opus 5.5)

| Handle | Agent (model) | Unit | Branch | Status |
| --- | --- | --- | --- | --- |
| a3a427c7 | superior (opus) | 2.1 / 2.1b / 2.1c listener Retry + admission wiring and hardening | agent/phase4-listener-admission | merged 945ed86, branch deleted |
| ses_ec9aa6f8 | default-tester (deepseek) | 2.7 admission unit matrix (3 rounds) | agent/phase4-admission-matrix | merged 8df6c07, branch deleted |
| a3ab5e12 | superior (opus) | 2.5 updater spec §12 (4 rounds) | agent/phase4-updater-spec | merged feef4c2, branch deleted |
| adcba98d | default-coder (haiku) | owner docs for merged admission | agent/phase4-owner-docs-2 | merged e24cddc, branch deleted |
| a7bc598f / ad09fa0c | security-reviewer (opus) | updater spec x4, admission 2.1b/2.1c | read-only | done, clean (no medium+ open) |
| opencode sessions | verifier (deepseek), peer-reviewer (gpt-6.1-sol) | every unit above | read-only | PASS / approve on final commits |

Listener result: `QuicServer` wires `IngressGuard` (pre-codec Initial budget, drops Initials under
1200 bytes) and `AdmissionTokenHandler` (HMAC Retry; token-less and validated budgets; per-IP first;
IPv6 per /64) into both binds, plus leases with a 10 s handshake deadline. Retry modes
adaptive/always/never; refusals and a handshake backlog (pending*4 >= maxOpen) force Retry in every
mode. 0-RTT stays at low load. `:core:test` 168 + matrix; verifier, Sol and Opus security all
clean. The one remaining security note (Faults javadoc) was fixed in d2f6590.

Updater spec: `docs/updater.md` §12 is a reviewed PROPOSAL. Implementation stays blocked on the user
decisions listed in §12 (manifest expiry/refresh, revocation lifetime, 2-of-3 quorum, reset-state
command, cross-filesystem behaviour, backup-key co-signature for key-set changes, rollback command)
and the remaining blockers. Live swap: the evidence above means a swap takes effect only at restart,
and Velocity must read no own-jar resources after a swap; this needs the user's sign-off, because
the request was an atomic live swap.
