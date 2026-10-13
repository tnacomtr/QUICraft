# Plan: approved Phase 4 updater implementation

Status: in progress | Owner: orchestrator

## Goal

Implement the approved server-only updater in independently reviewed waves and set the initial congestion window to 64 packets. Continue `feat/phase4-alpha` from `0c04268`; this is not release authorization.

## Non-goals

No push, publication, deployment, PR, generated signing keys, live updater requests, client updater, hot code activation, or deletion of retained worktrees. Preserve all pre-existing untracked user/config files.

## Context and approved decisions

- Listener hardening is already merged; the handoff records full-build and Fabric/real-client gates. Do not redo implementation or confuse historical evidence with fresh evidence.
- User approves 30-day manifests / 14-day refresh; 365-day revocations / yearly backup signing; 2-of-3 staging coverage; console `reset-state`; cross-filesystem fail-manual; backup authentication for changed key sets; cwnd 64 with override.
- Clarification: manual restore, no rollback command; on-disk atomic replacement while running, activation after restart; own-jar resources preloaded. JDK 21/macOS remain release gates.
- `core` stays Java 8. Add loader-independent `updater/` Java 21 module using existing conventions (JDK 25 toolchain, `options.release=21`). JDK crypto and collections only, no production dependencies.
- Strict schema / crypto / clock contracts: `docs/updater.md` 12.2-12.5; persistence 12.8-12.11; verifier ordering 12.12; loader restrictions 12.13-12.14. Paths and publishing endpoints still need resolution; do not invent public project IDs or enable traffic.

## Decision

Choose a separate updater module, not core (Java 8 incompatible with Ed25519) and not duplicated platform implementations (drifting security rules). Keep native transaction and platform activation behind subsequent safety gates rather than exposing unfinished commands.

For key-set changes, use the existing detached signature envelope (up to two signatures over the exact manifest bytes). Staged-jar validation computes the candidate fingerprint itself; no untrusted flag can waive the requirement. If changed, require a verified signature from the current, non-revoked backup/revocation key as well as release authorization. A backup-signed emergency release may satisfy both role checks. This semantic v1 extension needs independent critique/security review before implementation; no new bytes have been published.

## Contracts

Package `rs.sudoe.quicraft.updater`. This wave implements disconnected verification primitives, not a runtime updater.

- `UpdateRejectedException extends Exception`: checked fail-closed rejection with bounded fixed messages, optional cause; never log supplied document contents.
- `StrictJson.parse(byte[] bytes, int maxBytes, int maxStringChars)` returns an immutable `Map<String,Object>` root. Values: immutable maps/lists, `String`, non-negative `Long` up to 9007199254740991, `Boolean`, or null. UTF-8 REPORT mode; reject BOM; JSON whitespace only; unique decoded keys; depth <=4 (root container=1); no signed/fraction/exponent/leading-zero numbers; valid Unicode scalar strings including escaped surrogate pairs; max decoded string code units supplied per document (normally256, journal4096). Generic array/object cap1024; schema supplies narrower limits. Reject non-object root and trailing bytes. No recursive mutable object escapes.
- `UpdateVersion.parse(String)` / `compareTo`: numeric triple, optional alpha/beta/rc and numeric suffix, each0..99999 with no leading zeros. Stable above rc above beta above alpha. No build metadata.
- Immutable schema records in `UpdateDocuments`: `TrustedKey(kid,pub,roles)`, `Trust(epoch,built,revocationsSeqFloor,manifestSeqFloor,keys)`, `SignatureEntry(kid,sig)`, `SignatureEnvelope(payloadSha256,signatures)`, `ReleaseEntry(platform,version,targets,file,size,sha512,tag)`, `Withdrawal(platform,version,sha512)`, `ReleaseManifest(seq,issued,expires,minRevocationsSeq,releases,withdrawn)`, `RevokedKey(kid,pub)`, `Revocations(seq,issued,expires,minManifestSeq,revokedKeys,blockedArtifacts)`; roles/platform are exact schema strings; collections copied defensively. Timestamp type `Instant`, version `UpdateVersion`, numeric values long except epoch int.
- `DocumentParser.parseTrust/parseSignatures/parseRelease/parseRevocations(byte[])` enforce exact fields, types, encodings, limits and uniqueness at every level; no signature or freshness claim from successful parsing. `Hex` owns SHA-256 kid derivation in the schema wave. Reject duplicate signature kids; trust kids/pubs/roles; release platforms; per-entry targets; withdrawn `(platform,version)` pairs or hashes; revoked pubs/kids; blocked hashes. Reject even a short-kid collision between different full keys. Empty roles are invalid; roles are `release` and/or `revocation`, exactly one trust key has `revocation`. Calendar-valid UTC timestamps reject leap seconds/24:00 normalization. Schema-specific string limits remain enforced even if journal parsing later allows4096.
- `SignatureVerifier` later verifies exact raw bytes and returns all actually valid authorized signer keys, not a boolean or arbitrary first signer. Persisted full-public-key revocations are inputs. Torn payload mismatch is distinct from bad-signature rejection. Carry all signers through revalidation and final locked authorization; recheck backup requirement there. Co-signing never authorizes replacement of the revocation key. Freshness and sequence state remain separate.
- Current revocation selection and freshness are separate: expired documents may become current under the seed/sequence rules, but never provide fresh coverage. Documents issued more than24h ahead only add signed restrictions; they cannot become current or contribute to the clock floor. All hosts are processed before selecting the highest eligible seed response, independent of host order.

## Waves

### Wave 3A: first implementation slice

1. Orchestrator: record approvals; module scaffold and checked exception; freeze this plan and selected schema records after critique. No platform shipping integration yet.
2. `agent/phase4-cwnd-default` (cheap-coder): only `TransportConfig.java` and protocol table. Absent/malformed/out-of-range property ->64; explicit0 ->native default;1..1000 accepted; builder override unchanged.
3. `agent/phase4-cwnd-tests` (one cheap-tester): only `TransportConfigTest.java`; absent/invalid/bounds/trim/per-builder/property-restoration tests. Independent isolated base red/fixed green proof, no production edits by tester.
4. `agent/phase4-updater-json` (default-coder): only `StrictJson.java` and `UpdateVersion.java`, compile module; exact contracts above. No crypto, filesystem, HTTP or platform changes. Paired default-tester owns only `StrictJsonTest.java` and `UpdateVersionTest.java` after parser commit; gate both before schema assignment. Immutable JSON preserves nulls using defensive unmodifiable wrappers rather than null-rejecting copyOf helpers.
5. `agent/phase4-updater-schemas` (default-coder, after parser integration): only `UpdateDocuments.java`, `DocumentParser.java`, `Hex.java`; exact reviewed schemas. Test unit follows using default-tester so llama slot remains singular.

### Wave 3B: authenticated verification

After schema/test gates: signature checks, fingerprint/kid derivation, staged trust authorization and pure freshness policy, plus tests using public RFC8032 fixtures (no generated signing keys). Finalize exact public method signatures and independent security critique before assigning.

### Later dependent waves

- Persistent security state: per-key floors, restriction unions, deterministic seed acceptance, trust transitions, coverage. Then confined two-lock state persistence and durable transaction/recovery with crash tests.
- Fetch policy: explicit fixed origins and approved paths; bounded reads, no automatic redirects; mirror failures cannot drop revocation coverage. Resolve metadata hosting without contacting endpoints. Offline signing validation tool, no key generation or signing ceremony run.
- Platform adapters: dedicated-only gate, source-jar discovery, init/preload barrier, command authorization, hourly disclosure/check-only scheduling. Wire only after complete verification/transaction contracts exist; missing trust/endpoints disables updater safely, TCP unaffected.
- Windows post-exit helper and exact-loader JDK21/macOS tests; owner docs and release checklist. Untested platforms remain release-blocked.

## Risks and rollback

Unreviewed co-signature semantics, malformed parser input, filesystem TOCTOU and loader resource mixing are security risks. Keep primitives disconnected, require red/green tests and independent review, and resolve findings before integration. Retain branches/worktrees, never discard user work or reset the root. No update transaction runs against installed jars in this session.

## Verification

Fresh independent verifier, then mandatory peer review, then security review on sensitive units before merge. Per-module `check build --rerun-tasks --no-build-cache`, root SPDX and full integrated build after the slice. Verify Java21 updater and Java8 core class versions. Regression proof runs in separately named orchestrator-owned worktree; copy test files only, retain logs outside it. Historical platform gates must not be claimed as fresh cwnd/updater gates.

## Progress and roster

- Base `0c04268`; initial tracked root state clean, unrelated untracked paths preserved.
- Read-only explorers `ses_ec9656e43ffe8jlaghBR5EIHAZ`, `ses_ec9656e0fffe79SH67gVjNRsnq`, `ses_ec9656e0fffdWdvcSrduo1eRfS`: done; mapping used above.
- `ses_ec9639f77ffeahtN2pbVOh1yKN`, cheap-coder, cwnd, `agent/phase4-cwnd-default` / `.worktrees/phase4-cwnd-default`: author reports compile and commit `e5d3600`, awaiting tests and gates. Agent did not pause at requested pre-edit checkpoint; future briefs must reinforce checkpoints.
- `ses_ec9612e57ffeEZ343UoaLOJvxS`, cheap-tester, cwnd tests, `agent/phase4-cwnd-tests` / `.worktrees/phase4-cwnd-tests`: STOP confirmed. Quarantined `c7b8536` because it added an unapproved `core/src/test/java/rs/sudoe/quicraft/core/transport/EVIDENCE.md` and ignored the pre-edit checkpoint. Branch/worktree retained, never merge or delete. Only the authorized test file may be salvaged to a fresh branch; evidence must be regenerated independently. Local cheap tester slot is free.
- Remaining units not yet assigned; at most six concurrent units, exactly one writer per branch/file set.
- Independent critique `ses_ec95dedfbffeCpRwug3e0XHiqB` approved disconnected first slice; identity, kid-helper ownership, null handling, timestamp and revocation acceptance ambiguities corrected above before later-wave assignments.
