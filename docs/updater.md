# Server updater (specification)

**Status: in-progress specification. Not implemented. Not approved for release.** Nothing here is a
frozen wire format or a shipped feature. Release is blocked until the blockers in section 10 are
resolved.

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

1. Manifest, `release.json.sig`, key-set, and `revocations.json` wire formats.
2. Signed-byte, bounded-parse, duplicate-field, and sequence/expiry encodings.
3. Freshness thresholds and clock policy.
4. Revocation coverage rule when a source fails (fallback must not weaken fresh coverage).
5. Exact endpoints, CDN redirects, and the role of `git.sudoe.rs` in source priority.
6. Release sequencing and withdrawal.
7. Dedicated-server gating mechanism (section 7).
8. Permission integration for Fabric and Velocity, and command registration.
9. Platform health criteria and mechanism (backup retention until they pass is unconditional).
10. Loader-generation safety for live `ATOMIC_MOVE` replacement on Linux and macOS, with crash-recovery
    evidence. Release is blocked until it exists.
11. Windows post-exit helper design and verification.
12. Lock file locations, journal format, and recovery procedure.
13. Disclosure text in the README and on project pages (required before release).
14. Hosting-rule re-checks (section 11).
15. Signing tool, key generation, and the offline key process (see `release.md`).
16. Loader-specific installed-jar discovery, and backup and staging locations outside loader-scanned
    directories (section 5, "Installed jar discovery and placement").
17. Revocation metadata encodings, clock policy, and required coverage (section 4).

## 11. Hosting rules (re-check required)

- Modrinth: the draft cites the "clear disclosure" rule for software that contacts a remote server.
  Re-read the live rules before release. Not verified here.
- Hangar: the draft found no rule on runtime downloads but could not fetch the page. Re-read before
  the first Hangar upload.
- Rules change without versioning. Re-check before the first release that ships the updater.
