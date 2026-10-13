# Release checklist (Phase 4 alpha, not approved)

**Status: checklist only. Nothing here is released, published, signed, or approved.** The Phase 4
public alpha would ship the Velocity plugin and the Fabric builds for 26.x and 1.21.11 once the gates
below pass and the user approves. No publishing command is run from this document. The signing tool
and the release pipeline do not exist yet.

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

Release blockers from `CLAUDE.md` (Phase 4) and the plan. Runtime protections are not active yet:

- [ ] A real Retry token handler. The listener still uses `NoRetryTokenHandler`
      (`core/.../transport/QuicServer.java:133`). `HmacRetryTokenHandler` is implemented and unit-tested,
      but not wired into the listener.
      `InsecureQuicTokenHandler` from Netty's examples must never ship.
- [ ] Per-IP and global limits on connection attempts and open connections. `ConnectionAdmission` is
      an implemented, unit-tested state primitive. Its integration (adaptive Retry policy,
      lifecycle, platform settings) is a follow-up.
- [ ] Server-owner UDP firewall and DDoS documentation reviewed against
      [`server-security.md`](server-security.md).

## 5. Updater gates

The updater is not in the alpha until all of these pass. See [`updater.md`](updater.md) section 10:

- [ ] Manifest and key-set wire formats frozen and documented.
- [ ] Signing tool exists and produces `release.json` and `release.json.sig`.
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
- [ ] Windows: either a verified post-exit helper or an explicit documented statement that Windows has
      no live updater in the alpha.
- [ ] Updater tests run in testkit: mock release server, restart, and TCP and QUIC joins still work.

## 6. Signing and key process (owner-managed)

- [ ] Primary Ed25519 key generated offline by the owner. Never in the repo or CI.
- [ ] Backup Ed25519 key generated offline, on separate media with a paper copy.
- [ ] Public key IDs recorded in the release notes and built into the jar.
- [ ] Signing runs offline, outside CI. CI never sees private keys.
- [ ] The owner records custody of both keys. The project does not store them.

Key generation and the signing script are not written. Do not describe them as existing.

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
