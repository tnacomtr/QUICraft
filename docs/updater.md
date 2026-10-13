# Server updater (specification)

**Status: in-progress specification. Not implemented. Not approved for release.** Nothing here is a
frozen wire format or a shipped feature. Release is blocked until the blockers in section 10 are
resolved. Section 12 is a PROPOSAL for blockers 1, 2, 3, 4, 6, 12 and 17, pending user sign-off.

Local input, not a link: the user's draft is `docs/.plans/updater-plan.md` (untracked, local only,
not committed). This document is self-contained. Where it differs from the draft, the difference is
recorded here. The Phase 4 plan that sets this contract is
`.agents/plans/2026-10-13-phase4-alpha.md` (Updater contract, Unit 1.4).

## 1. Scope and targets

- **In scope (planned):** the Velocity plugin and the Fabric dedicated server. Not the client.
  Paper is a later phase.
- **Build targets (current, from the build files):** core is Java 8 bytecode
  (`core/build.gradle.kts`). The Velocity plugin compiles for Java 25 because velocity-api 4.2.0
  requires it (`velocity/build.gradle.kts:14`). Fabric 26.x uses `javaRelease=25`
  (`fabric-26x/gradle.properties`). Fabric 1.21.11 uses `javaRelease=21`
  (`fabric-1.21.11/gradle.properties`). The draft's "Java 17+" is superseded by these targets. It
  is not a blocker.
- The updater lives in the platform modules, not in `core`.

## 2. Approved user choices (preserved)

| Choice | Meaning in this spec |
| --- | --- |
| Release sources | Modrinth is primary. GitHub and `git.sudoe.rs` are also supported. |
| Scheduled checks | Hourly checks only, by default. A check never downloads a jar. |
| First-start disclosure | The plugin says once on first start which hosts it contacts and which key turns checks off. |
| Bad-signature output | One console WARN per distinct bad manifest by default (`update.log-bad-signatures`). |
| Live replacement | Atomic `ATOMIC_MOVE` replacement of the live jar on Linux and macOS stays in the design. Release is blocked until loader-generation safety and crash-recovery evidence exist (section 10). |
| Process scope | Velocity and Fabric dedicated servers only. |

If atomic replacement is unavailable on a platform, the updater fails safe: the installed bytes and
the backup are kept. Showing manual install or recovery instructions is allowed. A silently
manual-only updater instead of this design is not allowed.

## 3. Commands and permissions (draft, not registered)

Console, or a player with `quicraft.admin.update`:

| Command | Effect |
| --- | --- |
| `/quicraft update check` | Reports the running version and the latest compatible one. |
| `/quicraft update check -s` | Same check, silent mode (section 5). |
| `/quicraft update` | Downloads, verifies, and stages the new jar. |
| `/quicraft update cancel` | Cancels a download or a staged update (section 5). |

- Nothing is downloaded or installed automatically. A scheduled check only reports.
- Velocity has no OPs. Its notify permission is `quicraft.admin.update.notify` (draft).
- Fabric: operators at permission level 3 or higher, or a permission node if a permissions mod is
  installed (draft). The exact permission integration is unresolved.

## 4. Keys, freshness, and revocation

- **Keys.** Two Ed25519 pairs: a primary key (signs releases; offline; never in CI) and a backup key
  (signs nothing in normal operation; offline media; used only for recovery). Both public keys ship
  in every jar with short key IDs.
- **Signing tool.** A planned repo script that uses the JDK's Ed25519. It does not exist yet. The
  wire format of `release.json` and `release.json.sig` is unresolved.
- **Signed content.** The signature covers the manifest. The draft's fields: version, platform,
  game-version range, file name, size, SHA-512 of the jar, release date, signing key ID.
- **Freshness (both required).** The updater requires both:
  1. a signed, monotonic sequence number, with the highest value seen persisted, so an older
     manifest is never accepted after a newer one; and
  2. signed issued and expiry times, so a stale manifest is rejected.
  Schemas, sequence field encoding, expiry lengths, and clock-skew thresholds are unresolved.
- **Verification rules (specified, not implemented).** Verification runs over the exact received
  bytes. Parsing is size-bounded and rejects duplicate fields. A downgrade (version lower than or
  equal to the running one) is refused.
- **Revocation.** Revocation is a backup-key-signed `revocations.json`. The updater attempts all
  three sources (Modrinth, GitHub, `git.sudoe.rs`) on every check and applies a valid revocation from
  any source.
  - **Revocation freshness (required, same standard as manifests).** Revocation metadata is signed
    and carries a monotonic backup-key or trust-epoch sequence, plus issued and expiry times. The
    updater persists the highest accepted revocation metadata and never accepts an older one. A
    stale revocation replay is never treated as fresh. Persisted revoked key IDs form a permanent
    union: a later response that omits one does not undo it.
  - **Coverage rule (open):** fallback to an artifact or mirror source must never weaken fresh
    revocation coverage. The exact coverage rule for a source that fails is unresolved.
  - Encodings, clock policy, and required coverage remain unresolved (section 10).
- **Rotation.** The trusted key set ships inside the jar. Primary lost: the next release is signed
  with the backup key and carries a new key set. Primary leaked: publish a backup-signed revocation,
  then a release signed with the backup key and a new key set. Both lost or leaked: owners install
  the next release by hand, and the README documents this.
- **Not protected:** the first install (trust is the source the owner used), and a signer machine
  compromised while the key is on it.

## 5. Checks versus updates: states and flow

### States

Required states (from the Phase 4 plan): `idle`, `checking`, `downloading`, `verified-staged`,
`backup-ready`, `installed-pending-restart`, `health-check-pending`, `healthy`, `recovery-required`.
The plan lists the states. It does not define a full transition table; the flow below is the draft
intent, and the table is part of the open design.

The state records the **running** version (in the JVM) and the **on-disk** version separately. A
replaced file does not change the running version until restart.

### Scheduled check (report only)

`idle` -> `checking` -> `idle`. The check fetches the revocations, the manifest, and its signature.
It never downloads or writes a jar. Checks run off the server thread and the network threads. A
completed check returns to `idle`, whether or not an update exists.

### Explicit update

1. `idle` -> `checking` (fetch and verify manifest, freshness, and revocations).
2. `checking` -> `downloading`: a write to a private, confined, exclusively created staging path. Size
   cap and timeout, HTTPS only, fixed hosts. No path traversal and no write through a symlink.
3. `downloading` -> `verified-staged`: size and SHA-512 match the signed manifest. Revalidate
   manifest, key, freshness, and version immediately before any swap.
4. `verified-staged` -> `backup-ready`: copy the current jar to a backup and write a journal entry.
   Both are fsynced before the live replacement starts.
5. `backup-ready` -> `installed-pending-restart`: atomic `ATOMIC_MOVE`
   (draft: `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`) of the verified staged jar over the live
   jar on Linux and macOS. The running jar is never rewritten in place. The JVM loads classes lazily,
   so an in-place rewrite can cause `NoClassDefFoundError` or a crash.
6. `installed-pending-restart` -> `health-check-pending` -> `healthy` -> `idle`: after restart, the
   new generation reports against platform health criteria. The recovery backup is retained
   unconditionally until those criteria pass. The draft's rule of deleting `.bak` on the next
   successful start is superseded by this rule. Only the health criteria and their mechanism are
   unresolved (section 10).

### Cancel and failure

- **Cancel during `downloading` or `verified-staged`** -> `idle`. Only the staged file created by
  that operation is deleted. The installed jar and the recovery backup are never deleted.
- **Failure before the live swap** (download, verify, revalidation, backup, or journal fails) ->
  `idle`. The installed bytes are unchanged, and the staged file is removed.
- **Failure during or after the swap** that leaves the installed state unclear -> `recovery-required`.
  The backup and journal are kept, and the defined recovery procedure runs on next start. The
  recovery procedure is unresolved.
- Cancellation does not roll back an installed generation on its own.

### Concurrency

Operations are serialized by a process lock and a file lock. Only one transaction runs at a time.
Lock file locations and journal format are unresolved.

### Installed jar discovery and placement

- **Discovery.** The installed jar is found through platform-validated loader installation metadata,
  not by guessing a file name. `getProtectionDomain().getCodeSource()` alone is not sufficient for
  Fabric, so it is not the sole method. Exact loader-specific discovery is unresolved.
- **Validation.** The resolved origin must be exactly one canonical, writable, top-level supported
  jar. Reject nested jars, dev or build-output paths, ambiguous origins, and symlink destinations.
- **No duplicates.** Never leave two loadable QUICraft jars in `plugins/` (Velocity) or `mods/`
  (Fabric). Velocity refuses duplicate plugin IDs.
- **Placement.** Backup and staging files live outside loader-scanned directories. Exact paths are
  unresolved for each loader.

### Windows

The draft proposes a post-exit helper that waits for the server to exit and then swaps jars, with
manual steps logged if the helper cannot start. Its architecture is unresolved and unverified. A
Windows live updater must not ship until this is resolved.

## 6. Configuration, disclosure, and output

### Config (conceptual; `.properties` integration pending)

```
update.check-interval: hourly  # hourly (default) | daily | off
update.check-silent: true      # same as -s
update.log-bad-signatures: true
```

Real key names and loading are pending. Velocity config lives in `plugins/quicraft/config.properties`.
Fabric config lives in `config/quicraft/server.properties`. The draft's `update/` paths under
`plugins/quicraft/` are Velocity-only. The Fabric staging and state paths are unresolved.

### Disclosure

Each check sends the server's IP, the running version, and the platform to the release hosts. The
plugin says so once on first start, naming the hosts and the key that turns checks off. The README and
project pages must carry the same disclosure before release. Neither is present yet.

### Silent-mode output (draft)

| Result | Chat / OPs | Console |
| --- | --- | --- |
| No update | nothing | nothing |
| Host unreachable or timeout | nothing | one DEBUG line |
| Bad or untrusted signature | nothing | one WARN per distinct bad manifest |
| Valid, compatible update | notify OPs | one INFO line |
| Valid update for another game version | nothing | nothing |
| New backup-signed revocation | notify OPs | WARN |

Without `-s`, every result goes to whoever ran the command. Pacing (random hourly offset,
conditional requests, backoff on host failure) is a draft intent; parameters are unresolved.

### Notifications

Once per version. The last announced version is stored in a state file (path unresolved; the
draft's `update/state.json` is Velocity-only). Offline OPs get the notice on next join. The draft's
message is an example, not a release.

## 7. Safety and fail-safe requirements

These are requirements for the design. None is proven by implementation.

- Pre-swap errors are caught and logged. The installed jar bytes are unchanged.
- Post-swap errors keep the backup and journal and enter the defined recovery state. They never
  delete the backup.
- The updater never loads or runs the new jar in the current process.
- Startup and TCP joins are never affected (`CLAUDE.md`: every hook fails safe).
- The scheduler stops cleanly at shutdown.
- **Dedicated-server gating (unresolved).** Fabric's `fabric.mod.json` declares a common `main`
  entrypoint (`rs.sudoe.quicraft.fabric.QuicraftFabric`) that runs on clients too, plus a separate
  `client` entrypoint. The mechanism that keeps the updater out of clients is not decided here.

## 8. Test requirements (draft list, not yet written)

- Signatures: valid primary accepted; unknown, bad, and revoked keys rejected; backup-signed
  revocation overrides primary; persisted revocation survives an omitting response; backup-signed
  release installs a new key set.
- Freshness: an older sequence is rejected after a newer one; an expired or future-issued manifest
  is rejected.
- Manifest: wrong platform or game version, downgrades, and hash mismatch are rejected.
- Applying: atomic swap; rollback from backup; failure partway through download; failure after swap
  enters recovery.
- Silent mode: no output for no update, host failure, or wrong game version; one WARN per bad
  manifest; a revocation notifies OPs even in silent mode.
- Schedule: `hourly` default; `daily` and `off` honored; one first-start disclosure line.
- Fail-safe: an exception inside a check never reaches the server.
- Integration (testkit): Velocity updates from a mock release server signed with test keys,
  restarts, and still accepts TCP and QUIC joins.

## 9. Owner responsibilities

- Keep the primary key offline (owner's machine or hardware key). Keep the backup key offline on
  separate media, plus a paper copy. Private keys never enter the repo or CI.
- Verify a release by hand from the signed manifest once the README documents how.

## 10. Unresolved release blockers

1. Manifest, `release.json.sig`, key-set, and `revocations.json` wire formats. (Proposed: 12.2, 12.3.)
2. Signed-byte, bounded-parse, duplicate-field, and sequence/expiry encodings. (Proposed: 12.3, 12.4.)
3. Freshness thresholds and clock policy. (Proposed: 12.5.)
4. Revocation coverage rule when a source fails (fallback must not weaken fresh coverage). (Proposed:
   12.6.)
5. Exact endpoints, CDN redirects, and the role of `git.sudoe.rs` in source priority.
6. Release sequencing and withdrawal. (Proposed: 12.7.)
7. Dedicated-server gating mechanism (section 7).
8. Permission integration for Fabric and Velocity, and command registration.
9. Platform health criteria and mechanism (backup retention until they pass is unconditional).
10. Loader-generation safety for live `ATOMIC_MOVE` replacement on Linux and macOS, with crash-recovery
    evidence. Release is blocked until it exists.
11. Windows post-exit helper design and verification.
12. Lock file locations, journal format, and recovery procedure. (Proposed: 12.8.)
13. Disclosure text in the README and on project pages (required before release).
14. Hosting-rule re-checks (section 11).
15. Signing tool, key generation, and the offline key process (see `release.md`).
16. Loader-specific installed-jar discovery, and backup and staging locations outside loader-scanned
    directories (section 5, "Installed jar discovery and placement").
17. Revocation metadata encodings, clock policy, and required coverage (section 4). (Proposed: 12.3,
    12.5, 12.6.)

## 11. Hosting rules (re-check required)

- Modrinth: the draft cites the "clear disclosure" rule for software that contacts a remote server.
  Re-read the live rules before release. Not verified here.
- Hangar: the draft found no rule on runtime downloads but could not fetch the page. Re-read before
  the first Hangar upload.
- Rules change without versioning. Re-check before the first release that ships the updater.

## 12. Proposal (pending user sign-off)

**Status: PROPOSED.** Nothing in this section is approved, frozen, or implemented. It proposes
answers to section 10 blockers 1, 2, 3, 4, 6, 12 and 17 so they can be reviewed. Blockers 5, 7-11
and 13-16 stay open; where this section depends on one, it says so. Every choice gives a one-line
reason and the rejected alternative. Items marked **USER DECISION** need the user's answer; the
recommendation is given.

### 12.1 Threat model this proposal answers

- Hosts (Modrinth, GitHub, `git.sudoe.rs`) are untrusted transport. Any one of them may serve old,
  altered, withheld or oversized bytes. HTTPS only protects the connection to that host.
- Rollback: an older validly signed manifest replayed after a newer one.
- Freeze: a validly signed manifest replayed for a long time so no update or withdrawal is seen.
- Mix-and-match: a valid signature or hash for one artifact, platform or document type applied to
  another.
- Key compromise: a leaked primary key, recovered with the backup key (section 4).
- Clock skew on the server, in either direction.
- Not answered (unchanged from section 4): first install, signer machine compromise. A server that
  already installed a jar signed by a leaked key runs attacker code, which can disable the updater.
  Revocation protects only servers that learn of it before staging.

### 12.2 Keys and the trust file (blocker 1)

PROPOSED: keep the two-key design. No separate root/release/timestamp split.

- Reason: the backup key already acts as the offline root (it signs revocations and recovery
  releases). A timestamp or online freshness key would need an online machine or CI, which the
  owner rules out. Rejected: a TUF-style root/targets/snapshot/timestamp split (more keys and
  ceremonies, no extra protection while every key stays offline on the same owner).
- **Roles.** Primary: `release`. Backup: `release` (emergency only) and `revocation`. Only a
  `revocation`-role key may sign `revocations.json`. Reason: a leaked primary must not be able to
  revoke the backup. Rejected: letting the primary revoke the backup (a leaked primary would block
  recovery).
- **Key ID (`kid`).** Lowercase hex of the first 8 bytes of SHA-256 over the raw 32-byte Ed25519
  public key (16 hex chars). A `kid` only selects a key; the signature check decides. Reason: short,
  derivable, no registry. Rejected: arbitrary names (can drift from the key).
- **Trust file in the jar** at `META-INF/quicraft/updater-trust.json` (covered by the jar's
  SHA-512 in the manifest; never fetched from the network in v1):

```json
{
  "type": "quicraft.trust",
  "v": 1,
  "epoch": 1,
  "built": "2026-11-01T12:00:00Z",
  "revocationsSeqFloor": 1,
  "keys": [
    { "kid": "0123456789abcdef", "pub": "<64 hex>", "roles": ["release"] },
    { "kid": "fedcba9876543210", "pub": "<64 hex>", "roles": ["release", "revocation"] }
  ]
}
```

  | Field | Type | Rule |
  | --- | --- | --- |
  | `epoch` | integer 1..2^31-1 | Informational; logged. Increases when the key set changes. |
  | `built` | timestamp | Initial clock floor (12.5). |
  | `revocationsSeqFloor` | integer 0..2^53-1 | The `revocations.json` seq current at build time. A fresh install never accepts an older one. |
  | `keys` | array, 1..4 | `kid` must equal the value derived from `pub`, or the build fails and the updater disables itself. Exactly one key holds `revocation`. |

- JDK use: wrap the raw key in the fixed X.509 prefix `302a300506032b6570032100` and load it with
  `KeyFactory.getInstance("Ed25519")` and `X509EncodedKeySpec`; verify with
  `Signature.getInstance("Ed25519")` (pure Ed25519, RFC 8032). Java 15+; every updater target is 21+.
  No new dependency.

### 12.3 Encodings and bounded parsing (blocker 2)

PROPOSED:

- **Signed bytes = the exact file bytes.** The signature is over the raw received bytes of
  `release.json` or `revocations.json`. No canonical JSON, no re-serialisation. Reason: nothing to get
  wrong between signer and verifier, and owners can verify by hand with stock OpenSSL 3
  (`openssl pkeyutl -verify -pubin -inkey key.pem -rawin -in release.json -sigfile sig.bin`).
  Rejected: canonical JSON (fragile) and a prefixed signing context (breaks hand verification).
- **Domain separation** comes from the signed `type` field plus a strict schema: a document whose
  `type` is not the one expected for the URL it was fetched for is rejected, and unknown fields are
  rejected, so a revocation document can never parse as a release manifest. The `type` in the
  unsigned `.sig` file is never trusted.
- **Encodings, one each:** hashes, keys and signatures are lowercase hex (`[0-9a-f]`, exact length).
  Timestamps are strings in exactly `YYYY-MM-DDTHH:MM:SSZ` (20 chars, UTC, no fractions, no offsets).
  Reason: one encoding per kind, readable by owners. Rejected: base64 (two alphabets, padding
  variants) and epoch integers (equal safety, less readable).
- **Integers:** JSON integers only, no sign, no leading zeros, no fraction or exponent, value
  0..2^53-1 unless a field says otherwise.
- **Parser:** a small strict JSON reader in the updater code, not Gson. Reason: Gson silently keeps
  the last duplicate key and differs between platform versions. Rejected: Gson or Jackson (behaviour
  varies, Jackson is a new dependency). Rules: strict UTF-8 (malformed sequences and a BOM are
  rejected); max nesting depth 4; max string length 256 chars; max array length as per field; object
  keys unique (duplicate = reject); unknown keys rejected; missing required keys rejected; no
  trailing data after the top-level value except ASCII whitespace.
- **Size caps (bytes, enforced while reading, before hashing):** `release.json` 65536;
  `revocations.json` 65536; any `.sig` 4096; `updater-trust.json` 8192; `state.json` 131072;
  `journal.json` 8192.
- **Format version in the file name.** v1 clients read `release.v1.json`, `release.v1.json.sig`,
  `revocations.v1.json`, `revocations.v1.json.sig`. A future v2 is published next to v1, so strict v1
  parsers keep working. Rejected: one file name with tolerated unknown fields (weakens strictness).
  The draft names `release.json` / `release.json.sig` map to these; exact URLs are blocker 5.
- **Version strings:** `MAJOR.MINOR.PATCH` with optional `-alpha.N`, `-beta.N` or `-rc.N`; each number
  0..99999 without leading zeros. Order: numeric triple, then a release ranks above `rc` above `beta`
  above `alpha`, then N. No build metadata. A version number is never reused, even after withdrawal.

#### Signature file (`*.sig`)

```json
{
  "type": "quicraft.sig",
  "v": 1,
  "payloadSha256": "<64 hex>",
  "signatures": [ { "kid": "0123456789abcdef", "sig": "<128 hex>" } ]
}
```

- `signatures`: 1..2 entries (two allows a rotation overlap). Accepted if at least one entry verifies
  under a trusted, non-revoked key holding the role for the expected `type`. Entries with unknown
  `kid` are ignored.
- `payloadSha256` is not authentication. It lets the updater tell a torn pair (document and `.sig`
  from different publishes, normal during an upload) from a bad signature: a mismatch is logged at
  DEBUG and retried at the next check, not counted as a bad signature (section 6 WARN rule).

#### Release manifest (`release.v1.json`, signed by a `release`-role key)

```json
{
  "type": "quicraft.release",
  "v": 1,
  "seq": 42,
  "issued": "2026-11-01T12:00:00Z",
  "expires": "2026-12-01T12:00:00Z",
  "minRevocationsSeq": 3,
  "releases": [
    {
      "platform": "fabric-26x",
      "version": "0.4.0",
      "targets": ["26.1", "26.1.1", "26.1.2", "26.2", "26.3"],
      "file": "quicraft-fabric-26x-0.4.0.jar",
      "size": 4194304,
      "sha512": "<128 hex>",
      "tag": "v0.4.0"
    }
  ],
  "withdrawn": [
    { "platform": "fabric-26x", "version": "0.3.9", "sha512": "<128 hex>" }
  ]
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `seq` | integer | Monotonic across all manifests, never reused (12.4). |
| `issued`, `expires` | timestamp | 12.5. |
| `minRevocationsSeq` | integer | Staging needs fresh revocation state at least this new (12.6). |
| `releases` | array, 0..16 | At most one entry per `platform`. |
| `platform` | enum | `velocity`, `fabric-26x`, `fabric-1.21.11` in v1. Matched against a constant compiled into the running jar, never a config value. |
| `targets` | array of strings, 1..64, each ≤ 32 chars | Exact match. Fabric: the Minecraft version reported by the loader. Velocity: the proxy's `major.minor` (for example `4.2`). Source of values: `docs/version-matrix.md`. No ranges. |
| `file` | string, `^[A-Za-z0-9._+-]{1,124}\.jar$` | Display and download name only; never used as a local path. |
| `size` | integer 1..67108864 | Exact byte count; also the download cap. |
| `sha512` | 128 hex | Of the jar bytes. |
| `tag` | string, `^v[0-9A-Za-z.-]{1,40}$` | Git tag of the Corresponding Source; informational. |
| `withdrawn` | array, 0..64 | 12.7. |

Reasons: one manifest for all platforms gives one `seq` counter and one re-sign per refresh.
Rejected: one manifest per platform (three counters, three refresh signatures). Exact target lists:
Minecraft version ranges (`26.1` to `26.3`, `1.21.11`) are not reliably orderable. Rejected:
range syntax.

#### Revocations (`revocations.v1.json`, signed by the `revocation`-role key only)

```json
{
  "type": "quicraft.revocations",
  "v": 1,
  "seq": 3,
  "issued": "2026-11-01T12:00:00Z",
  "expires": "2027-11-01T12:00:00Z",
  "minManifestSeq": 0,
  "revokedKeys": [ { "kid": "0123456789abcdef", "pub": "<64 hex>" } ],
  "blockedArtifacts": [ { "sha512": "<128 hex>" } ]
}
```

| Field | Type | Rule |
| --- | --- | --- |
| `seq` | integer | Separate counter from manifests. |
| `minManifestSeq` | integer | Floor for manifest `seq`, independent of any release key (12.4). |
| `revokedKeys` | array, 0..16 | `kid` must match `pub`. Revoking by full key, so a later trust file cannot re-trust it by accident. |
| `blockedArtifacts` | array, 0..256 | Jar hashes never staged or swapped, whatever key signed them. |

An empty `revocations.v1.json` (no keys, no artifacts) must be published with the first release, or
staging cannot pass the coverage rule.

### 12.4 Rollback and sequence rules (blockers 2, 3)

PROPOSED: persist the highest accepted manifest `seq` **per signing key**, and compute the floor only
over keys that are not revoked.

- `floor = max(persisted minManifestSeq, max over non-revoked kid of seqByKid[kid])`.
- A manifest is accepted if `seq > floor`, or if `seq == floor` and its SHA-256 equals the persisted
  hash for that seq (the same document fetched again). The same `seq` with different bytes is
  equivocation: reject and WARN once.
- Only a signature that actually verified updates `seqByKid` for that `kid`.
- Reason: a leaked primary can push `seq` to 2^53-1 and freeze every later legitimate manifest. With
  per-key floors, revoking that key removes its contribution, and the backup-signed `minManifestSeq`
  still blocks older manifests. Rejected: one global floor (permanent freeze after a leak) and a cap on
  increments (an attacker raises it step by step).
- Revocations: accepted as the new current document if `seq > persisted revocations seq`, or equal
  with identical SHA-256 (equal seq, different bytes = equivocation, WARN). Older ones are ignored as
  "current" (DEBUG). Separately, the `revokedKeys`, `blockedArtifacts` and `minManifestSeq` of **any**
  validly signed revocations document, even older or expired, are merged in (union, max), because
  they only add restrictions and only the backup key can sign them. This is not freshness: a stale
  document never counts as fresh (12.6).
- Withdrawn artifact hashes from every accepted manifest are added to a persisted permanent set, so
  dropping a revoked key's floor cannot resurrect an older manifest that lacked a withdrawal.
- A downgrade is refused regardless of signature: the entry's version must be greater than the
  running version (section 4).

### 12.5 Freshness and clock policy (blocker 3)

PROPOSED numbers:

| Rule | Value |
| --- | --- |
| Manifest max lifetime (`expires - issued`) | 45 days; recommended publication: `expires = issued + 30 days`, re-signed (new `seq`) at least every 14 days |
| Revocations max lifetime | 400 days; recommended 365 days, re-signed yearly and immediately on any revocation |
| Future tolerance for `issued` | `issued <= systemNow + 24 h` |
| Expiry grace | none: `expires > effectiveNow` |
| Clock-behind warning | `systemNow < clockFloor - 24 h` |

- `issued < expires` is required.
- **Clock floor.** `effectiveNow = max(systemNow, clockFloor)`. `clockFloor` is the maximum of the
  trust file's `built`, the `issued` of the current fresh revocations document, and the `issued` of
  manifests accepted from **non-revoked** keys (stored per `kid`, like `seq`). The floor is never
  derived from the system clock, so a clock that was briefly far ahead cannot poison it, and a revoked
  key's contributions are dropped with its `seq`. A leaked key can push the floor at most 24 h past
  the real time it signed, which is less than any manifest lifetime.
- **Clock behind:** `effectiveNow` stops expired documents being accepted. A clock more than 24 h
  behind makes new manifests look future-issued, so updates stop until it is fixed; the updater logs
  one WARN per day naming the clock. Fail closed.
- **Clock ahead:** documents look expired, updates stop, one WARN per day naming the clock. Persisted
  state is not changed by a wrong clock.
- No network time and no trust in HTTP `Date` headers. Reason: hosts are untrusted. Rejected: NTP or
  `Date`-based correction.
- Expired manifest: no update is reported or staged (DEBUG once per document). It is not a bad
  signature.
- **USER DECISION (manifest refresh):** recommended 30-day expiry and a 14-day re-sign with the
  primary key. The freeze window equals the expiry. Shorter means more offline signing; longer means a
  longer freeze. Alternatives: 14 days (more signing), 90 days (longer freeze).
- **USER DECISION (revocation lifetime):** recommended 365 days. This makes the backup key sign once a
  year, which changes section 4's "signs nothing in normal operation" to "signs one revocations refresh
  per year". Shorter lifetimes need the backup key more often; with no online key, short revocation
  freshness is not possible. Alternative rejected: no expiry (contradicts section 4's freshness rule).

### 12.6 Revocation coverage when a source fails (blockers 4, 17)

PROPOSED:

1. Every check (scheduled or explicit) fetches `revocations.v1.json` and its `.sig` from **all three**
   hosts, and `release.v1.json` and its `.sig` from all three, independently of which host later serves
   the jar. A host failing for the jar never removes it from the revocation or manifest fetches.
2. Revocations are processed first (12.4), then manifests. Among valid manifests the highest accepted
   `seq` wins.
3. **Fresh revocation state** means: the current revocations document is validly signed, unexpired
   at `effectiveNow`, has `seq >= trust.revocationsSeqFloor` and `seq >= manifest.minRevocationsSeq`.
4. **Report-only check** (scheduled or `check`): an update is reported only if fresh revocation state
   exists (fetched now or persisted and still unexpired). Otherwise nothing is reported (DEBUG) and a
   WARN is logged once per day if the state has been stale for more than 7 days.
5. **Staging** (`/quicraft update`), and again at the revalidation before the swap: requires fresh
   revocation state, and at least **2 of the 3 hosts** returned, in this run, a valid revocations
   document whose SHA-256 equals the current one. Otherwise staging fails before download, the
   installed jar is untouched, and the command says which hosts failed.
6. The selected entry's `sha512` must not be in `blockedArtifacts` or the persisted withdrawn set, and
   its signer must not be revoked. Checked again before the swap.

- Reason: an attacker controlling one host cannot hide a revocation. Rejected: 1-of-3 (one host
  withholds) and 3-of-3 (any outage blocks updates).
- **USER DECISION (staging quorum):** recommended 2 of 3. Effect: an owner whose network blocks two
  hosts cannot stage updates through the updater and installs by hand.
- Limit, stated plainly: the quorum is not proof of freshness. Two hosts replaying the same unexpired
  older document still pass; the bound is the revocation lifetime (12.5). Dependency: blocker 5 (where
  each host serves these files).

### 12.7 Release sequencing and withdrawal (blocker 6)

PROPOSED release sequence (the signing tool itself is blocker 15):

1. Tag the commit. Build the jars. CI may build them; CI never signs.
2. On the offline signing machine, compute size and SHA-512 of each jar, and check the built jar's
   `META-INF/quicraft/updater-trust.json` and version metadata.
3. Upload the jars to every host. Download each copy back and compare size and SHA-512.
4. Compose `release.v1.json` with `seq = previous + 1`, new `issued` and `expires`, the new entries,
   and `minRevocationsSeq` = current revocations `seq`. Sign with the primary key.
5. Verify the pair with the same verifier code the updater uses.
6. Publish the document and `.sig` to all hosts. Fetch them back from each host and verify.

The manifest is published only after every jar it names is available, so a client never sees a
manifest pointing at a missing jar. Refresh without a release = steps 4 to 6 with the same entries.

PROPOSED withdrawal (bad build, no key compromise):

1. Publish a new manifest (`seq + 1`) that no longer lists the bad version, and lists its platform,
   version and SHA-512 under `withdrawn`.
2. Effects: servers that have not staged it no longer see it; a staged copy fails revalidation before
   the swap and is discarded; servers already running it log WARN and notify OPs once ("running a
   withdrawn release"). The updater never downgrades automatically; the fix is a newer version.
3. Only after step 1 is published on every host, delete or hide the bad jar on the hosts.

Malicious release signed by a leaked primary: the backup key signs a revocations document with the
primary in `revokedKeys`, the bad jar hashes in `blockedArtifacts`, and a `minManifestSeq` above any
legitimate seq used so far. Then a backup-signed release ships a jar with a new trust file. Servers
that already installed the malicious jar are not protected (12.1).

### 12.8 Locks, files, journal and recovery (blocker 12)

Paths are relative to a per-platform updater directory `<updateDir>` outside every loader-scanned
directory. Its location is blocker 16 (draft: `plugins/quicraft/update/` on Velocity).

| File | Purpose |
| --- | --- |
| `<updateDir>/updater.lock` | OS file lock (`FileChannel.tryLock`). The lock, not the file's existence, is what counts. Content: pid and start time, for diagnostics only. |
| `<updateDir>/state.json` | Persisted trust state (below). |
| `<updateDir>/journal.json` | Present only while a transaction is in progress or needs recovery. |
| `<updateDir>/staging/<txid>.part`, `<txid>.jar` | Download, then verified staged copy. |
| `<updateDir>/backup/<txid>.jar` | Copy of the live jar before the swap. |

- **Locking.** An in-process guard (one transaction per JVM) plus the file lock (one per directory).
  Held from the start of `/quicraft update` to the end of the swap, during `cancel`, and during the
  startup recovery. Not held by scheduled checks, except for the short atomic `state.json` write. Busy
  lock = "another update is in progress", no waiting.
- **Atomic writes** (`state.json`, `journal.json`): write `<name>.tmp`, `force(true)`, `ATOMIC_MOVE`
  over the target, then fsync the directory. Directory fsync and `F_FULLFSYNC` behaviour on macOS need
  evidence (blocker 10).
- **Same file system.** The staged jar must be on the live jar's file store. If `ATOMIC_MOVE` throws
  `AtomicMoveNotSupportedException`, the transaction fails before the swap (installed bytes unchanged).
- **Swap target.** The staged jar replaces the live jar at the live jar's existing path; the file name
  is not changed. Reason: a rename to a versioned name takes two operations, leaving a moment with two
  or zero jars. Rejected: versioned rename. Running and on-disk versions are reported from content.

`state.json`:

```json
{
  "type": "quicraft.updater-state",
  "v": 1,
  "keys": { "0123456789abcdef": { "seq": 42, "sha256": "<64 hex>", "issued": "2026-11-01T12:00:00Z" } },
  "minManifestSeq": 0,
  "revocations": { "seq": 3, "sha256": "<64 hex>", "issued": "2026-11-01T12:00:00Z", "expires": "2027-11-01T12:00:00Z" },
  "revokedKeys": [ { "kid": "0123456789abcdef", "pub": "<64 hex>" } ],
  "blockedArtifacts": [ "<128 hex>" ],
  "withdrawn": [ "<128 hex>" ],
  "lastAnnounced": { "fabric-26x": "0.4.0" },
  "warnedBad": [ "<64 hex>" ]
}
```

`warnedBad` keeps the last 32 SHA-256 values of bad documents, for the one-WARN rule. A corrupt or
unparsable `state.json` disables update operations (checks report it, WARN once per start) and is
never reset silently, because resetting would drop persisted revocations. The owner deletes it by hand
after reading the message.

`journal.json`:

```json
{
  "type": "quicraft.journal",
  "v": 1,
  "txid": "<32 hex, random>",
  "phase": "prepared",
  "platform": "fabric-26x",
  "manifestSeq": 42,
  "live":   { "path": "/abs/path/mods/quicraft-fabric-26x.jar", "size": 4100000, "sha512": "<128 hex>", "version": "0.3.1" },
  "staged": { "path": "/abs/path/<updateDir>/staging/<txid>.jar", "size": 4194304, "sha512": "<128 hex>", "version": "0.4.0" },
  "backup": { "path": "/abs/path/<updateDir>/backup/<txid>.jar", "sha512": "<128 hex>" },
  "created": "2026-11-02T09:00:00Z",
  "bootAttempts": 0
}
```

`phase`: `prepared` | `swapped` | `boot-pending` | `recovery-required`. Paths are absolute and
canonical. `backup.sha512` equals `live.sha512`.

#### Transaction steps (maps to section 5 states)

1. `checking`: fetch and verify (12.6). `downloading`: write `staging/<txid>.part` (created with
   `CREATE_NEW`, no symlink following), stop at `size + 1` bytes.
2. `verified-staged`: size and SHA-512 match; the jar's own version metadata names the same platform
   and version; rename to `<txid>.jar`; revalidate manifest, keys, revocations, freshness, version.
3. `backup-ready`: copy the live jar to `backup/<txid>.jar`, fsync, check its SHA-512; write the
   journal with `phase=prepared` (atomic, fsynced). Then log the exact manual rollback command.
4. `installed-pending-restart`: `ATOMIC_MOVE` staged over live, fsync the directory, set
   `phase=swapped`.
5. After restart, in the new generation: `phase=boot-pending`, `bootAttempts += 1` (written before
   anything else) -> `health-check-pending`. When the health criteria pass (blocker 9): delete the
   journal -> `healthy` -> `idle`. Keep the newest backup until the next successful update replaces it
   (one backup retained).

#### Startup recovery (runs first in updater init, under the file lock, never throws)

1. No journal: delete `staging/` files not named by a journal. Done.
2. Journal unparsable or schema-invalid: `recovery-required`; keep every file; WARN with manual steps.
3. Hash the live path.
4. `phase=prepared`:
   - live = `live.sha512`: the swap did not happen. Delete the staged file and the journal. `idle`.
     The backup is kept.
   - live = `staged.sha512`: the swap happened but the journal was not advanced. Continue as 5.
   - otherwise: `recovery-required` (someone else changed the jar).
5. `phase=swapped`: if the running version equals `staged.version` and live = `staged.sha512`, go to
   step 5 of the transaction. Otherwise `recovery-required`.
6. `phase=boot-pending` found at startup: the previous boot of the new generation did not pass the
   health criteria. If `bootAttempts < 2`, increment and try again. Otherwise `recovery-required`.
7. `recovery-required`: no new downloads; WARN on every start and notify OPs once per start, with the
   exact backup path and manual command. If the owner restores by hand (live = `backup.sha512`) or
   installs another jar and deletes the journal, the next start returns to `idle` and logs INFO.

Limit: if the new generation fails before the updater's init runs (for example the loader rejects the
jar), no project code can recover it. That is why the manual rollback command is logged at step 3,
before the swap.

**USER DECISION (rollback command):** recommended: add `/quicraft update rollback` (copy the backup
to staging, then the same `ATOMIC_MOVE` and journal path) for `recovery-required`. Alternative: manual
restore only. The command adds a second live swap and depends on blocker 10 like the first.

### 12.9 Verification algorithm (summary)

For each host, per document kind (revocations first, then release):

1. HTTPS GET with the size cap; abort past the cap.
2. Fetch the `.sig` (cap 4096) and parse it strictly.
3. SHA-256 of the document bytes equals `payloadSha256`; else torn pair (DEBUG), skip this host.
4. For each signature entry with a trusted `kid`: the key is not revoked (persisted union plus trust
   file) and holds the role for the expected type; Ed25519 verify over the raw bytes. None verifies:
   bad signature (one WARN per distinct document).
5. Parse the document strictly; `type` and `v` match the expected kind.
6. Lifetime, `issued` future tolerance and expiry against `effectiveNow` (12.5).
7. Sequence rules (12.4); merge additive revocation data.
8. Persist state atomically before any later step uses it.
9. Release only: select the entry for the compiled-in platform with an exact target match; version
   greater than running; hash not blocked or withdrawn; coverage (12.6).
10. Staging and swap: transaction steps (12.8), with steps 4 to 9 repeated immediately before the swap.

### 12.10 Dependencies on blockers not resolved here

- Blocker 5: URLs per host, redirect policy, which host serves which file.
- Blocker 9: health criteria that end `health-check-pending`.
- Blocker 10: loader safety of the live swap, directory fsync and macOS `F_FULLFSYNC` evidence.
- Blocker 15: the signing tool, which must reuse this verifier.
- Blocker 16: `<updateDir>` per loader and installed-jar discovery (the `live.path` input).
