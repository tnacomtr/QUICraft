# Plan: Runtime server updater (Phase 4, continuation)

Status: approved with review fixes (2026-10-13), not started. Owner: orchestrator.
Base: `feat/phase4-alpha` @ `5392797`. Predecessor plan: `.agents/plans/2026-10-13-updater-implementation.md`.

## Goal

Implement the approved server-only updater as a runtime feature: authenticate host metadata with Ed25519 over exact bytes, persist security state and permanent revocations, fetch bounded metadata and jars, stage and atomically replace the live jar on Linux and macOS (activation after restart), recover after a crash, and expose console/OP commands behind dedicated-server-only platform adapters. Nothing is published, signed with owner keys, or pushed.

## Non-goals

- No publishing, pushing, deploying, PRs, or uploads (CLAUDE.md "Working rules").
- No owner signing keys generated, no signing ceremony, no real updater network requests in builds or tests.
- No metadata hosting decisions or invented public project paths (blocker 5, `docs/updater.md:1071-1078`). Fetcher origins are injected; real origins are a release step.
- No Windows post-exit helper implementation (blocker 11, `docs/updater.md:1076`). Windows stops at `verified-staged` (`docs/updater.md:875-877`). The helper is deferred to a future plan (user, 2026-10-13), not built here.
- No hot activation: the new jar takes effect only after restart (`docs/updater.md:1010-1011`, user-approved).
- No `/quicraft update rollback` command; restore is manual (`docs/updater.md:951-955`, user-approved).
- No client-side activation; the updater runs on Velocity and Fabric dedicated servers only (`docs/updater.md:15`).
- No JDK21/macOS loader-safety claim until its own gate passes (blocker 10, `docs/updater.md:1020-1022`).
- No real redirect policy decision (blocker 5, `docs/updater.md:1073`). The fetcher takes an injected per-host redirect policy, default deny; the real policy is set together with the real origins.
- No final health criteria (blocker 9, `docs/updater.md:1074`). Wave 3 uses an injected `HealthSignal`; the platform default is a placeholder (server reached "started" and the QUIC/TCP listeners bound), documented as provisional, and the release gate stays open until the user fixes the criteria.

## Context (grounded)

- Implemented primitives are parsing only, `updater/src/main/java/rs/sudoe/quicraft/updater/`: `StrictJson.parse(byte[],int,int)` (`StrictJson.java:49`), `DocumentParser.parseTrust/parseSignatures/parseRelease/parseRevocations(byte[])` (`DocumentParser.java:81,130,157,205`), records in `UpdateDocuments.java:14-97`, package-private `Hex` (`Hex.java:15-74`), `UpdateVersion` (`UpdateVersion.java:51,96`), checked `UpdateRejectedException`. No signature, state, transition, freshness, network or filesystem code exists (explorer report).
- Module: plain Java 21 library, no production dependencies, `updater/build.gradle.kts:3-8`; `options.release = 21`; toolchain JDK 25 and license gate from `build-logic/src/main/kotlin/quicraft.java-conventions.gradle.kts:17-21,52-66`. Listed in `settings.gradle.kts`. Velocity is Java 25 (`velocity/build.gradle.kts:13-16`), fabric-26x Java 25, fabric-1.21.11 Java 21, core Java 8. 21 is the common floor.
- Velocity lifecycle: `onProxyInitialize` before listeners bind (`velocity/.../QuicraftVelocity.java:60-69`), `onProxyShutdown` (`:71-74`), `stop()` idempotent (`:208-226`), `@DataDirectory Path dataDirectory` (`:54`). Config `plugins/quicraft/config.properties` (`Settings.java:13-16,35-51`). No command or permission API is used anywhere today (grep: zero matches).
- Fabric lifecycle: common `onInitialize` (`fabric/.../QuicraftFabric.java:17-25`), config dir `config/quicraft/` (`:28-29`); server start/stop hooks in `ServerConnectionListenerMixin.java:23-31`; dedicated gate `isDedicatedServer()`/`DedicatedServer` (`ServerQuic.java:78,94`); server settings `ServerSettings.java:14,33-47`; config load inside `ServerQuic.start` (`ServerQuic.java:102-103`). The common entrypoint also runs on clients (`fabric/src/main/resources/fabric.mod.json:8,10`).
- Packaging: Velocity shades `implementation` project deps (`velocity/build.gradle.kts:36-37,62-67`); Fabric nests shipped deps as jar-in-jar through `include` (`build-logic/src/main/kotlin/quicraft.fabric-conventions.gradle.kts:119-127`).
- Fail-safe hook shape to copy: `fabric/.../Hooks.java:23-45`. Config template/defaults pattern: `Settings.java:18-51`, `ServerSettings.java:16-47`.
- Test/tooling: testkit log-based waits (`testkit/run-baseline.sh:58-88`), real-client harness (`testkit/run-real-client.sh:48-67`), fault injection (`fabric/.../Hooks.java`). Integration target only sketched (`docs/updater.md:247-248`).

## Decision

Build the updater as a layered Java 21 library plus two thin platform adapters.

Chosen layering: (1) key validation and authenticated evidence, (2) persisted policy and state, (3) fetch, acceptance, staging and transaction, (4) platform adapters, (5) packaging and integration. Waves 1 and 2 merge separately with security review between them.

Rejected options and why:
- All logic in platform modules: duplicates security rules and drifts; core is Java 8 and cannot host Ed25519.
- One "policy" unit for Wave 1 and 2: unreviewable and untestable in isolation; security review would have to cover everything at once.
- Separate independent stores per state file: two files must commit as one logical transaction; one store owns both.
- Wiring commands and network early: exposes unfinished security paths. Commands are wired only after the fetch and transaction contracts exist and pass review.

Platform wiring decision: keep `updater` loader-agnostic. Add `implementation(project(":updater"))` to `velocity`, `fabric-26x` and `fabric-1.21.11`; shade into the Velocity jar and nest via `include` for Fabric. The Fabric jar is shared client/server, so updater classes ship in both, but activation is gated on the dedicated server. Record this in the plan and docs.

## Security invariants (Wave 0 freezes these; every unit cites them)

1. Cryptographic validity and policy acceptance are separate. A parsed document, a verified signature or a computed fingerprint is evidence, never an authorization by itself.
2. Signer evidence is bound to one `TrustSnapshot` (epoch, key-set fingerprint, revoked pubs). Policy never combines signers verified under different snapshots or generations.
3. `TORN` means only that the document bytes do not match the `.sig` `payloadSha256`. It is a bounded refetch hypothesis and never relaxes a check and never replaces committed state.
4. Persistence follows the spec's two independent files (`docs/updater.md:722-732`): `state.json` and `revoked.json` are each replaced atomically on their own, never as a paired generation. Every commit is an idempotent, monotone merge (max of floors and seqs, union of revocations), so a crash between the two file writes leaves a state that the next commit converges from, whatever order the writes landed in. `revoked.json` is written first.
5. Corruption: a file that fails parsing or schema is replaced by its `.prev` if valid, with a WARN (spec behaviour). A `.prev` may be one commit older; it is never lowered below the running trust file's floors (`manifestSeqFloor`, `revocationsSeqFloor`, `built`) or below the revocations the next refetch re-merges. Both copies invalid: `state.json` disables staging, `revoked.json` disables the updater, until `reset-state`. Neither file is ever reset silently. A deliberate local rollback by someone with write access to the update directory is out of the threat model (same-user write access can replace the jar itself); the plan does not claim to detect it.
6. Every acceptance, merge and prune produces one `StateDelta` applied exactly once; no unit mutates state in place.
7. Lock order is always `txn` then `state`; a busy OS lock owned by another process means "another server uses this directory", refuse, never wait (`docs/updater.md:859-860`).
8. Checks advance floors, merge restrictions and persist `seqByKid` under `state.lock` (`docs/updater.md:968,982-983`). This is explicit and tested: a later manifest can raise the floor and make an earlier staged manifest ineligible, and step 4 of the transaction then discards the staged copy (`docs/updater.md:885-889`).
9. Coverage counts distinct configured host origins returning identical authenticated current bytes. Retries, aliases and the same origin under two schemes do not add hosts. Host coverage is not signer independence (`docs/updater.md:663-667`).
10. Key-set changes: a staged jar whose computed key-set fingerprint differs from persisted state is authorized only if the manifest's exact bytes are also signed by the current, non-revoked `revocation`-role (backup) key, in addition to release authorization (`docs/updater.md:394-402`). No manifest flag waives it; one valid backup signature may satisfy both roles. The check runs at acceptance, at revalidation, and at the final locked authorization before `ATOMIC_MOVE`. It never permits a jar that would replace the revocation key (transition 6 is hand-install only).
11. Filesystem safety: `NOFOLLOW_LINKS` everywhere, fixed staging and backup names from `txid`, `0700`/`0600`, owned by the server user, same file store for `ATOMIC_MOVE`, discovered live jar is a real regular file whose parent is the real scan directory and whose id is unique (`docs/updater.md:717-721,1057-1069`).

## Contracts

All in package `rs.sudoe.quicraft.updater` unless stated. Wave 0 types and Waves 1 and 2 are frozen here. Wave 3 to 5 interfaces are frozen at the Wave 2.5 gate.

### Wave 0 types (records only, no logic, merged before Wave 1)

- `PersistedTrust(int epoch, String keySetFingerprint, String revocationPub)`: the trust identity read from `state.json`.
- `RevokedState(List<RevokedKey> revokedKeys, List<String> blockedArtifacts)`; `UpdaterState(...)` mirroring `state.json` (`docs/updater.md:770-800`): `epoch`, `keySetFingerprint`, `revocationKey`, `CurrentRevocations(seq, sha256, issued, expires, minManifestSeq)`, `Map<String, KidSeq> seqByKid`, `List<WithdrawnEntry> withdrawn`, `lastAnnounced`, `List<String> warnedBad`, `Map<String, Instant> tornSince`. `UpdaterState.persistedTrust()` derives `PersistedTrust`.
- `StateDelta(UpdaterState newState, RevokedState newRevoked)`; every policy function returns a delta and never mutates.
- `Snapshot(UpdaterState state, RevokedState revoked)`.
All immutable with defensive copies; tests only for construction invariants.

### Wave 1: key validation, authenticated evidence, transitions, freshness

- `KeyRole` enum: `RELEASE`, `REVOCATION`. `TrustedKeyRoles.of(UpdateDocuments.TrustedKey) -> Set<KeyRole>`; parser already guarantees non-empty, known roles.
- `KeySetFingerprint.of(List<UpdateDocuments.TrustedKey>) -> String` (64 lowercase hex). Sort by `pub` ascending; per key append 32 raw pub bytes then one role byte (bit0 release, bit1 revocation); SHA-256. Throws on non-32-byte pub or duplicate pub.
- `TrustSnapshot(int epoch, String keySetFingerprint, Set<String> revokedPubs, Set<String> releasePubs, Set<String> revocationPubs)`. Derived from a `Trust` plus persisted revoked pubs. Carries the capability sets used to authorize.
- `Ed25519.verify(byte[] message, String pubHex, String sigHex) -> boolean`. JDK only: X.509 prefix `302a300506032b6570032100`, `X509EncodedKeySpec`, `KeyFactory.getInstance("Ed25519")`, `Signature.getInstance("Ed25519")`. Fixed rejection on malformed hex.
- `VerifiedSigner(String kid, String pub, Set<KeyRole> roles)`; `VerifiedSigners(List<VerifiedSigner>)` with `hasRelease()`, `hasRevocation()`, defensive copy.
- `EnvelopeStatus` enum: `VERIFIED`, `NO_VALID_SIGNER`, `TORN`.
- `SignatureVerifier.check(byte[] documentBytes, SignatureEnvelope envelope, TrustSnapshot snapshot) -> EnvelopeVerification(EnvelopeStatus, VerifiedSigners)`.
  - `TORN` when `Hex.sha256(documentBytes)` differs from `envelope.payloadSha256`.
  - Else verify each `SignatureEntry` against the trusted key whose derived kid equals `entry.kid()` and whose `pub` is not revoked; retain every entry that verifies; unknown kids ignored; a bad entry never suppresses a valid one.
  - `NO_VALID_SIGNER` when nothing verifies.
- `VerificationOutcome` enum: `VERIFIED`, `TORN`, `BAD_SIGNATURE`, `MALFORMED`.
- `VerifiedDocument<T>(T document, byte[] exactBytes, VerifiedSigners signers, TrustSnapshot snapshot)`.
- `DocumentVerification<T>(VerificationOutcome outcome, VerifiedDocument<T> verified)`; `verified` is null unless VERIFIED.
- `KeySetChangeAuthorization.check(VerifiedDocument<ReleaseManifest> manifest, String stagedKeySetFingerprint, UpdaterState state, TrustSnapshot snapshot) -> boolean`: true when the fingerprint equals persisted state, or when `manifest.signers()` contains a verified signer whose pub is the persisted, non-revoked revocation pub. Pure; reused by acceptance (3.3), artifact verification (3.4) and the final locked authorization (3.6). Invariant 10.
- `AuthenticatedDocuments.verifyRelease(byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot) -> DocumentVerification<ReleaseManifest>` and `.verifyRevocations(...) -> DocumentVerification<Revocations>`. Require a `RELEASE` signer for a manifest and a `REVOCATION` signer for revocations. Never returns a document whose outcome is not VERIFIED.
- `Transition` enum: `INITIALISE`, `NORMAL`, `INVALID_SAME_EPOCH`, `OLDER_EPOCH_REPORT_ONLY`, `ROTATION`, `REVOCATION_KEY_REPLACEMENT`.
- `TrustDecision(Transition transition, int newEpoch, String newFingerprint, String newRevocationPub, Set<String> newlyRevokedPubs, boolean keepPermanentRevocations)`.
- `TrustTransition.classify(PersistedTrust persisted /* null when none */, Trust trust) -> TrustDecision`. Pure, implements transitions 1 to 6 (`docs/updater.md:374-393`). `REVOCATION_KEY_REPLACEMENT` sets `keepPermanentRevocations` true, returns the old revocation pub in `newlyRevokedPubs`, and marks the old revocation floors for reset to the trust floors.
- `TrustTransition.apply(TrustDecision, UpdaterState, RevokedState) -> StateDelta`. Deterministic; produces the single delta the store commits. Permanent revocations are preserved explicitly, not through a loose side effect.
- `FreshnessPolicy` pure functions, constants `FUTURE_TOLERANCE = 24h`, `CLOCK_WARN = 24h`, `MANIFEST_MAX_LIFETIME = 45 days`, `REVOCATIONS_MAX_LIFETIME = 400 days`:
  - `effectiveNow(systemNow, clockFloor)`, `futureIssued(issued, systemNow)`, `unexpired(expires, effectiveNow)`, `clockBehind(systemNow, clockFloor)`, `structuralTimesValid(issued, expires, maxLifetime)`.
- `ClockFloor.of(PersistedTrust/Trust built, current revocation issued, List<Instant> acceptedManifestIssuedOfNonRevokedKids) -> Instant` (`docs/updater.md:605-610`). Never the system clock.

### Wave 2: persisted policy and state

Records come from Wave 0 types.
- `StateCodec.encode/decode` and `RevokedCodec.encode/decode` over `StrictJson`, caps 131072 and 262144, exact keys, deterministic encoding (sorted keys).
- `StateStore(Path updateDir, Clock clock)`:
  - `initialise(Trust trust)` writes both files if absent (`docs/updater.md:739-750`).
  - `Snapshot commit(UnaryOperator<Snapshot> merge)`: take the in-process guard (a `ReentrantLock` per canonical update dir, held before the OS lock, because a second `FileChannel.lock` in the same JVM throws `OverlappingFileLockException` rather than blocking), then `tryLock` on `state.lock` (busy: refuse, invariant 7), re-read both files, run the pure merge, write `revoked.json` then `state.json`, release. Atomic per file: `.tmp`, `force(true)`, copy current to `.prev`, `ATOMIC_MOVE`, directory fsync. No cross-file generation marker (invariant 4).
  - Corruption per invariant 5: per-file `.prev` fallback with WARN, clamped up to the trust-file floors; both copies invalid disables staging (`state.json`) or the updater (`revoked.json`).
  - `resetState(Trust running)`: under the same locks, rebuild `state.json` from the running trust file's seeds (`docs/updater.md:739-750`), keep a valid `revoked.json` untouched, never touch an invalid `revoked.json` without the spec's refetch-and-remerge path (`docs/updater.md:728-737`). Backs the console-only `reset-state` command.
- `SequencePolicy` pure functions returning deltas:
  - `manifestFloor(UpdaterState, Trust, RevokedState)` (`docs/updater.md:554-556`).
  - `manifestAccepted(seq, sha256, floor, UpdaterState)`; equal seq must match the persisted hash, otherwise equivocation.
  - `mergeRestrictions(RevokedState, UpdaterState, Revocations, Instant systemNow)` adds `revokedKeys`/`blockedArtifacts` to the permanent union and maxes `minManifestSeq`, with no expiry or seq condition (`docs/updater.md:566-570`).
  - `selectCurrent(UpdaterState, List<VerifiedDocument<Revocations>>, Instant systemNow)` implements seq/hash acceptance and the seed case (`docs/updater.md:571-583`). Named gaps resolved: non-empty persisted `sha256` with no validating document this run keeps the persisted current; equal-seq ties require identical bytes or are equivocation.
  - `freshRevocations(UpdaterState, ReleaseManifest, Trust, Instant effectiveNow)` (`docs/updater.md:645-647`).
  - `stagingCoverage(List<VerifiedDocument<Revocations>> per distinct origin, UpdaterState)` requires 2 of 3 distinct origins returning matching current bytes (`docs/updater.md:651-654,663-667`).
  - `acceptManifest(...) -> StateDelta` persists `seqByKid`, clock contribution and own-platform withdrawn entries (`docs/updater.md:982-983`).

### Wave 3 to 5: frozen at Wave 2.5 (superior critique `superior-upd-2.5`, all 12 findings accepted)

Shared (unit 3.0, first): all in package `rs.sudoe.quicraft.updater`, immutable, byte arrays copied in and out.
- `CancelToken { boolean isCancelled(); }`; `MutableCancelToken implements CancelToken` (`cancel()`).
- `ReleaseHost(String key, URI origin)`: key in `modrinth`/`github`/`sudoe`; origin HTTPS, normalized (lowercase host, default port removed, no path/query/userinfo). `ReleaseHosts.of(List<ReleaseHost>)` rejects duplicate keys and duplicate normalized origins (finding 3).
- `ReleaseEndpoints { URI revocations(ReleaseHost); URI revocationsSignature(ReleaseHost); URI manifest(ReleaseHost); URI manifestSignature(ReleaseHost); URI artifact(ReleaseHost, ReleaseEntry); }` injected; real paths are blocker 5.
- `RedirectPolicy { boolean allows(ReleaseHost host, URI from, URI to); static RedirectPolicy denyAll(); }`, HTTPS on every hop, max 3 hops; a redirect keeps the original host identity and never adds a coverage vote.
- `FetchRequest(ReleaseHost host, URI uri, long maxBytes, Duration connectTimeout, Duration idleReadTimeout, Duration overallDeadline, RedirectPolicy redirects)`; the overall deadline never resets across hops (finding 4).
- `FetchOutcome { OK, TRANSPORT_ERROR, TIMEOUT, OVERSIZE, HTTP_STATUS, REDIRECT_REFUSED, CANCELLED }`; `FetchException(FetchOutcome)`; `FetchedBytes(byte[] bytes)`.
- `SourceFetcher { FetchedBytes fetch(FetchRequest, CancelToken) throws FetchException; long download(FetchRequest, WritableByteChannel destination, CancelToken) throws FetchException; }`. `download` checks cancel between chunks and fails OVERSIZE on byte `maxBytes + 1` without writing it; the caller owns the destination.
- `DocumentPair(byte[] documentBytes, byte[] signatureBytes)`; `HostEvidence(ReleaseHost host, FetchOutcome outcome, DocumentPair pair /* null unless OK */)`; `HostCollection(List<HostEvidence> responses)`.
- `RuntimeIdentity(String platform, String target, UpdateVersion runningVersion, Trust runningTrust, String liveSha512AtInit)` built once at init from preloaded resources.
- `ArtifactIdentity(String platform, UpdateVersion version, long size, String sha512)`.
- `ManifestEvidence(byte[] exactManifestBytes, byte[] exactSignatureBytes)`: retained so step 6 re-authenticates locally under a fresh snapshot (finding 7).
- `InstallCapability { VERIFIED_STAGING_ONLY, ATOMIC_REPLACEMENT }`, injected. Windows always VERIFIED_STAGING_ONLY; Linux/macOS ATOMIC_REPLACEMENT only when the platform adapter's evidence gate says so (blocker 10), never from OS detection alone, and never when directory fsync is unsupported (finding 11, 12).
- `InitBarrier { boolean readyForStaging(); }`.
- `HealthSignal { CompletionStage<Boolean> awaitHealthy(Duration timeout); }` (finding 10).
- Locks (unit 2.1 extension): `StateStore.withLockedSnapshot(LockedStateOperation<T>)` holds `state.lock` across a callback on a freshly read snapshot; `TransactionLock.tryAcquire(Path) -> TxnLease`, thread-owned, shared by transaction and recovery; order txn then state, enforced.

3.1 transport: `HttpSourceFetcher implements SourceFetcher` on `java.net.http.HttpClient` with `followRedirects(NEVER)` and manual redirects through `RedirectPolicy`; status 200 only; caps per hop; overall deadline. Tests use a fake server only where a local TLS fixture is authorized; otherwise fake `SourceFetcher`s drive everything else.

3.2 collection: `MetadataCollector.collectRevocations(ReleaseHosts, ReleaseEndpoints, SourceFetcher, CancelToken) -> HostCollection` and `collectManifests(...)`; all hosts, fixed caps (document cap per schema, `.sig` 4096).

3.3 acceptance (pure): `Acceptance.evaluateRevocations(HostCollection, Snapshot, Trust running, Instant systemNow) -> RevocationAcceptance(StateDelta delta, Map<String, VerifiedDocument<Revocations>> currentByHost, List<String> diagnostics)`; `Acceptance.evaluateManifests(HostCollection, Snapshot, RuntimeIdentity, UpdateVersion onDiskVersion, CheckMode mode, Instant systemNow) -> ManifestAcceptance(StateDelta delta, Optional<AcceptedCandidate> candidate, boolean stateFull, List<String> diagnostics)`, `CheckMode { REPORT, STAGE }`; `AcceptedCandidate(VerifiedDocument<ReleaseManifest> manifest, ManifestEvidence evidence, ReleaseEntry entry, List<ReleaseHost> artifactSources)`. Exact platform AND target match; version > max(running, on-disk); not blocked/withdrawn; STAGE also needs fresh revocations and 2-of-3 coverage. Orchestration runs each evaluation INSIDE `StateStore.commit` on the freshly read snapshot (revocations first, then manifests), never applies a delta computed before locking, and does no network under `state.lock` (finding 2).

3.4 artifact verification: `ArtifactVerifier.verify(Path stagedJar, AcceptedCandidate c, RuntimeIdentity id, UpdateVersion onDiskVersion, Snapshot current) -> StagedArtifact(Path path, ArtifactIdentity identity, Trust stagedTrust, String stagedKeySetFingerprint)`: size, SHA-512, bounded ZIP reads of exactly one `META-INF/quicraft/updater-trust.json` and one version-metadata entry (duplicates rejected), metadata agrees with the entry, never loads classes. `ArtifactVerifier.validateStagedTrust(Trust staged, Snapshot current)`: schema, exactly one revocation key EQUAL to persisted, no revoked pub, epoch not lower, equal epoch requires equal fingerprint; a different backup key is always rejected. `KeySetChangeAuthorization` is an additional requirement, never a waiver (finding 8).

3.5 staging and journal: `JournalRecord` (full schema docs/updater.md 817-848), `JournalCodec.encode/decode` (cap 8192, exact keys); `UpdateDirectory(Path updateDir)` with fixed `staging/<txid>.part|.jar`, `backup/<txid>.jar`, `journal.json`, `readJournal()`, `writeJournal(JournalRecord)` (atomic, fsync), `deleteJournal()`, NOFOLLOW/owner/0700 rules; fault-injection seam `DurabilityOps` (force, move, dir-fsync, journal write) so every boundary can fail in tests (finding 9). `Revalidation.compare(ArtifactIdentity staged, ReleaseEntry revalidated) -> RevalidationDecision { MATCH, RESTART_REQUIRED, REJECT }` (finding 6).

3.6 transaction: `UpdateAdmission.admit(AdmissionContext(boolean initComplete, boolean journalPresent, String liveSha512AtInit, String currentLiveSha512, boolean stateFull), RuntimeIdentity, Snapshot) -> AdmissionDecision`; `UpdaterTransaction.execute(UpdateRequest, CancelToken) -> TransactionResult` implementing 12.10 steps 1-6 (step 4 full re-pipeline incl. coverage, at most one restart with a new txid under the same lease; step 6 inside `withLockedSnapshot`: rebuild TrustSnapshot from running trust + fresh revoked state, re-authenticate `ManifestEvidence`, release authorization, freshness, floor, fresh revocations, entry binding, not blocked/withdrawn, `validateStagedTrust`, `KeySetChangeAuthorization.check`, re-hash, ATOMIC_MOVE, dir fsync, journal `swapped`); `cancelActive() -> CancelReply { REQUESTED, NO_ACTIVE_TRANSACTION, INSTALLED_RESTART_PENDING }`, atomic with entry into step 6; cancel never takes txn.lock. Cross-store check happens before any staging write. `VERIFIED_STAGING_ONLY` stops after step 3/5 checks with the jar staged. After a move may have happened, failures keep recovery evidence (finding 5, 9).

3.7 recovery: `StartupRecovery.run(RecoveryContext) -> RecoveryResult(RecoveryStatus status, Optional<RecoveryTicket> ticket, List<String> messages)`, `RecoveryStatus { IDLE, HEALTH_PENDING, RECOVERY_REQUIRED, DISABLED }`, never throws; ordered cases 1-7 of docs/updater.md 922-952 (discovery failure disables and touches nothing; invalid journal keeps every file; manual restore before phase dispatch; boot-pending attempt 1 written durably, attempt 2 allowed, then recovery-required). `completeHealth(RecoveryTicket, HealthSignal, Duration) -> CompletionStage<HealthCompletion>`: runs after init, re-acquires the txn lease, checks the journal still belongs to the ticket, deletes it and only EARLIER transactions' backups; timeout/failure keeps journal and backup (finding 10).

`LiveJarDiscovery { LiveJar discover() throws IOException, UpdateRejectedException; }`, `LiveJar(Path realPath, Path realScanDirectory, long size, String sha512, UpdateVersion onDiskVersion)`; implementations per platform (Wave 4) validate the original path for symlinks before resolving, scan-parent membership, unique id.

`ResetResult resetStateAndRefetch(...)` (Wave 3.6 owner): reset-state with an invalid revoked.json refetches and re-merges; staging stays disabled unless that succeeds; never authorizes an empty revocation state.

### Wave 4: platform adapters

- Starts by wiring `implementation(project(":updater"))` (the spike branch `agent/phase4-upd-spike`), so adapters build against it; Wave 5 only verifies packaging (finding 11).
- `velocity/`: commands `/quicraft update check|check -s|cancel|reset-state` and `/quicraft update` (spec table 55-63), permission `quicraft.admin.update` (+ `.notify`); config; `LiveJarDiscovery` via `PluginContainer.getDescription().getSource()`; preload trust file, version metadata and every own-jar resource (incl. native extraction/loading and service lookups) at init; off-thread checks and commands; hourly check-only task; shutdown cancels; post-swap checks use the cached `RuntimeIdentity`.
- `fabric/`: dedicated-only, same commands with OP level 3, `FabricLoader` origin discovery, config dir `config/quicraft/`, no activation on clients.
- Missing trust file or origins disables the updater with a WARN; TCP untouched. `InstallCapability` defaults to `VERIFIED_STAGING_ONLY` until the blocker-10 gate passes.
- Discovery tested on every supported Velocity and Fabric build (blocker 16). JDK 21 / macOS evidence via a CI job on the github mirror: prepared in this plan, pushed/run only after asking the user (finding 12).

### Wave 5: packaging and integration

- Verify the shaded/nested updater in all three jars, shaded-notice check extended to the updater.
- Testkit: a mock release server with precomputed throwaway-key-signed documents and jar; a server stages, (where capability allows) swaps, restarts, and still accepts TCP and QUIC joins.

## Waves and executors

Executor choices: default-coder for crypto-adjacent and policy units needing judgment; cheap-coder only for mechanical fixtures once contracts are frozen; default-tester or cheap-tester for tests (at most one llama.cpp tester at a time, user constraint). Every unit: own branch `agent/<slug>`, own worktree, verifier PASS then peer review, and security review for Wave 1 to 3.

### Wave 0: freeze and invariants (orchestrator; advisor critique done, superior critique at 2.5)

Freeze the security invariants above, the Wave 1 and 2 contracts, lock ordering, the durable commit points and the crash/recovery matrix, the fixture strategy, and the module wiring. Deliverable: this plan updated to `approved` plus the crash/recovery matrix appended.

### Wave 0.5: packaging and bootstrap spike (explorer or default-coder, scratch worktree, nothing committed)

Feasibility spike, read-only then scratch: prove where Java 21 verification, state and recovery code runs relative to loader jar loading on Velocity and on the Fabric dedicated server, and that the updater classes can load in both without a loader-specific classpath problem. Confirm the shade and jar-in-jar wiring builds and the classes appear in the shipped jars. No network, no swap. DONE WHEN: a short evidence note with file:line and build output exists; STOP if a loader cannot load the module.

### Wave 1 (after Wave 0 types; partly parallel)

Order: 0T (Wave 0 types, default-coder, small) merges first; then 1.1; then 1.2, 1.3 and 1.4 in parallel on top of 1.1.

- 1.1 `agent/phase4-updater-keys` (default-coder): `KeyRole`, `TrustedKeyRoles`, `KeySetFingerprint`, `TrustSnapshot`, `Ed25519`. Tests 1.1T (default-tester): RFC8032 vectors for `Ed25519` (no key generation), fingerprint order and role-bit sensitivity, snapshot capability sets.
- 1.2 `agent/phase4-updater-signers` (default-coder): `VerifiedSigner(s)`, `EnvelopeStatus`, `SignatureVerifier`, `VerificationOutcome`, `VerifiedDocument`, `DocumentVerification`, `AuthenticatedDocuments`. Also `KeySetChangeAuthorization`. Tests 1.2T: torn (payload-hash mismatch) versus bad signature versus no valid signer, unknown plus valid, invalid plus valid, both-valid retained, uppercase/length mismatch, non-canonical S and wrong-length pub/sig rejected before the JDK call, snapshot binding; key-set change with release-only signers refused, with release plus current backup accepted, with a revoked backup refused, one backup signature satisfying both roles, unchanged fingerprint needing no backup.
- 1.3 `agent/phase4-updater-trust` (default-coder): `PersistedTrust`, `Transition`, `TrustDecision`, `TrustTransition.classify/apply`. Tests 1.3T: all six transitions, transition 6 old-revocation pub revocation and permanent-union preservation, invalid same-epoch fingerprint, deterministic delta.
- 1.4 `agent/phase4-updater-freshness` (default-coder): `FreshnessPolicy`, `ClockFloor`. Tests 1.4T: future tolerance, expiry boundary, clock behind and ahead, revoked-key contribution dropped, structural lifetimes, no network time.

### Wave 2 (after Wave 1 merges)

- 2.1 `agent/phase4-updater-state` (default-coder): `RevokedState`, `UpdaterState`, `StateDelta`, `StateCodec`, `RevokedCodec`, `StateStore`. Tests 2.1T: round-trip, deterministic encoding, crash after `revoked.json` but before `state.json` converges on the next commit, per-file `.prev` fallback with WARN clamped to trust-file floors, both-invalid disabling staging or the updater, `resetState` keeping `revoked.json`, same-JVM double commit serialised by the in-process guard (no `OverlappingFileLockException`), other-process lock refused, lock ordering, permissions and symlink refusal.
- 2.2 `agent/phase4-updater-sequence` (default-coder): `SequencePolicy`. Tests 2.2T: floor with revoked keys, equivocation, restriction union, seed selection and the named gaps, coverage 2 of 3 distinct origins, check-only advancing floors and making an earlier staged manifest ineligible.

### Wave 2.5: freeze Wave 3 to 5 (orchestrator plus superior critique)

Freeze Wave 3 to 5 signatures, the fetch and transaction contracts, and the crash/recovery test matrix. Independent superior critique of the frozen interfaces before Wave 3 assignment. Update the plan, then brief.

### Wave 3 (after Wave 2 merges; order per superior critique)

1. 3.0 shared records/interfaces above (small, first).
2. In parallel: 3.1 transport, 3.3 pure acceptance, 3.4 artifact verification, 3.5 staging/journal/durability seam.
3. 3.2 collection (against the frozen fetch interface; may start with 3.1).
4. 3.6 transaction and 3.7 recovery in parallel after 3.5.
Each unit gets fault-injection tests: truncated download, oversized bytes, redirect denied by default and allowed only by a test policy, redirect to HTTP refused, hop limit, overall deadline, torn pair, corrupted journal, wrong platform/target, changed live jar, manual restore, cancel at each step and racing step-6 entry, key-set change without backup signature refused at final locked authorization, revocation merged between step 5 and step 6 stopping the swap, clock advanced past expiry before step 6, cross-file-store refusal before any staging write, failure at every durability boundary.

### Wave 3.5: crash/recovery matrix gate (before adapters)

Test every journal boundary, a crash between the two state-file writes, stale trust snapshots, a revocation-key replacement, a key-set change whose backup key is revoked between staging and swap, cancellation, and restart with the old and the new jar. PASS required before Wave 4.

### Wave 4: adapters

Velocity and Fabric adapters as above, own branches, adapter-specific tests plus the required swap-then-read loader test (`docs/updater.md:1018-1019`). Linux runs locally. JDK 21 and macOS run on a GitHub Actions job on the `github` mirror (`macos-latest`, Temurin 21 and 25), prepared in this wave; push and run only after asking the user; the job's result is the gate, not a local claim.

### Wave 5: packaging and integration

Packaging, shaded notices, testkit mock-update restart run, and the existing TCP/QUIC fallback gates.

## Test fixture strategy

Two layers, no owner keys:
- Cryptographic primitive: official RFC8032 test vectors (published public key, message, signature) test `Ed25519` and `SignatureVerifier` with no key material generated.
- Authenticated pipeline: signed-JSON fixtures need a signature over JSON bytes we control. APPROVED (user, 2026-10-13): one throwaway Ed25519 keypair whose private key is a published RFC8032 vector seed, used once outside the repo to precompute `(json bytes, signature, public key)` triples committed as test resources. Test data, not an owner key, not a release ceremony. The seed, message and signature are committed with the fixture so any reviewer can reproduce the signature independently.

## Resolved decisions (user, 2026-10-13)

1. Test fixture signing: approved, the throwaway RFC8032 vector approach above.
2. Hosting origins and paths (blocker 5): fetcher origins stay injected and offline until the user supplies real origins. Mock server only in tests.
3. Windows: the post-exit helper is deferred to a future plan. Windows stops at `verified-staged` in this one.

## Risks

- Crypto and authorization mistakes: lane separation, default-coder plus mandatory security review, no network or swap in Wave 1 and 2.
- Local state rollback or corruption silently weakening protections: invariants 4 and 5, revoked-first monotone merges, and the Wave 2.1 tests.
- Two-file crash window: idempotent monotone merges (invariant 4) plus the Wave 3.5 crash matrix.
- Loader resource mixing after swap on Velocity: the Wave 0.5 spike plus the init preload barrier and the swap-then-read test.
- Redirect and health placeholders mistaken for final policy: both injected, defaults deny/provisional, release gate open (non-goals).
- Key-set change without backup authorization: invariant 10, one shared check at three points, 1.2T and the Wave 3.5 matrix.
- Host coverage mistaken for signer independence: invariant 9 and its test.
- Scope creep into hosting and publishing: non-goals and injected origins.

## Rollback

Each wave lands on its own branch and merges only after gates. Reverting a wave is `git revert` of its merge commit. The updater is inert until platform wiring lands, and inert again if its trust file or origin is missing. No installed jar is touched by any test in this plan; the swap runs only in the testkit sandbox.

## Verification

Per unit: fresh verifier (`:updater:check build --rerun-tasks --no-build-cache`) with counted XML; peer review; security review for Wave 1 to 3. Per wave: fresh root `./gradlew build --rerun-tasks --no-build-cache`, SPDX, license, shaded notices, class major 65 for updater and 52 for core. Integration: the testkit mock-update restart run plus existing TCP/QUIC fallback gates. Rejected or denied commands are reported, never bypassed.

## Progress and roster

- Review fixes applied 2026-10-13: backup co-sign check, Wave 0 types unit, spec-aligned state store, injected redirect policy, `reset-state` and cross-store refusal assigned, health placeholder, `UpdateVersion` args, macOS/JDK21 CI route, in-process lock guard.
- Wave 0 (this plan): approved, advisor critique incorporated, open decisions resolved (fixtures approved, origins injected, Windows deferred). Awaiting the user's go-ahead to start Wave 0.5.
- 2026-10-13 execution (orchestrator). Merged on feat/phase4-alpha after verifier PASS + peer approve: Wave 0 types (1a2df88), 1.1 keys (e2ad90e), 1.4 freshness (2b76dee), 1.3 trust (a196461), 1.2 signers (643cfad). Merged updater suite: 387 tests, 0 failures. Wave 0.5 spike PASS, kept unmerged on `agent/phase4-upd-spike` (4a87986, note `.agents/plans/spike-upd-0.5.md`) for Wave 5.
- Later merges: Wave 2.1 state (57c1580), 2.2 sequence (2c330ae), Wave 1 hardening from the Wave 1 security review (e5ea9b3), 3.0 shared (57e5699). 499 updater tests green before 3.0; Wave 1 security review: no critical/high/medium, 3 lows fixed.
- ChatGPT usage limit hit 2026-10-13: Sonnet stands in for peer-reviewer and superior (user decision).
- Decisions by orchestrator (report to user): build metadata resource `META-INF/quicraft/build.json` {type quicraft.build, v 1, platform, version}; release hosts are LDH DNS names or exactly `localhost`; throwaway TLS fixture `updater/src/test/resources/tls/test-localhost.p12` for transport tests; Windows DurabilityOps fails closed on directory fsync (3.6 must allow VERIFIED_STAGING_ONLY staging there or document it).
- Running: 3.1 transport, 3.3 acceptance, 3.4 artifact (default-coder); 3.5 journal gates; Wave 2 security review.
- Test keys: RFC 8032 TEST 1-3 seeds only, test sources only (throwaway, per user). Owner keys replace them after implementation; user to be told.
- Grounding explorers: `ses_ec90bf527ffewKBKlh3oIN8CLf` (platform wiring), `ses_ec90bf445fferbnM9Eq2V7R7S5` (state/verification gaps). Both done, read-only.

## Advisor review

Advisor `ses_ec901f029fferSuXasStZoUdgO`, one question, no tools. Recommendation: keep Waves 1 and 2 separate with security review; use one logical state store for both files. Incorporated:

1. Wave 0 now freezes invariants, durable commit points, lock ordering and a crash/recovery matrix, not only signatures.
2. Persistence moved to an explicit generation-consistent pair commit; a two-file transaction is defined, not assumed from two renames. (Superseded 2026-10-13 review: independent files, revoked first, monotone merges; invariant 4.)
3. Corruption recovery versus malicious local rollback separated as invariant 5, with refusal to lower floors or drop revocations.
4. Added Wave 0.5 packaging and bootstrap feasibility spike before Wave 1.
5. Wave 1.1 split into key validation and fingerprint (1.1) and exact-byte verification and signer evidence (1.2).
6. Signer evidence bound to a `TrustSnapshot`; policy never combines epochs (invariant 2).
7. `TORN` redefined as a bounded refetch hypothesis only, never a relaxation (invariant 3).
8. Trust transitions split into pure classification and deterministic delta application (1.3).
9. A single `StateDelta` shared by freshness, sequence, selection and coverage (invariant 6).
10. Added an explicit artifact-verification unit (3.4).
11. Wave 3 split into seven fault-injectable units.
12. Coverage defined over distinct origins with identical content; not signer independence (invariant 9).
13. Filesystem safety folded into invariant 10 and Wave 2.1 tests.
14. Check-only floor advancement stated explicitly and tested (invariant 8, unit 2.2T).
15. Added Wave 3.5 crash/recovery matrix gate before adapters.

Rejected from the advice: none. The advisor's caveat that it had not read section 12 was handled by validating each point against the cited spec lines; the spec already covered locks, `.prev`, generation, journal phases, permanent union and coverage, so those became units and tests rather than new design.

After the critique, the user resolved the three open decisions: fixtures approved, origins injected, Windows deferred to a future plan.
