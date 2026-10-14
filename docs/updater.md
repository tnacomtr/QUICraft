# Server updater (specification)

**Status: approved implementation direction, not approved for release.** No runtime updater is
implemented. The first implementation wave adds disconnected verification primitives. Nothing is
a published wire format or a shipped feature. Release is blocked until the remaining blockers in
section 10 are resolved. The user approved section 12's recommendations on 2026-10-13, including
the restart-required live-swap semantics and manual restore instead of a rollback command.

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
- Shared updater logic lives in a separate Java 21 `updater` module, with thin platform adapters,
  not in the Java 8 `core`. Adding this module does not yet wire or ship the updater.

## 2. Approved user choices (preserved)

| Choice | Meaning in this spec |
| --- | --- |
| Release sources | Modrinth is primary. GitHub and `git.sudoe.rs` are also supported. |
| Scheduled checks | Hourly checks only, by default. A check never downloads a jar. |
| First-start disclosure | The plugin says once on first start which hosts it contacts and which key turns checks off. |
| Bad-signature output | One console WARN per distinct bad manifest by default (`update.log-bad-signatures`). |
| Live replacement | Atomic `ATOMIC_MOVE` replacement of the live jar on Linux and macOS stays in the design. Release is blocked until loader-generation safety and crash-recovery evidence exist (section 10). |
| Process scope | Velocity and Fabric dedicated servers only. |
| Manifest publication | 30-day expiry, re-sign at least every 14 days; structural cap 45 days. |
| Revocation publication | 365-day expiry; yearly backup-key signing and immediate signing on revocation; structural cap 400 days. |
| Staging coverage | Fresh revocations with matching current bytes from at least 2 of 3 hosts. |
| State reset | Console-only `reset-state`, preserving valid permanent revocations. |
| Different filesystems | Fail before replacement, with manual install instructions. |
| Key-set changes | Backup-key authentication required in addition to release authorization (12.2). |
| Rollback | Manual restore baseline; no rollback command in v1. |

Atomic live replacement means replacement of the on-disk jar while the process runs, not hot
activation: the new version takes effect only after restart. Own-jar resources must be preloaded.
JDK 21, macOS and crash-recovery proof remain release gates.

If atomic replacement is unavailable on a platform, the updater fails safe: the installed bytes and
the backup are kept. Showing manual install or recovery instructions is allowed. A silently
manual-only updater instead of this design is not allowed.

## 3. Commands and permissions (draft, not registered)

Console, RCON, or an operator (Fabric: any player on the server's OP list; Velocity: the
`quicraft.admin.update` permission), except that `reset-state` is console-only:

| Command | Effect |
| --- | --- |
| `/quicraft update check` | Reports the running version and the latest compatible one. |
| `/quicraft update check -s` | Same check, silent mode (section 5). |
| `/quicraft update` | Downloads, verifies, and stages the new jar. |
| `/quicraft update cancel` | Cancels a download or a staged update (section 5). |
| `/quicraft update reset-state` | Console only: rebuilds resettable state while preserving valid permanent revocations (12.8). |

- Nothing is downloaded or installed automatically. A scheduled check only reports.
- Velocity has no OPs. Its notify permission is `quicraft.admin.update.notify` (draft).
- Fabric: implemented. Allowed: the console and RCON (no entity, admin permission); any player on the
  server's OP list, at any op level (1 to 4); and, as before, a player holding the admin permission
  (level 3, or granted by a permissions mod). Command blocks and datapack functions run at level 2 by
  default and are refused (a server that raises `function-permission-level` to 3 or 4 lets its functions
  run it), and `reset-state` stays console-only. The same rule picks who gets the join notice.
  `/execute` cannot widen this: the command needs both the source that typed it and the source it runs
  as to be allowed. Live check on 26.1.2: the console's `execute as <non-op>` is refused, and a command
  block's `execute as <op>` is refused when the command is parsed.
  Every OP-list entry, level 1 included, can stage and cancel updates, so give op only to people you
  trust with server maintenance. In offline mode, or on a backend reachable without proxy forwarding,
  anyone can join under an operator's name and pass this check.

## 4. Keys, freshness, and revocation

- **Keys.** Two Ed25519 pairs: a primary key (signs releases; offline; never in CI) and a backup key
  (offline media; recovery and key-set authorization). Both public keys ship in updater-enabled
  jars with short key IDs. The approved freshness policy also requires the backup key to re-sign
  `revocations.v1.json` yearly and immediately on any revocation (12.5).
- **Signing tool.** A planned repo script that uses the JDK's Ed25519. It does not exist yet. The
  unreleased v1 wire formats are specified in 12.3; the tool must reuse their verifier.
- **Signed content.** The signature covers the manifest. The draft's fields: version, platform,
  game-version range, file name, size, SHA-512 of the jar, release date, signing key ID.
- **Freshness (both required).** The updater requires both:
  1. a signed, monotonic sequence number, with the highest value seen persisted, so an older
     manifest is never accepted after a newer one; and
  2. signed issued and expiry times, so a stale manifest is rejected.
  Schemas, sequence field encoding, expiry lengths, and clock-skew thresholds are specified in
  12.3-12.5; implementation and tests are still required.
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
  - **Coverage rule:** fallback to an artifact or mirror source never weakens fresh revocation
    coverage. Staging needs matching fresh current revocations from 2 of 3 hosts (12.6).
  - Encodings and clock policy are specified in 12.3-12.5, not yet runtime-enforced.
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
   new generation reports against the health criteria below. The recovery backup is retained
   unconditionally until those criteria pass. The draft's rule of deleting `.bak` on the next
   successful start is superseded by this rule.

   The alpha's criteria (blocker 9 of section 10):

   - **Velocity:** the plugin's initialisation finished. QuicraftVelocity records the verdict at the
     end of its own init, from a late, lowest-priority handler, so it covers QUICraft's init.
   - **Fabric dedicated server:** the dedicated server finished starting. The updater is reached only
     after the server's init returned true, so the verdict is recorded at that point.

   A crash, a failed init or a failed start after either point is not detected; the timeout that
   bounds the wait is unchanged. Health is a verdict on that one point, not a watch on the first
   ticks, and the backup of the previous generation is kept until the verdict passes.

### Cancel and failure

- **Cancel during `downloading` or `verified-staged`** -> `idle`. Only the staged file created by
  that operation is deleted. The installed jar and the recovery backup are never deleted.
- **Failure before the live swap** (download, verify, revalidation, backup, or journal fails) ->
  `idle`. The installed bytes are unchanged, and the staged file is removed.
- **Failure during or after the swap** that leaves the installed state unclear -> `recovery-required`.
  The backup and journal are kept, and the defined recovery procedure runs on next start. The
  recovery procedure is specified in 12.11, not yet implemented or proven crash-safe.
- Cancellation does not roll back an installed generation on its own.

### Concurrency

Operations are serialized by a process lock and a file lock. Only one transaction runs at a time.
Lock file locations and journal format are specified in 12.8-12.9, not yet implemented.

### Installed jar discovery and placement

- **Discovery.** The installed jar is found through platform-validated loader installation metadata,
  not by guessing a file name. `getProtectionDomain().getCodeSource()` alone is not sufficient for
  Fabric, so it is not the sole method. Loader-specific discovery is specified in 12.14 and still
  requires implementation and supported-loader tests.
- **Validation.** The resolved origin must be exactly one canonical, writable, top-level supported
  jar. Reject nested jars, dev or build-output paths, ambiguous origins, and symlink destinations.
- **No duplicates.** Never leave two loadable QUICraft jars in `plugins/` (Velocity) or `mods/`
  (Fabric). Velocity refuses duplicate plugin IDs.
- **Placement.** Backup and staging files live outside loader-scanned directories, at the paths
  specified in 12.8 and 12.14.

### Windows

The draft proposes a post-exit helper that waits for the server to exit and then swaps jars, with
manual steps logged if the helper cannot start. Its architecture is unresolved and unverified. A
Windows live updater must not ship until this is resolved.

## 6. Configuration, disclosure, and output

### Config

The updater reads its own file, separate from the transport settings: `plugins/quicraft/updater.properties`
on Velocity and `config/quicraft/updater.properties` on Fabric. QUICraft never creates or rewrites it; a
missing file means the defaults below.

```
update.check-interval=hourly   # hourly (default) | daily | off; matched case-insensitively
update.check-silent=true       # same as -s
update.log-bad-signatures=true
update.install=swap            # swap (default, Linux and macOS) | stage-only
```

An unrecognised `update.check-interval` value turns checks off and logs a WARN naming the key only. An
unrecognised `update.install` value fails safe to `stage-only`: the jar is staged and verified, never
installed, and one WARN names the key only. `update.install=stage-only` is the owner opt-out and holds
on every platform, whatever the OS. A malformed file logs a fixed WARN and uses the defaults. The
transport settings stay in `plugins/quicraft/config.properties` (Velocity) and
`config/quicraft/server.properties` (Fabric). The draft's `update/` paths under
`plugins/quicraft/` are Velocity-only. Fabric staging and state paths are specified in 12.14.

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

Once per version. The last announced version is stored in the platform's `state.json` (12.8 and
12.14; the draft's `update/state.json` path is Velocity-only). Offline OPs get the notice on next join. The draft's
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
- Approved requirements (section 12): a staged jar whose trust file names a revocation key other than the persisted
  one is rejected before the swap, even when validly signed (12.2, N1); a staged jar with the same
  epoch and a different key-set fingerprint is rejected (12.2, N2); a hand-installed jar with a higher
  epoch and a new revocation key resets that key's floors (transition 6); a revalidated entry that
  differs from the staged bytes discards the staging (12.10 step 4); a revocation merged by a check
  while a transaction waits blocks the swap (12.10 step 6); after a swap, no code path reads the new
  jar (12.13); fresh install, then a check against the currently published revocations document,
  reaches fresh revocation state (12.4 seed case).

## 9. Owner responsibilities

- Keep the primary key offline (owner's machine or hardware key). Keep the backup key offline on
  separate media, plus a paper copy. Private keys never enter the repo or CI.
- Verify a release by hand from the signed manifest once the README documents how.

## 10. Unresolved release blockers

1. Runtime integration and release freeze of the approved manifest, signature, trust and revocation
   schemas (12.2, 12.3). Disconnected structural parsers are implemented and tested, not authentication.
2. Signed-byte authentication and sequence/expiry enforcement under the approved encodings (12.3,
   12.4). Bounded parsing and duplicate-field rejection are implemented as primitives only.
3. Implementation and tests of the approved freshness thresholds and clock policy (12.5).
4. Implementation and tests of the approved failed-source coverage rule: fallback never weakens
   fresh coverage (12.6).
5. Exact endpoints and CDN redirects. Resolved for the first alpha (12.15): the updater compiles in
   the GitHub and `git.sudoe.rs` hosts; each serves the four metadata files from the
   `updater-metadata` release of its own repository, replaced on every publish, and each artifact
   from the release tag of its own repository. Source priority between the hosts is not implemented
   yet.
6. Prove the specified release sequencing and withdrawal workflow (12.7).
7. Dedicated-server gating mechanism (section 7).
8. Permission integration for Fabric and Velocity, and command registration.
9. Platform health criteria and mechanism. **Resolved for the alpha:** the criteria are settled in
   section 5 (Velocity: the plugin's initialisation finished; Fabric: the dedicated server finished
   starting). Backup retention until they pass is unconditional, and a crash after either point is not
   detected.
10. Loader-generation safety for live `ATOMIC_MOVE` replacement on Linux and macOS, with crash-recovery
    evidence (evidence and rules: 12.13). **The swap is enabled for the alpha on Linux and macOS:**
    `update` installs the verified jar there and reports "installed, restart required"; every other OS
    keeps verified staging only, and so does a Linux or macOS server whose owner sets
    `update.install=stage-only` (section 6). Still open:
    - Live-swap evidence on macOS and on JDK 21 server runs. A CI job is being added separately; the
      macOS job in 12.13 runs the updater test suite, not a server.
    - What JDK `FileChannel.force` does on macOS (`fsync` versus `F_FULLFSYNC`) is **not verified**.
      No JDK source or documentation was read for this note, so the macOS durability of the swap and of
      the directory sync rests on the recorded CI runs and not on a citation.
    - Crash-recovery evidence from a live server run (the in-process crash matrix of 12.11 is tested).
    - Velocity builds other than 4.2.0-30.
11. Windows post-exit helper design and verification.
12. Implement and prove the approved locks, journal and recovery design (12.8 to 12.11).
13. Disclosure text in the README and on project pages (required before release).
14. Hosting-rule re-checks (section 11).
15. Signing tool, key generation, and the offline key process (see `release.md`).
16. Implement and prove loader-specific installed-jar discovery and confined backup/staging placement
    outside loader scan roots (approved specification: 12.14).
17. Implement and prove revocation authentication, clock policy and required coverage using the
    approved metadata encodings (12.3, 12.5, 12.6).

## 11. Hosting rules (re-check required)

- Modrinth: the draft cites the "clear disclosure" rule for software that contacts a remote server.
  Re-read the live rules before release. Not verified here.
- Hangar: the draft found no rule on runtime downloads but could not fetch the page. Re-read before
  the first Hangar upload.
- Rules change without versioning. Re-check before the first release that ships the updater.

## 12. Approved implementation specification

**Status: approved for implementation, not release.** The user approved the recommendations on
2026-10-13 after four review rounds, and separately confirmed manual restore and restart-required
activation after an atomic on-disk swap. This answers the design choices for section 10 blockers
1, 2, 3, 4, 6, 12, 16 and 17, and records the evidence for blocker 10. Implementation and its tests
remain required. Blockers 5, 7-11 and 13-15 stay open where not otherwise resolved. The backup
authentication extension below needs its own implementation security review before release.

### 12.1 Threat model this proposal answers

- Hosts (Modrinth, GitHub, `git.sudoe.rs`) are untrusted transport. Any one of them may serve old,
  altered, withheld or oversized bytes. HTTPS only protects the connection to that host.
- Rollback: an older validly signed manifest replayed after a newer one.
- Freeze: a validly signed manifest replayed for a long time so no update or withdrawal is seen.
- Mix-and-match: a valid signature or hash for one artifact, platform or document type applied to
  another.
- Key compromise: a leaked primary key, recovered with the backup key (section 4).
- Backup authentication is required for transition 5 key-set changes (12.2). A leaked primary alone
  cannot authorize the updater to install a jar adding a persistent release key. This does not
  protect a server that already installed malicious code or whose backup key is compromised.
- Clock skew on the server, in either direction.
- Not answered (unchanged from section 4): first install, signer machine compromise. A server that
  already installed a jar signed by a leaked key runs attacker code, which can disable the updater.
  Revocation protects only servers that learn of it before staging.

### 12.2 Keys and the trust file (blocker 1)

APPROVED: keep the two-key design. No separate root/release/timestamp split.

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
  "manifestSeqFloor": 41,
  "keys": [
    { "kid": "0123456789abcdef", "pub": "<64 hex>", "roles": ["release"] },
    { "kid": "fedcba9876543210", "pub": "<64 hex>", "roles": ["release", "revocation"] }
  ]
}
```

  | Field | Type | Rule |
  | --- | --- | --- |
  | `epoch` | integer 1..2^31-1 | Trust epoch. Normative: drives the transitions below. Increases whenever the key set changes. |
  | `built` | timestamp | Initial clock floor (12.5). |
  | `revocationsSeqFloor` | integer 0..2^53-1 | The `revocations.json` seq current at build time. A fresh install never accepts an older one. |
  | `manifestSeqFloor` | integer 0..2^53-1 | The highest manifest `seq` published before this build. A fresh install never accepts an older manifest. |
  | `keys` | array, 1..4 | `kid` must equal the value derived from `pub`, or the build fails and the updater disables itself. Exactly one key holds `revocation`. No key is listed twice. |

- **Read once.** The running jar's trust file, its own version metadata and every other resource the
  updater reads from its own jar are read once at plugin or mod init into memory, and never again in
  that process (12.13 explains why).
- **Key-set fingerprint** (peer review: makes the same-epoch check implementable). Sort `keys` by
  `pub` (lowercase hex, ascending). For each key, append the 32 raw public-key bytes and one role byte
  (bit 0 = `release`, bit 1 = `revocation`). The fingerprint is the lowercase hex SHA-256 of that byte
  string. It is persisted as `keySetFingerprint` (12.8).
- **Trust-epoch transitions** (only ever from the running jar's trust file, never from network data).
  The persisted state records `epoch`, `keySetFingerprint` and the revocation key's `pub` (12.8).
  Exactly one case applies:
  1. No persisted state: initialise from the trust file (12.8, "Initialisation").
  2. Same epoch, same `keySetFingerprint`: normal operation.
  3. Same epoch, different `keySetFingerprint`: the trust file is invalid; updater disabled, WARN.
  4. Lower epoch (an older jar installed by hand): checks report only; staging disabled; WARN once per
     start. Persisted revocations still apply to its keys.
  5. Higher epoch, same revocation key (routine rotation, or primary recovery): store the new epoch
     and fingerprint; every floor and union is kept.
  6. Higher epoch, **different** revocation key (recovery after a backup-key compromise or loss): store
     the new epoch, fingerprint and key; drop the revocation `seq`, `sha256`, `minManifestSeq` and
     clock contribution recorded for the old revocation key; seed them from the trust file's
     `revocationsSeqFloor` and `manifestSeqFloor`; add the old revocation `pub` to `revokedKeys`. Keep
     `revokedKeys` and `blockedArtifacts` (permanent union) and the per-`kid` manifest floors of keys
     that are not revoked. From here on `revocations.seq` and `revocationsSeqFloor` count documents
     signed by the **new** revocation key only; its counter may start again at 1 (security review N3).
     The signing tool (blocker 15) must check that a trust file's `revocationsSeqFloor` equals the
     `seq` of the newest revocations document signed by that trust file's revocation key, or 0 if
     there is none.
  Transition 6 happens **only through a hand install**: the updater never stages a jar that would
  cause it (staged-jar validation below). Transition 5 may come from an updater-installed jar.
- **APPROVED (key-set changes in updater-installed jars):** require authentication by the current,
  non-revoked `revocation`-role (backup) key as well as normal release authorization whenever the
  staged jar's computed key-set fingerprint differs from persisted state. Use the existing detached
  envelope's `signatures` array: both signatures cover the same exact manifest bytes, which bind
  the selected jar's SHA-512. No manifest flag can waive this rule. A backup-signed emergency release
  can satisfy both roles with the same valid signature. Retain all actually verified signers and
  recheck this requirement at revalidation and final locked authorization before replacement.
  Co-signing never waives the prohibition on updater-driven replacement of the revocation key.
  This is an unreleased v1 authorization extension; it needs independent implementation review.
  Reason for 6: without it a leaked backup key could set `seq` and `minManifestSeq` to 2^53-1 and block
  every later update permanently, even after a manual install (security review). The transition is
  safe because it only comes from a jar that is already running: a jar with code execution needs no
  trust-file trick. The replacement keys of a recovery must be freshly generated, so a leaked backup
  key cannot have revoked them in advance. Rejected: epoch as information only (no recovery path).
- **Validating a staged jar's trust file** (before the swap, 12.10 step 5): it parses under the same
  rules; exactly one `revocation` key, and its `pub` **equals the persisted `revocationKey`** (security
  review N1: a key change is transition 6, hand install only, matching the roles rule above); no `pub`
  in the persisted `revokedKeys`; `epoch` not lower than the persisted epoch; and if `epoch` equals the
  persisted epoch, its fingerprint equals the persisted `keySetFingerprint` (security review N2:
  otherwise the jar would disable its own updater at restart through transition 3). Otherwise the
  staged jar is rejected and its staging files are deleted.

- JDK use: wrap the raw key in the fixed X.509 prefix `302a300506032b6570032100` and load it with
  `KeyFactory.getInstance("Ed25519")` and `X509EncodedKeySpec`; verify with
  `Signature.getInstance("Ed25519")` (pure Ed25519, RFC 8032). Java 15+; every updater target is 21+.
  No new dependency.

### 12.3 Encodings and bounded parsing (blocker 2)

Specified encoding and parser rules:

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
  rejected); max nesting depth 4; max string length 256 chars, except where a field's table states a larger limit (only the journal's
  `live.path`, ≤ 4096 chars); max array length as per field; object
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
  DEBUG and retried at the next check, not counted as a bad signature (section 6 WARN rule). A host
  that stays torn for more than 24 h gets a WARN (12.6 item 7).

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

Specified: persist the highest accepted manifest `seq` **per signing key**, and compute the floor only
over keys that are not revoked.

- `floor = max(trust.manifestSeqFloor, minManifestSeq of the current revocation key,
  max over every persisted kid that is not revoked of seqByKid[kid])`. Persisted kids that are no
  longer in the running trust file still count, as long as they are not revoked (security review).
- A manifest is accepted if `seq > floor`, or if `seq == floor` and its SHA-256 equals the persisted
  hash for that seq (the same document fetched again). The same `seq` with different bytes is
  equivocation: reject and WARN once.
- Only a signature that actually verified updates `seqByKid` for that `kid`.
- Reason: a leaked primary can push `seq` to 2^53-1 and freeze every later legitimate manifest. With
  per-key floors, revoking that key removes its contribution, and the backup-signed `minManifestSeq`
  still blocks older manifests. Rejected: one global floor (permanent freeze after a leak) and a cap on
  increments (an attacker raises it step by step).
- **Revocations, two separate operations** (peer review: they were mixed before):
  1. *Restriction merge.* Any revocations document that passes the signature, schema and structural
     time checks (12.12 steps R1 to R6) and is signed by the **current** revocation key has its
     `revokedKeys` and `blockedArtifacts` added to the permanent union and its `minManifestSeq` merged
     by max, even if it is older than the current one or expired. It only adds restrictions, and only
     the revocation key can sign it.
  2. *Current-document acceptance.* A document becomes the current one if `seq > persisted seq`, or
     `seq ==` with identical SHA-256. Equal `seq` with different bytes is equivocation (WARN once).
     **Seed case** (fresh install, reset, or transition 6; both reviews): while the persisted
     `revocations.sha256` is the empty string, the highest-`seq` validly signed document among this
     run's responses with `seq >= persisted seq` and `issued <= systemNow + 24 h` becomes current
     (so the result does not depend on host order). An expired document may become current this way;
     it is never fresh, but it ends the seed
     case; its SHA-256, `issued` and `expires` are filled in, its
     restrictions are merged as in 1, and `minManifestSeq` is merged by max, so no floor is lowered.
     Without this a fresh install would see `seq == floor` with a hash that cannot match the empty one
     and never reach fresh revocation state.
     Freshness (12.6) is judged only on the current document and only if it is unexpired. A stale or
     expired document is never fresh.
- **Withdrawn entries** are persisted with the `kid` that signed the manifest, and only for the
  compiled-in platform with a version greater than `max(running, on-disk)`. Entries from a revoked
  `kid` are dropped together with its floor. They are permanent otherwise, so dropping a revoked key's
  floor cannot resurrect an older manifest that lacked a withdrawal (growth limits: 12.8).
- A downgrade is refused regardless of signature: the entry's version must be greater than
  `max(running version, on-disk version)` (security review: comparing with the running version alone
  allowed a second update to replace a newer on-disk jar).

### 12.5 Freshness and clock policy (blocker 3)

Approved publication defaults and structural limits:

| Rule | Value |
| --- | --- |
| Manifest max lifetime (`expires - issued`) | 45 days; recommended publication: `expires = issued + 30 days`, re-signed (new `seq`) at least every 14 days |
| Revocations max lifetime | 400 days; recommended 365 days, re-signed yearly and immediately on any revocation |
| Future tolerance for `issued` | `issued <= systemNow + 24 h` |
| Expiry grace | none: `expires > effectiveNow` |
| Clock-behind warning | `systemNow < clockFloor - 24 h` |

- `issued < expires` is required.
- **Clock floor.** `effectiveNow = max(systemNow, clockFloor)`. `clockFloor` is the maximum of the
  trust file's `built`, the `issued` of the current revocations document of the current revocation
  key, and the `issued` of manifests accepted from **non-revoked** keys (stored per `kid`, like `seq`).
  The floor is never derived from the system clock, so a clock that was briefly far ahead cannot
  poison it, and a revoked key's contributions are dropped with its `seq`. A leaked key can push the
  floor at most 24 h past the real time it signed, which is less than any manifest lifetime.
- **Clock behind:** `effectiveNow` stops expired documents being accepted. A clock more than 24 h
  behind makes new manifests look future-issued, so updates stop until it is fixed; the updater logs
  one WARN per day naming the clock. Fail closed.
- **Clock ahead:** documents look expired, updates stop, one WARN per day naming the clock. Persisted
  state is not changed by a wrong clock.
- No network time and no trust in HTTP `Date` headers. Reason: hosts are untrusted. Rejected: NTP or
  `Date`-based correction.
- Expired manifest: no update is reported or staged (DEBUG once per document). It is not a bad
  signature.
- **Backup-key exposure (restated, security review).** With a 365-day revocations lifetime the backup
  key signs once a year in normal operation, plus on any revocation or recovery release. This changes
  section 4's "signs nothing in normal operation"; section 4 now points here. Each yearly ceremony is
  an exposure of the root key and should follow the offline process of blocker 15.
- Rejected alternative (considered after review): let the fresh primary-signed manifest vouch for the
  current revocations document (by `seq` and SHA-256) and give that document a multi-year lifetime.
  It touches the backup key less, but a leaked primary could then keep an old revocations document
  alive for years; the 365-day expiry is a deadline that only the backup key can extend.
- **APPROVED (manifest refresh):** 30-day expiry and a 14-day re-sign with the
  primary key. The freeze window equals the expiry. Alternatives: 14 days (more signing), 90 days
  (longer freeze).
- **APPROVED (revocation lifetime and backup-key use):** 365 days, so the backup key
  signs once a year. Shorter lifetimes mean more backup-key ceremonies. Longer ones mean a longer window
  in which a withheld revocation goes unnoticed. Rejected: no expiry (contradicts section 4's freshness
  rule).

### 12.6 Revocation coverage when a source fails (blockers 4, 17)

Approved coverage policy:

1. Every check (scheduled or explicit) fetches `revocations.v1.json` and its `.sig` from **all three**
   hosts, and `release.v1.json` and its `.sig` from all three, independently of which host later serves
   the jar. A host failing for the jar never removes it from the revocation or manifest fetches.
2. All revocation responses are processed first (12.4), then manifests. Among valid manifests the
   highest accepted `seq` wins.
3. **Fresh revocation state** means: the current revocations document is validly signed by the current
   revocation key, unexpired at `effectiveNow`, has `seq >= trust.revocationsSeqFloor` and
   `seq >= manifest.minRevocationsSeq`.
4. **Report-only check** (scheduled or `check`): an update is reported only if fresh revocation state
   exists (fetched now or persisted and still unexpired). Otherwise nothing is reported (DEBUG), and a
   WARN is logged once per day if the state has been stale for more than 7 days.
5. **Staging** (`/quicraft update`), and again at the revalidation before the swap: requires fresh
   revocation state, and at least **2 of the 3 hosts** returned, in this run, a valid revocations
   document whose SHA-256 equals the current one. Otherwise staging fails before download, the
   installed jar is untouched, and the command says which hosts failed. With the two hosts of the
   first alpha, staging needs both.
6. The selected entry's `sha512` must not be in `blockedArtifacts` or the persisted withdrawn set, and
   its signer must not be revoked. Checked again before the swap.
7. A host whose document and `.sig` disagree (`payloadSha256` mismatch) is DEBUG at first; if the same
   host is still torn after 24 h, WARN once per host per day (security review: an endlessly torn host
   was invisible).

- Reason: an attacker controlling one host cannot hide a revocation. Rejected: 1-of-3 (one host
  withholds) and 3-of-3 (any outage blocks updates).
- **APPROVED (staging quorum):** 2 of 3. Effect: an owner whose network blocks two
  hosts cannot stage updates through the updater and installs by hand.
- Limit, stated plainly: the quorum is not proof of freshness. Two hosts replaying the same unexpired
  older document still pass; the bound is the revocation lifetime (12.5). Dependency: blocker 5 (where
  each host serves these files).

### 12.7 Release sequencing and withdrawal (blocker 6)

Specified release sequence (the signing tool itself is blocker 15):

1. Tag the commit. Build the jars. CI may build them; CI never signs.
2. On the offline signing machine, compute size and SHA-512 of each jar, and check the built jar's
   `META-INF/quicraft/updater-trust.json` (12.2 validation rules) and version metadata.
3. Upload the jars to every host. Download each copy back and compare size and SHA-512.
4. Compose `release.v1.json` with `seq = previous + 1`, new `issued` and `expires`, the new entries,
   and `minRevocationsSeq` = current revocations `seq`. Sign with the primary key.
5. Verify the pair with the same verifier code the updater uses.
6. Publish the document and `.sig` to all hosts. Fetch them back from each host and verify.

The manifest is published only after every jar it names is available, so a client never sees a
manifest pointing at a missing jar. A refresh without a release is steps 4 to 6 with the same entries.

Specified withdrawal (bad build, no key compromise):

1. Publish a new manifest (`seq + 1`) that no longer lists the bad version, and lists its platform,
   version and SHA-512 under `withdrawn`.
2. Effects: servers that have not staged it no longer see it; a staged copy fails revalidation before
   the swap and is discarded; servers already running it log WARN and notify OPs once ("running a
   withdrawn release"). The updater never downgrades automatically; the fix is a newer version.
3. Only after step 1 is published on every host, delete or hide the bad jar on the hosts.

Malicious release signed by a leaked primary: the backup key signs a revocations document with the
primary in `revokedKeys`, the bad jar hashes in `blockedArtifacts`, and a `minManifestSeq` above any
legitimate seq used so far. Then a backup-signed release ships a jar with a new trust file (higher
`epoch`, new primary). Servers that already installed the malicious jar are not protected (12.1).

Backup key leaked or lost: owners install a release by hand whose trust file has a higher `epoch` and a
freshly generated revocation key (12.2 transition 6). The updater cannot do this step, by design.

### 12.8 Persisted state, files and growth limits (blocker 12)

Paths are relative to the per-platform updater directory `<updateDir>` (12.14).

| File | Purpose |
| --- | --- |
| `<updateDir>/txn.lock` | OS lock held for a whole transaction (12.9). |
| `<updateDir>/state.lock` | OS lock held for each read-merge-write of the state files (12.9). |
| `<updateDir>/revoked.json` (+ `.prev`) | Permanent restrictions: `revokedKeys`, `blockedArtifacts`. |
| `<updateDir>/state.json` (+ `.prev`) | Everything else: epoch, floors, current revocations, withdrawn, notifications. |
| `<updateDir>/journal.json` | Present only while a transaction is in progress or needs recovery. |
| `<updateDir>/staging/<txid>.part`, `<txid>.jar` | Download, then verified staged copy. |
| `<updateDir>/backup/<txid>.jar` | Copy of the live jar before the swap. |

- The lock files' content is diagnostic only (pid, start time). The OS lock is what counts.
- **Permissions and links (security review).** On POSIX, `<updateDir>` and its subdirectories are
  created `0700`, files `0600`. If `<updateDir>` or any file in it is a symlink, or is not owned by the
  server's user, the updater disables itself (WARN). Every open, hash and delete uses `NOFOLLOW_LINKS`.
  Staging and backup paths are never read from a file: they are always `<updateDir>/staging/<txid>.*`
  and `<updateDir>/backup/<txid>.jar` with `txid` matching `^[0-9a-f]{32}$`.
- **Atomic writes.** Write `<name>.tmp`, `force(true)`, copy the current file to `<name>.prev` (same
  procedure), `ATOMIC_MOVE` the `.tmp` over the target, fsync the directory. Directory fsync and
  `F_FULLFSYNC` on macOS need evidence (blocker 10).
- **Why two files (security review).** Floors, withdrawn entries and notifications can be rebuilt;
  revocations cannot be rebuilt if hosts withhold them. Keeping them apart lets a damaged `state.json`
  be reset without losing revocations.
- **Corruption.** On load, a file that fails parsing or its schema is replaced by its `.prev` if that
  one is valid (WARN). If `state.json` and its `.prev` are both invalid: staging is disabled, checks
  report it, and the owner runs a reset (below). If `revoked.json` and its `.prev` are both invalid:
  the updater disables itself until a reset, which then re-merges every revocations document it can
  fetch. Neither file is ever reset silently.
- **APPROVED (state reset command):** `/quicraft update reset-state` (console only)
  rebuilds `state.json` from the running trust file's seeds, keeps a valid `revoked.json`, and refetches.
  Old floors are lost, so manifests between the jar's `manifestSeqFloor` and the lost floor become
  acceptable again; the downgrade rule still blocks older versions. Alternative: documented manual
  deletion of `state.json` only (never `revoked.json`).
  Security review of 3.6a (2026-10-14) adds:
  - When the old `state.json` (or its `.prev`) is still readable, the reset carries its `withdrawn`
    entries forward, keeping those whose kid is not revoked and whose version is above
    max(running, on-disk). Without this, a reset would re-admit a withdrawn build served by hosts
    that replay an older manifest. When `state.json` is unreadable, `withdrawn` starts empty, and
    that loss is an accepted residual.
  - When the readable state classifies as case 3 (same epoch, other key set) or case 4 (older
    epoch) against the running trust file, the reset is refused, so it cannot lift those
    restrictions.
  - The reset takes `txn.lock` and refuses while `journal.json` exists, so it never runs inside a
    transaction (wired with the command in Wave 4).
  - Admission additionally requires both live-jar hashes to be 128 lowercase hex characters and
    the trust transition to be case 2 (normal); a running trust file not yet applied to the
    persisted state refuses until restart.

#### Initialisation

With no state files, every field starts as follows (peer review), and both files are written before
the first check result is used:

- `revoked.json`: `revokedKeys` empty, `blockedArtifacts` empty.
- `state.json`: `epoch`, `keySetFingerprint` and `revocationKey` from the running trust file;
  `revocations` = `{ "seq": <trust.revocationsSeqFloor>, "sha256": "", "issued": "", "expires": "",
  "minManifestSeq": <trust.manifestSeqFloor> }` (the empty `sha256` marks the seed case of 12.4); `seqByKid` empty; `withdrawn` empty; `lastAnnounced`
  empty string; `warnedBad` empty; `tornSince` empty.
- The clock floor is not stored. It is computed (12.5) from the trust file's `built`, the
  `revocations.issued` and the `issued` values in `seqByKid` of non-revoked kids.

#### `revoked.json` (normative)

```json
{
  "type": "quicraft.revoked",
  "v": 1,
  "revokedKeys": [ { "kid": "0123456789abcdef", "pub": "<64 hex>" } ],
  "blockedArtifacts": [ "<128 hex>" ]
}
```

| Field | Type | Required | Cardinality |
| --- | --- | --- | --- |
| `revokedKeys` | array of `{kid, pub}`; `kid` derived from `pub` | yes | 0..64, unique by `pub` |
| `blockedArtifacts` | array of 128-hex strings | yes | 0..1024, unique |

Size cap 262144 bytes (1024 hashes at about 140 bytes each, plus keys, fit with room).

#### `state.json` (normative)

```json
{
  "type": "quicraft.updater-state",
  "v": 1,
  "epoch": 1,
  "keySetFingerprint": "<64 hex>",
  "revocationKey": "<64 hex>",
  "revocations": { "seq": 3, "sha256": "<64 hex>", "issued": "2026-11-01T12:00:00Z", "expires": "2027-11-01T12:00:00Z", "minManifestSeq": 0 },
  "seqByKid": { "0123456789abcdef": { "seq": 42, "sha256": "<64 hex>", "issued": "2026-11-01T12:00:00Z" } },
  "withdrawn": [ { "sha512": "<128 hex>", "version": "0.4.1", "kid": "0123456789abcdef" } ],
  "lastAnnounced": "0.4.0",
  "warnedBad": [ "<64 hex>" ],
  "tornSince": { "modrinth": "2026-11-01T12:00:00Z" }
}
```

| Field | Type | Required | Cardinality / rule |
| --- | --- | --- | --- |
| `epoch` | integer 1..2^31-1 | yes | 12.2 |
| `keySetFingerprint` | 64 hex | yes | 12.2 |
| `revocationKey` | 64 hex | yes | `pub` of the current revocation key |
| `revocations` | object | yes | `seq` integer, `sha256` 64 hex, or the empty string until the first document is accepted (seed case, 12.4), `issued`/`expires` timestamps or empty, `minManifestSeq` integer |
| `seqByKid` | object, key = kid | yes | 0..32 entries; each `seq` integer, `sha256` 64 hex, `issued` timestamp |
| `withdrawn` | array | yes | 0..256; own platform only; unique by `sha512` |
| `lastAnnounced` | version string or empty | yes | own platform only |
| `warnedBad` | array of 64 hex | yes | 0..32, oldest dropped first (it only limits log lines) |
| `tornSince` | object, key in `modrinth`, `github`, `sudoe` | yes | first time a host was seen torn |

Size cap 131072 bytes.

`liveSha512AtInit` is **not** persisted (peer review). It is held in memory only, computed on every
process init from the verified live bytes (12.11 step 1), and never carried over from a predecessor
process or generation.

#### Growth policy (security and peer review)

- Pruning that never loses a protection: withdrawn entries whose version is not greater than
  `max(running, on-disk)`, withdrawn entries and `seqByKid` entries of revoked kids, and the old revocation
  key's floors on transition 6 (12.2). `warnedBad` is a bounded log-suppression list, not a protection.
- After pruning, if merging a document would exceed a cardinality limit, the document is **not merged**
  and not accepted, the state is unchanged, staging is disabled while that persists, and a WARN is
  logged once per day ("updater state full"). Nothing already persisted is evicted. Only a leaked
  release key (withdrawn, `seqByKid`) or the revocation key (`revoked.json`) can cause this, and revocation
  or an epoch transition clears the first case.

#### `journal.json` (normative)

```json
{
  "type": "quicraft.journal",
  "v": 1,
  "txid": "<32 hex>",
  "phase": "prepared",
  "platform": "fabric-26x",
  "manifestSeq": 42,
  "live":   { "path": "/abs/real/path/mods/quicraft-fabric-26x.jar", "size": 4100000, "sha512": "<128 hex>", "version": "0.3.1" },
  "staged": { "size": 4194304, "sha512": "<128 hex>", "version": "0.4.0" },
  "backupSha512": "<128 hex>",
  "created": "2026-11-02T09:00:00Z",
  "bootAttempts": 0
}
```

| Field | Type | Required | Rule |
| --- | --- | --- | --- |
| `txid` | 32 hex | yes | random; names the staging and backup files |
| `phase` | enum | yes | `prepared`, `swapped`, `boot-pending`, `recovery-required` |
| `platform` | enum | yes | must equal the compiled-in platform |
| `manifestSeq` | integer | yes | manifest that authorised the transaction |
| `live.path` | string ≤ 4096 | yes | absolute real path; only used for comparison with the freshly discovered path (12.11) |
| `live.size`, `staged.size` | integer | yes | bytes |
| `live.sha512`, `staged.sha512`, `backupSha512` | 128 hex | yes | `backupSha512 == live.sha512` |
| `live.version`, `staged.version` | version string | yes | 12.3 grammar |
| `created` | timestamp | yes | |
| `bootAttempts` | integer 0..9 | yes | 12.10 step 7 |

Size cap 8192 bytes. Unknown fields rejected.

Unlike the state files, `journal.json` has no `.prev`. It is written with the same `.tmp`, `force`,
`ATOMIC_MOVE`, directory-fsync procedure. A journal that fails to parse is never replaced by an older
copy: startup recovery (12.11) treats it as `recovery-required` and keeps every update file.

### 12.9 Locking, admission and cancel (blocker 12)

- **Two locks**, each an in-process `ReentrantLock` plus the OS file lock (`FileChannel.tryLock`):
  - `txn.lock`: held by the thread running `/quicraft update` from step 1 until the swap is done or
    the transaction ends, and by startup recovery.
  - `state.lock`: held for every **read-merge-write** of `state.json` and `revoked.json`: acquire,
    re-read both files from disk, merge (union and max), write, release. Scheduled checks take only this
    lock. Reason: a check that wrote a stale snapshot could undo a revocation or floor merged by a
    command in between (security and peer review). Rejected: locking only the write.
  - Lock order is always `txn` then `state`. A busy OS lock held by another process means "another
    server uses this directory": refuse with a message, never wait.
- **Admission (security and peer review).** `/quicraft update` is refused when any of these is true:
  a `journal.json` exists in any phase; the live jar's hash differs from the in-memory `liveSha512AtInit` of
  this process (the jar changed on disk since start); the trust epoch is lower than the persisted one; the state is full; or
  QUICraft's init has not finished (12.13). A swapped-but-not-restarted update therefore blocks a
  second one until restart and health pass.
- **Cancel (peer review).** `/quicraft update cancel` never takes `txn.lock`. It sets the cancel token
  of the active in-process transaction. The transaction thread, which owns the lock, checks the token
  between download chunks and before steps 4, 5 and 6 (12.10), and does its own cleanup: delete its
  `staging/<txid>.*` files and, if it got that far, `journal.json` (phase `prepared`, live jar
  untouched). After step 6 started, cancel answers "installed, restart pending; cancel does not roll
  back". With no active transaction, cancel deletes nothing.

### 12.10 Transaction (Linux and macOS only)

Windows (peer review): steps 5 to 7 never run on Windows. A Windows transaction stops at
`verified-staged` and the rest is gated on blocker 11 (post-exit helper). On Linux and macOS steps 5 to
7 run: the swap is enabled there for the alpha (blocker 10, 12.13), unless the owner sets
`update.install=stage-only` (section 6).

1. `checking`: under `txn.lock`, fetch and verify (12.12), admission rules (12.9).
2. `downloading`: write `staging/<txid>.part`, created with `CREATE_NEW` and `NOFOLLOW_LINKS`; stop at
   `size + 1` bytes; HTTPS only, fixed hosts (blocker 5).
3. `verified-staged`: size and SHA-512 match; `force(true)` on the file; `ATOMIC_MOVE` to
   `staging/<txid>.jar`; fsync `staging/` (peer review: the bytes and the name are durable before
   anything depends on them).
4. Revalidate manifest, keys, revocations, freshness, version against `max(running, on-disk)`,
   withdrawn and blocked sets (12.12). The revalidated entry's `platform`, `version`, `size` and
   `sha512` must equal those of the staged bytes (peer review). If they differ (for example a newer
   manifest now names another build), delete `staging/<txid>.*` and restart the transaction from step 1
   under a new `txid`, at most once per command; a second mismatch ends the command with a message.
5. `backup-ready`: validate the staged jar's own trust file and version metadata (12.2) without
   loading it as code; copy the live jar to `backup/<txid>.jar`, `force(true)`, fsync `backup/`,
   check its SHA-512; write the journal with `phase=prepared` (atomic). Log the exact manual rollback
   command. As implemented, the line goes to a mandatory progress sink (`TransactionProgress`, which
   the platform facade wires to the server log at WARN and to the command output). If emitting it
   fails, the transaction ends FAILED before step 6 ("the rollback instructions could not be logged;
   nothing was installed") and cleans up: a swap never happens without the logged command.
6. `installed-pending-restart`: take `state.lock` (peer review: no check may merge a revocation
   between the last authorisation and the move) and hold it through this step. Under it: re-read
   `state.json` and `revoked.json` from disk and re-authorise from that persisted state alone (no
   network): the full set of actually verified signers still provides release authorization and,
   for a changed key-set fingerprint, current non-revoked backup authentication (12.2); the staged `sha512` is not blocked
   or withdrawn, the manifest `seq` is still at or above the floor, the revocation state is still fresh,
   and the staged jar's trust file (held in memory since step 5) passes the 12.2 staged-jar validation
   again against this freshly read state (peer review: a revocation merged after step 5 that hits a key
   in the staged trust file must stop the swap). If any of these fails: release `state.lock`, delete
   `staging/<txid>.*` and the journal under `txn.lock`, and end the command with the reason (security
   review); the live jar is unchanged. As implemented, this attempt's own backup is deleted too: the
   live jar was never replaced, so the backup is a copy of the running jar and restores nothing.
   Backups of earlier transactions are never touched here. Also as implemented: the live jar is
   re-hashed immediately before the move, and a changed live jar ends the command with "the live jar
   changed during the update; nothing was installed"; after the move the live jar is re-hashed
   against `staged.sha512`, and a mismatch keeps the `prepared` journal and the backup as recovery
   evidence (12.11 step 5 then reports `recovery-required`), logs a WARN and fails the command. Otherwise re-hash `staging/<txid>.jar` (security review); `ATOMIC_MOVE` it over the live path (step 1 already
   downgraded to staging when `<updateDir>` and the live jar are on different file stores; if the move
   still reports `AtomicMoveNotSupportedException` the transaction ends before any change, journal
   removed); fsync the live jar's directory; set `phase=swapped`. Release `state.lock`, then
   `txn.lock`. A scheduled check that wants `state.lock` meanwhile waits for this step (in-process) or
   skips its write until the next check (other process); the step does no network I/O, so the wait is
   short. From here on the process reads
   no own-jar resource (12.13).
7. After restart, in the new generation: startup recovery (12.11) sets `phase=boot-pending` and
   increments `bootAttempts` before anything else -> `health-check-pending`. When the health criteria
   pass (blocker 9): delete the journal -> `healthy` -> `idle`. Then delete backups of **earlier**
   transactions only. The backup of this transaction (the previous generation) is kept until the next
   transaction passes health, so the running generation's predecessor is always restorable and the
   current backup is never deleted by a later admission (security review).

- **Swap target.** The staged jar replaces the live jar at its existing path; the file name is not
  changed. Reason: a rename to a versioned name takes two operations, leaving a moment with two or
  zero jars. Rejected: versioned rename.

### 12.11 Startup recovery (runs first in updater init, under `txn.lock`, never throws)

1. Discover the live jar (12.14) and hash it. If discovery fails, the updater is disabled for this
   start and no file is touched.
2. No journal: delete `staging/` files whose name does not match a journal. Done.
3. Journal unparsable or schema-invalid, `platform` wrong, or `live.path` not equal to the discovered
   path: `recovery-required`. Keep every file; WARN with manual steps. Nothing is deleted.
4. **Manual restore, checked before any phase (peer review):** if live = `backupSha512` and
   `backupSha512 != staged.sha512`, the owner restored the old jar. Delete `staging/<txid>.*` and the
   journal, keep the backup, INFO, `idle`.
5. `phase=prepared`:
   - live = `live.sha512`: the swap did not happen. Delete `staging/<txid>.*` and the journal. `idle`.
   - live = `staged.sha512`: the swap happened but the journal was not advanced. Continue as 6.
   - otherwise: `recovery-required` (someone else changed the jar).
6. `phase=swapped`: if the running version equals `staged.version` and live = `staged.sha512`, set
   `phase=boot-pending`, `bootAttempts = 1` (written before anything else), continue with step 7 of
   12.10. Otherwise `recovery-required`.
7. `phase=boot-pending` at startup (the previous boot of the new generation did not pass the health
   criteria): first re-check that the running version equals `staged.version` and live =
   `staged.sha512` (peer review); if not, `recovery-required`. If `bootAttempts < 2`, increment and
   continue; otherwise `recovery-required`.
8. `recovery-required`: no new downloads (admission). WARN on every start and notify OPs once per start
   with the backup path and the manual command. Step 4 recognises a manual restore on the next start;
   an owner who installs another jar deletes the journal by hand.

Limit: if the new generation fails before the updater's init runs (for example the loader rejects the
jar), no project code can recover it. That is why the manual rollback command is logged at step 5 of
12.10, before the swap.

**Baseline: restoration is manual only** (peer review). The owner stops the server, copies
`backup/<txid>.jar` over the live jar, and starts it; step 4 recognises the restore.

The user approved manual restore as the v1 baseline. There is no `/quicraft update rollback`
command. A future command would need separate admission, validation and recovery design.

### 12.12 Verification algorithm

Revocations, per host (all hosts before any manifest):

- R1. HTTPS GET with the size cap; abort past the cap.
- R2. Fetch the `.sig` (cap 4096) and parse it strictly.
- R3. SHA-256 of the document bytes equals `payloadSha256`; else torn pair (12.6 item 7), skip host.
- R4. A signature entry verifies under the **current** revocation key (not revoked). Otherwise bad
  signature: one WARN per distinct document.
- R5. Parse strictly; `type` is `quicraft.revocations`, `v` is 1.
- R6. Structural times: `issued < expires`, lifetime ≤ 400 days. Failing: reject as malformed.
- R7. Restriction merge (12.4) under `state.lock`. No expiry or `seq` condition here.
- R8. Current-document acceptance follows the sequence/seed rules (12.4) and requires
  `issued <= systemNow + 24 h`. Expired documents may become current; freshness is a separate test
  requiring `expires > effectiveNow`. A future-issued document still adds restrictions at R7,
  but cannot become current or contribute a clock floor. Stale or expired current metadata never
  provides fresh staging coverage.

Manifests, per host, after every revocation response is processed:

- M1 to M3 as R1 to R3.
- M4. A signature entry verifies under a trusted key with the `release` role whose `pub` is not in
  `revokedKeys`. Otherwise bad signature (one WARN per distinct document).
- M5. Parse strictly; `type` is `quicraft.release`, `v` is 1.
- M6. `issued < expires`, lifetime ≤ 45 days, `issued <= systemNow + 24 h`, `expires > effectiveNow`.
- M7. Sequence rules (12.4); persist `seqByKid`, clock contribution and own-platform withdrawn entries
  under `state.lock`.
- M8. Of the accepted manifests, use the highest `seq`. Select the entry for the compiled-in platform
  with an exact target match; version greater than `max(running, on-disk)`; hash not blocked or
  withdrawn; coverage (12.6).

Staging repeats R1 to M8 at step 4 of 12.10, and step 6 re-authorises from persisted state under
`state.lock`.

#### Build metadata (`META-INF/quicraft/build.json`, normative)

Each of the project's own jars carries its platform and version, so a staged jar can be matched
against its release entry without loading any class:

```json
{ "type": "quicraft.build", "v": 1, "platform": "fabric-26x", "version": "0.4.0" }
```

- Size cap 1024 bytes; strict parsing as in 12.3; unknown fields rejected.
- `platform` must equal both the release entry's `platform` and the compiled-in platform;
  `version` must equal the entry's `version` (12.3 grammar, compared as versions).
- A staged jar without the entry, or with more than one, is rejected. The entry is read as data only.

### 12.13 Live-swap evidence and rules (basis for blocker 10)

Evidence (experiment, 2026-10-13, scratch only, recorded in the Phase 4 plan,
`.agents/plans/2026-10-13-phase4-alpha.md`, "Live-swap evidence"): a test plugin and mod with a lazily
loaded class, a resource, and (v2 only) a new class; v1 runs, the jar is replaced, then the lazy reads
run; 3 trials per mode, then a restart.

| Loader | Replace mode | Lazy class after swap | Own-jar resource | v2-only class | Shutdown |
| --- | --- | --- | --- | --- | --- |
| Velocity 4.2.0-30, JDK 25.0.4.1 | atomic rename | old | **new** | not found | clean |
| Velocity 4.2.0-30 | in-place overwrite | `NoClassDefFoundError` | new | not found | clean |
| Fabric Loader 0.19.5, MC 26.1.2, JDK 25.0.4.1 | atomic rename | old | old | not found | clean |
| Fabric Loader 0.19.5 | in-place overwrite | new (mixed) | `ZipException` | not found | clean |
| Fabric Loader 0.19.5, MC 1.21.11, JDK 25 | atomic rename / in-place | old / new | old / `ZipException` | not found | clean |
| all three | restart after swap | new | new | found | clean |

Rules derived from it (user-approved semantics; enabled on Linux and macOS for the alpha, see
blocker 10):

- In-place overwrite is never used (unsafe on both loaders). Only `ATOMIC_MOVE` (12.10 step 6).
- **The new jar takes effect only at restart.** The running version stays the old one, and the
  updater reports "installed, restart required".
- **Nothing reads its own jar after a swap.** Velocity serves resources from the **new** jar while
  classes come from the old one. So every QUICraft own-jar resource (the trust file, version metadata,
  config defaults, language files, and the QUIC native library that Netty extracts from the jar, plus
  `META-INF/services` lookups) is read or loaded during init. Staging is refused until init has
  finished (12.9), so no lazy read is pending. The updater keeps everything it needs in memory.
  Fabric showed a consistent old view, but the same rule applies to both loaders.
- Required test: swap, then run a check and the other QUICraft code paths that might read resources,
  on Velocity and Fabric; nothing may read from the new jar.
- Gaps recorded with this evidence (blocker 10): a live server swap on JDK 21 and on macOS (1.21.11
  ran on JDK 25; the macOS CI job below runs the updater suite, not a server), directory fsync and
  `F_FULLFSYNC` on macOS, crash-recovery tests of 12.11, and Velocity builds other than 4.2.0-30.
- macOS CI (`updater-macos` workflow, run 38600235420, 2026-10-14, feat d9f3514): the full updater
  suite, transaction and crash-matrix tests included, passed 1166/0 on macos-latest (ARM64) and
  macos-15-intel, each with JDK 21 and JDK 25 as the test launcher. The first run (38577920195)
  failed 2 tests: fixtures used the symlinked macOS temp path (`/var` -> `/private/var`), and the
  ownership scan rejected a `.owner-*.probe` file removed between listing and check. Both fixed.

Swap-then-read run (2026-10-14, manual, scratch only; the required test above, run once by hand,
not yet automated): `0.1.0-alpha.1` jars with localhost dev origins on Velocity 4.2.0-30, Fabric
26.1.2 and Fabric 1.21.11 (Loader 0.19.5, JDK 25, Linux). While running, the jar was replaced by
`rename(2)` with either a zip holding no QUICraft entries or the same module's `0.1.0-SNAPSHOT` jar.
Then: a server-list ping, `check`, `check -s`, `update`, `cancel`, `reset-state`, another ping and a
stop.

| Loader | Probe jar | Ping advertisement | check / cancel / reset-state | `update` | Exceptions / stop |
| --- | --- | --- | --- | --- | --- |
| Velocity 4.2.0-30 | empty zip | unchanged | normal | refused: live jar does not carry the QUICraft id | 0 / clean |
| Velocity 4.2.0-30 | SNAPSHOT jar | unchanged | normal, still alpha.1 | refused: development build | 0 / clean |
| Fabric 26.1.2 | empty zip | unchanged | normal | refused: no QUICraft id | 0 / clean |
| Fabric 26.1.2 | SNAPSHOT jar | unchanged | normal, still alpha.1 | refused: development build | 0 / clean |
| Fabric 1.21.11 | empty zip | unchanged | normal | refused: no QUICraft id | 0 / clean |

Only staging looks at the file, and it does so on purpose: it validates the on-disk live jar before
anything else (12.14) and fails closed. Nothing else read the new jar. Not covered by this run: a
new QUIC connection after the swap (the native is loaded at listener start), JDK 21, macOS.

### 12.14 Installed-jar discovery and placement (basis for blocker 16)

Sources: an explorer report (`.agents/opencode-runs/phase4-jar-discovery.txt`) plus my own checks.
**Verified** means I checked it against the named local artifact; **unverified** means it rests on the
report's reading of upstream source.

Velocity (4.2.0):

- Discovery: the plugin's `PluginContainer.getDescription().getSource()`, an `Optional<Path>`
  (verified: `javap` on `velocity-api-4.2.0.jar`). Empty -> updater disabled. The path is the
  directory-stream entry under `plugins/`, not normalised (unverified: upstream
  `VelocityPluginDescription`, `JavaPluginLoader`); the updater makes it absolute, normalises it and
  resolves the real path itself.
- Scan: only `plugins/`, one level, filter `File.isFile()` and name ending `.jar`, no hidden-file
  filter (verified: `javap -c` on `VelocityPluginManager` and the `"plugins"` constant in
  `VelocityServer`, `velocity-4.2.0-30.jar`). So a `.quicraft-x.jar` directly in `plugins/` would be
  loaded; a `.jar.bak` would not.
- `<updateDir>` = `plugins/quicraft/update/` (subdirectories are not scanned).

Fabric (Loader 0.19.5):

- Discovery: `FabricLoader.getInstance().getModContainer("quicraft")`, then `getOrigin()`. Require
  `Kind.PATH` with exactly one path; `NESTED` and `UNKNOWN` -> updater disabled (verified:
  `ModOrigin.java` in `fabric-loader-0.19.5-sources.jar`; `getPaths()` throws for other kinds).
  Directories (development runs) and paths whose parent is not the mods directory (for example
  `fabric.addMods`) are rejected.
- Mods directory: system property `fabric.modsFolder` if set, else `<gameDir>/mods` (verified:
  `FabricLoaderImpl.java:623-626`). Scan: one level, `FOLLOW_LINKS`, regular file, not hidden, name
  ends `.jar` and does not start with `.` (verified: `DirectoryModCandidateFinder.java:57`, `82-93`).
  Paths from the loader are absolute and normalised (unverified: `LoaderUtil`).
- `<updateDir>` = `config/quicraft/update/` (from `FabricLoader.getConfigDir()`); `config/` is not a
  scan root.

Both:

- Validation: the discovered path, after `toRealPath()`, is a regular file, not a symlink
  (`NOFOLLOW_LINKS`), ends `.jar`, its parent is the real scan directory, and it is the only jar in
  that directory carrying QUICraft's plugin or mod id (checked at init by reading each jar's
  `velocity-plugin.json` or `fabric.mod.json`). Otherwise the updater is disabled with a WARN.
- Same file store: `ATOMIC_MOVE` needs `<updateDir>` and the scan directory on one file store
  (container setups often mount `mods/` separately). If they differ, the transaction downgrades the
  capability to verified staging before any download, like the directory-fsync downgrade: the jar is
  downloaded, verified and staged with `STAGED_ONLY`, the live jar is never written, and the message
  names the staged jar path and the manual steps (stop the server, copy the staged jar over the live
  jar, start the server).
- **Peer review (separate file stores):** the former "fail with instructions" rule was wrong: that
  refusal runs before any download, so the owner had no release jar to copy. Superseded by the
  downgrade above. Alternative: put a temporary `.quicraft-<txid>.swap` copy (no `.jar` suffix, hidden;
  neither loader loads it, per the filters above) in the scan directory just before the move. That
  conflicts with section 5's rule that staging lives outside loader-scanned directories, so I did not
  choose it.

### 12.15 Dependencies on blockers not resolved here

- Blocker 5: URLs per host, redirect policy, which host serves which file.
  Settled for the first alpha: the compiled-in hosts are `github` (`https://github.com`, repository
  `/tnacomtr/QUICraft`) and `sudoe` (`https://git.sudoe.rs`, repository `/tna/QUICraft`). There is
  no `modrinth` host, because Modrinth is not a publish target yet. Every host serves the four
  metadata files from the `updater-metadata` release of its own repository, replaced on every
  publish:
  `<origin><repo>/releases/download/updater-metadata/release.v1.json` and the same path for
  `release.v1.json.sig`, `revocations.v1.json` and `revocations.v1.json.sig`; an artifact comes from
  `<origin><repo>/releases/download/<tag>/<file>`, with the tag and file name re-checked against
  `v[0-9A-Za-z.-]{1,40}` and `[A-Za-z0-9._+-]{1,124}.jar` before they become a URL.
  Each host origin is `https://` with no path, query or fragment. Its host part is
  a plain LDH DNS name (two or more labels of 1..63 letters, digits or hyphens, no leading or trailing
  hyphen, no IP literals, no trailing dot) or exactly `localhost` (tests only). Redirects are denied
  unless an injected policy allows them.
  The production redirect policy allows a hop only to the release host's own origin host or to a
  fixed CDN allowlist per host (GitHub: `release-assets.githubusercontent.com` and
  `objects.githubusercontent.com`, which is where a GitHub release download answers with a 302;
  Forgejo answers a release download directly, so the `sudoe` allowlist is empty), never an IP
  literal and never another configured host's origin, so one origin cannot count as two hosts in the
  2-of-3 coverage rule. A hop must also be absolute HTTPS with no userinfo, no fragment and no port
  other than 443. Response header size is bounded only by the JDK HTTP client
  (`jdk.http.maxHeaderSize`); the minimum supported JDK update must have that limit, checked
  before release.
- Blocker 9: health criteria that end `health-check-pending`.
- Blocker 10: open gaps listed in 12.13.
- Blocker 11: Windows steps 5 to 7.
- Blocker 15: the signing tool, which must reuse this verifier, and the yearly backup-key ceremony.
- Blocker 16: proposed in 12.14; still needs tests on every supported build.
