# Release checklist (Phase 4 alpha, not approved)

**Status: checklist only. Nothing here is released, published, signed, or approved.** The Phase 4
public alpha would ship the Velocity plugin and the Fabric builds for 26.x and 1.21.11 once the gates
below pass and the user approves. No publishing command is run from this document. The offline
release-metadata tool exists (`release-tool/`, section 6); the release pipeline does not exist yet.

Local input, not links: the user's updater draft is `docs/.plans/updater-plan.md` (untracked, local).
The Phase 4 plan is `.agents/plans/2026-10-13-phase4-alpha.md`. The companion docs are `docs/updater.md`
and `docs/server-security.md`.

## 1. Artifacts and targets

- [ ] Velocity plugin jar. Target: Java 25 (`velocity/build.gradle.kts:14`, velocity-api 4.2.0). The
      README records Velocity 4.2 as the tested proxy.
- [ ] Fabric `quicraft-fabric-26x`: Minecraft 26.1 to 26.3, Java 25 (`fabric-26x/gradle.properties`,
      `javaRelease=25`). Shared source with the 1.21.11 jar; separate jars.
- [ ] Fabric `quicraft-fabric-1.21.11`: Java 21 (`fabric-1.21.11/gradle.properties`, `javaRelease=21`).
      Intermediary-remapped.
- [ ] `core` stays Java 8 bytecode (`core/build.gradle.kts`).
- [ ] [`version-matrix.md`](version-matrix.md) matches these artifacts before release.
- [ ] Future roadmap targets (1.21 to 1.21.10 on Fabric, NeoForge, Paper, 1.20.1, 1.8.9) are not in
      this alpha. The README must say so.

## 2. Build, license, and notice gates

- [ ] Clean-checkout build of every alpha artifact (`./gradlew`). Not run for this document.
- [ ] License report on every build fails on AGPL or any license not on the allowlist (`CLAUDE.md`,
      Licensing).
- [ ] Shaded-notice check passes for the release. It already exists:
      `checkShadedNotices` (`ShadedNoticeCheck`, `core/build.gradle.kts:167-176`). It compares the notice
      and license files kept in the shaded jar against the source jars. Its passing run is a release
      gate; the result is not recorded here.
- [ ] Release notes record the exact Netty, `netty-codec-native-quic`, quiche, and BoringSSL
      versions. Confirm the BoringSSL pin is after the 2025-02-03 relicense (`CLAUDE.md` table).
- [ ] Every released jar maps to a public, tagged commit (Corresponding Source).
- [ ] No decompiled or remapped Minecraft sources or game assets in the artifact or the repo.
- [ ] Every source file has an SPDX header (`CLAUDE.md`, Licensing). Confirm before tagging.

## 3. Native and fallback gates

- [ ] Native loading verified from the shaded jar on every native platform (`CLAUDE.md`, Architecture).
- [ ] Platforms without a native run TCP-only and log once at INFO. They never crash.
- [ ] Fault injection: with project code forced to throw, vanilla TCP join and ping still work.
- [ ] Fallback tests pass when UDP is dropped, when the handshake fails, and when the fingerprint does
      not match ([`protocol.md`](protocol.md)).

## 4. Server-side protections (must exist before alpha)

Release blockers from `CLAUDE.md` (Phase 4) and the plan. Runtime protections are wired into both
listener binds, merged on `feat/phase4-alpha` after verifier, peer and security review:

- [x] A real Retry token handler. `HmacRetryTokenHandler` (through `AdmissionTokenHandler`) is wired
      into both listener binds, merged
      on `feat/phase4-alpha` after verifier, peer and security review.
      `InsecureQuicTokenHandler` from Netty's examples must never ship.
- [x] Per-IP and global limits on connection attempts and open connections. `ConnectionAdmission` is
      wired into both listener binds, merged on `feat/phase4-alpha` after verifier, peer and security
      review; the settings are documented in [`server-security.md`](server-security.md) section 3.
- [x] Server-owner UDP firewall and DDoS documentation security-reviewed against
      [`server-security.md`](server-security.md) (2026-10-13). Includes TCP-only frontend bypass
      and hidden-origin warnings. This is a documentation review, not firewall execution,
      provider coverage confirmation, load testing, or release approval.

## 5. Updater gates

The updater is not in the alpha until all of these pass. See [`updater.md`](updater.md) section 10:

- [ ] Manifest and key-set wire formats frozen and documented.
- [ ] Signing tool exists and produces `release.v1.json` and `release.v1.json.sig`.
- [ ] Signature, revocation, freshness, and downgrade tests pass ([`updater.md`](updater.md) section 8).
- [ ] Live `ATOMIC_MOVE` replacement on Linux and macOS: loader-generation safety evidence and
      crash-recovery tests. Without this evidence release is blocked. Failure must keep the installed
      bytes and backup intact. In-place rewriting is not a fallback.
- [ ] Dedicated-server-only gating proven for the Fabric common entrypoint.
- [ ] Installed-jar discovery and placement proven per loader: platform-validated metadata (not a
      guessed name, not `CodeSource` alone on Fabric), one canonical top-level jar, no duplicate
      loadable jars in `plugins/` or `mods/`, backup and staging outside loader-scanned directories
      (`updater.md`, "Installed jar discovery and placement").
- [ ] Revocation metadata freshness tests: stale revocation replay rejected; persisted union kept.
- [ ] Release trust guard: run `./gradlew clean checkReleaseTrust -Pversion=<release version>
      -Pquicraft.updaterTrustFile=<owner trust file>` from a clean checkout and a clean `build/libs`
      (stale jars built with the development trust carry the RFC 8032 TEST keys). It fails on the
      development trust file (`updater/dev-trust/updater-trust.json`, TEST keys only), on any TEST
      key, on a version outside the updater grammar, on outer jars whose `META-INF/quicraft` trust
      bytes differ from the configured file, and whenever `-Pquicraft.allowDevTrust` is set. Never set
      `allowDevTrust` for a release; it exists only for local and testkit update runs. Any future
      release or publish task must depend on `checkReleaseTrust`.
- [ ] The version grammar exists twice: `UpdateVersion` (runtime) and `UpdaterTrust` in
      `build-logic` (build time, with a 256-character cap and the leading-zero rule). Change both
      together; there is no automated cross-check yet.
- [ ] Windows: either a verified post-exit helper or an explicit documented statement that Windows has
      no live updater in the alpha.
- [ ] Updater tests run in testkit: mock release server, restart, and TCP and QUIC joins still work.

## 6. Signing and key process (owner-managed)

- [ ] Primary Ed25519 key generated offline by the owner. Never in the repo or CI.
- [ ] Backup Ed25519 key generated offline, on separate media with a paper copy.
- [ ] Public key IDs recorded in the release notes and built into the jar.
- [ ] Signing runs offline, outside CI. CI never sees private keys.
- [ ] The owner records custody of both keys. The project does not store them.

Key generation is not written, and the tool never signs: it holds no private key. Do not describe key
generation as existing.

### Signing: the offline release-metadata tool

`release-tool/` (never shipped, no other module depends on it) writes the exact bytes of
`release.v1.json` and `revocations.v1.json`, prints the OpenSSL command for each trust key, assembles
the `.sig` file and re-checks every pair with the updater's own parser, `SignatureVerifier` and
`TrustSnapshot` ([`updater.md`](updater.md) 12.2, 12.3, 12.12). It reads only the public trust file:
it never generates, reads or stores a private key, and it never uses the network. Keys stay
owner-managed and offline (owner decision, `updater.md` section 2).

Run every command from the repository root; `--trust` then defaults to
`updater/release-trust/updater-trust.json`. The first alpha is `v0.1.0-alpha.1`, and `TS` is a strict
`YYYY-MM-DDTHH:MM:SSZ` UTC timestamp. `--seq` is always `previous + 1` and the owner supplies it: the
tool never guesses a sequence, never publishes and never deletes a file.

The revocations document goes first ([`updater.md`](updater.md) 12.7), because staging needs it:

```sh
./gradlew :release-tool:run --args="compose-revocations --out revocations.v1.json \
    --seq 1 --issued 2026-11-01T12:00:00Z --days 365 --min-manifest-seq 0"
./gradlew :release-tool:run --args="payload-info --payload revocations.v1.json"
# Owner, offline: run the printed openssl pkeyutl -sign command with the backup key
# (the only revocation-role key) and keep the private key on the offline machine.
./gradlew :release-tool:run --args="assemble-sig --payload revocations.v1.json \
    --sig <kid>=revocations.v1.json.<kid>.sigbin --out revocations.v1.json.sig"
./gradlew :release-tool:run --args="verify --trust updater/release-trust/updater-trust.json \
    --payload revocations.v1.json --sig revocations.v1.json.sig"
```

Then the manifest, after every jar it names is uploaded and read back ([`updater.md`](updater.md)
12.7 steps 3 to 6). Each `--entry` is `PLATFORM=JARPATH=TARGET1,TARGET2,...`; the tool reads the
jar's `META-INF/quicraft/build.json` for its platform and version, hashes the jar bytes, and refuses a
jar whose `META-INF/quicraft/updater-trust.json` is not byte-identical to `--trust`:

```sh
./gradlew :release-tool:run --args="compose-release --out release.v1.json --seq 1 \
    --days 30 --min-revocations-seq 1 --tag v0.1.0-alpha.1 \
    --entry fabric-26x=build/libs/quicraft-fabric-26x-0.1.0-alpha.1.jar=26.1,26.1.1,26.3"
./gradlew :release-tool:run --args="payload-info --payload release.v1.json"
# Owner, offline: run the printed openssl pkeyutl -sign command with the primary key.
./gradlew :release-tool:run --args="assemble-sig --payload release.v1.json \
    --sig <kid>=release.v1.json.<kid>.sigbin --out release.v1.json.sig"
./gradlew :release-tool:run --args="verify --trust updater/release-trust/updater-trust.json \
    --payload release.v1.json --sig release.v1.json.sig"
```

Notes: `--days` accepts 1 to 45 for a manifest and 1 to 400 for revocations; `--issued` defaults to
now (whole UTC seconds) only for `compose-release`; `assemble-sig` accepts the raw 64-byte signature
or 128 hex characters per `--sig KID=SIGFILE` and exits non-zero (keeping the written file) when the
pair does not verify; `verify` prints which kids verified and fails on a torn pair, an unknown kid,
the wrong role for the document type, or an expired document. The tool does not yet check
`revocationsSeqFloor` against the newest revocations document (`updater.md` 12.2, blocker 15), so that
comparison stays a manual step.

## 7. Disclosure, hosting, and publishing (nothing published)

- [ ] README and project pages disclose the update check (hosts, data sent, how to turn it off).
      Not present yet; required before release.
- [ ] Modrinth content rules re-read live before release (`updater.md` section 11).
- [ ] Hangar guidelines re-read before the first Hangar upload.
- [ ] Publish to Modrinth (primary), GitHub, and `git.sudoe.rs` only as the user approves.
- [ ] Owner has explicitly approved publication. Pushing, tagging, and uploading are outward-facing.

## 8. Rollback

- [ ] Each release keeps the previous jar available for manual reinstall.
- [ ] Owners can revert by replacing the jar by hand and restarting. The updater backup does not
      replace a documented manual path.
- [ ] A release with a broken signature or revocation is withdrawn by a process that is still
      unresolved ([`updater.md`](updater.md) section 10, item 6).

## 9. Loader safety and updater test matrix

- [ ] Velocity: replace and restart on every Velocity build the alpha supports.
- [ ] Fabric dedicated server: replace and restart on each supported game version.
- [ ] Interrupted download, interrupted replace, and failure after replace each recover to a known
      state ([`updater.md`](updater.md) section 5).
- [ ] Running and on-disk versions are reported distinctly in logs and commands.

## 10. Blocked claims

Do not claim any of these until the matching gate passes:

- Retry tokens are active in production.
- Per-IP or global limits are active.
- The updater is implemented or released.
- Windows has a live updater.
- Live atomic replacement is proven safe for any loader.
- IPv6 QUIC behavior has been tested by this project.
