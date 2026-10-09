# CLAUDE.md

Guidance for Claude Code working in this repository. Read this file fully before every session.

## What this project is

A **QUIC support layer for Minecraft Java Edition**: a client mod plus server/proxy plugins that let a client and a server talk over QUIC (UDP) when both sides support it.

**QUIC is the first-class transport; TCP is the compatibility path.** This project **adds** QUIC; it never removes, replaces or degrades TCP:

- The vanilla TCP listener stays exactly as it is. Vanilla clients, and clients without this mod, connect over TCP with no difference at all.
- A server with this project installed **advertises** QUIC support. A client with this mod that sees the advertisement **uses QUIC**. In every other case (no advertisement, mod missing on either side, UDP blocked, handshake failure, unsupported platform) the connection uses **TCP**, silently.
- A bug in this project must never stop a player from joining over TCP. When in doubt, fall back.

Project name: **QUICraft**. License: **GPL-3.0-or-later**. Both decided by the user.

## Background research

The design is based on `docs/research.md` ("QUIC for Minecraft Java Edition — Research & Feasibility", also at https://claude.ai/code/artifact/377ffc95-ec47-4005-9335-d40ab79b16f4). Treat it as background, not as a spec. Where it and this file disagree, this file wins. Known disagreement: the research suggests forking prior art; this project starts from scratch. Key facts from it:

- Minecraft 26.1+ requires Java 25, ships unobfuscated, and bundles Netty 4.2.7. Netty's QUIC codec has been part of Netty core since 4.2.1, but Minecraft does not bundle it.
- Every packet currently rides one ordered TCP stream. Game logic depends on that order (entity spawn before move, chunk before block update, bundle packets).
- QUIC wins on lossy, mobile and long-RTT links and on network changes. On a clean wired link the gain is near zero. A single QUIC stream still has head-of-line blocking, so most of the gain needs multi-stream. Never claim numbers you haven't measured.
- UDP is blocked on some networks, so TCP fallback is mandatory, not optional.

## Start from scratch

- **Do not fork, copy from, vendor, or depend on any existing Minecraft networking project.** That includes QUICify, QUIC-MC, QuicProtocolSupport, NetBridge and Raknetify. Don't read their source either, so nothing gets copied by accident. They appear in the research only as prior art.
- Allowed dependencies: Netty (including `netty-codec-classes-quic` and `netty-codec-native-quic`), the official loader/platform toolchains (Fabric Loom, NeoForge/ForgeGradle, Velocity API, Paper API), and ordinary libraries (logging, config, testing). **Exception, approved by the user:** MCProtocolLib (MIT) is allowed in `testkit/` only, for the headless test client. It never ships in a release jar. Every dependency must also pass the Licensing rules.
- Write all Minecraft integration (mixins, hooks, plugin code) yourself against the game and platform sources.
- **Never commit decompiled or remapped Minecraft sources, or game assets.** They are Mojang's and cannot be GPL. Generate them locally (`genSources`) and keep them gitignored.

## Licensing

The project is GPL-3.0-or-later. **No AGPL code anywhere**: not as a dependency (direct or transitive), not in tests, not in testkit images or CI tooling. Examples to avoid: Grafana, Loki, Mimir, k6, MinIO (all AGPL-3.0). GPL-3.0 §13 lets *others* combine our code with AGPL; we never do it ourselves.

Dependency status (each license file checked Oct 2026; re-check on every version bump):

| Dependency | License | With GPL-3.0 |
| --- | --- | --- |
| Netty (incl. QUIC classes) | Apache-2.0 | OK (Apache-2.0 → GPL-3.0 is one-way compatible) |
| BoringSSL (statically linked in `netty-codec-native-quic`) | Apache-2.0 from commit `33d1049b` (2025-02-03). Before that: OpenSSL/SSLeay terms, which are **GPL-incompatible** (advertising clause) | OK only with a Netty whose pinned `boringsslCommitSha` is after the relicense. The current 4.2 branch pins a commit from after 2025-09-07. Check the pin in `codec-native-quic/pom.xml` (or the jar manifest's `BoringSSL-Revision`) for the exact Netty version we use, and never ship an older one |
| Cloudflare quiche (inside the native) | BSD-2-Clause | OK |
| Fabric Loader, Fabric API, Mixin | Apache-2.0 / MIT | OK |
| NeoForge | LGPL-2.1 | OK |
| Forge (1.20.1, 1.8.9) | LGPL-2.1 (not re-checked; check before those phases) | OK if confirmed |
| Velocity API | MIT | OK |
| Velocity proxy (we hook its internals) | GPL-3.0 | OK, and the reason GPL-3.0 fits this project |
| Paper (API and server internals) | GPL-3.0, some parts MIT | OK |
| Minecraft | Proprietary | Not a dependency we ship; see the linking exception below |
| MCProtocolLib (testkit only) | MIT | OK |

Rules:

- **Incompatible with what we ship:** AGPL (banned by the user), GPL-2.0-only, CDDL, EPL-1.0, SSPL, BUSL and other non-free or source-available licenses. Ask the user before adding any dependency whose license isn't in the table.
- **License check in CI.** A Gradle license report runs on every build and fails on AGPL or anything not on an allowlist.
- **Notices in shaded jars.** Shaded jars must keep every license and notice file shipped inside the Netty and `netty-codec-native-quic` jars (`META-INF/LICENSE.txt`, `META-INF/NOTICE.txt`, `META-INF/license/**`). The native statically links quiche's Rust dependencies too, so don't hand-pick a subset. Add a test that compares the set in the built jar against the set in the source jars.
- **Corresponding Source.** Every released jar maps to a public, tagged commit. The release notes record the exact Netty, netty-codec-native-quic, quiche and BoringSSL versions bundled.
- **Minecraft linking exception: deferred by the user.** No GPL-3.0 §7 additional permission for now. Don't add one, and don't raise it again unless the user does.
- **Dependency upgrades (Netty especially) re-run the license check.** A new native build can change bundled licenses.
- Every source file carries an SPDX header (`SPDX-License-Identifier: GPL-3.0-or-later`).

## Architecture

```
core/          Java 8. Transport, handshake, discovery, fallback. No Minecraft classes.
bridge-netty*/ Game-Netty Channel that wraps a core QUIC stream. One per Netty generation (4.0, 4.1, 4.2).
velocity/      Velocity plugin. Terminates QUIC at the proxy; backends stay TCP.
fabric-*/      Fabric client + dedicated-server adapters, one module per hook group.
neoforge-*/    NeoForge adapters.
paper/         Paper plugin for servers without a proxy (later phase).
testkit/       Docker harness, netem profiles, headless test client, benchmarks.
docs/          protocol.md (the spec), research.md, version-matrix.md, benchmarks.md.
```

Rules:

- `core` targets **Java 8** bytecode so every supported game version can load it. Netty 4.2's minimum is Java 8, so that works. Platform modules target whatever their game version needs (Java 8, 17, 21 or 25).
- **Shade and relocate Netty 4.2** into the project's own package so it never clashes with the game's Netty (4.0.23 on 1.8.9, 4.1.x on mid versions, 4.2.7 on 26.x).
- **Relocation means the game's pipeline cannot run on a QUIC channel directly.** A relocated `QuicStreamChannel` is a different `Channel` type from the game's, so game handlers can't be added to it. Bridge it instead: a `bridge-netty*` module implements a `Channel` in the *game's* Netty that reads from and writes to a core QUIC stream. The platform adapter installs the vanilla pipeline on that channel. Keep the bridge small and generic; QUIC logic stays in `core`. Velocity uses the bridge for its own Netty version.
- QUIC natives come prebuilt from Maven Central. Use `netty-codec-native-quic` classifiers `linux-x86_64`, `linux-aarch_64`, `osx-x86_64`, `osx-aarch_64` and `windows-x86_64`. Netty loads `netty_quiche42_<os>_<arch>` and expects the shaded package prefix on the library name. **Rename the native library files during shading** to match the relocated package prefix, or Netty's loader silently fails to find them. Add a test that loads the natives from the shaded jar, run in a CI matrix on every native platform.
- On a platform with no native (Windows ARM64, FreeBSD, possibly musl/Alpine), log once at INFO and run TCP-only. Never crash.
- Platform modules are thin adapters: hook the connection path, hand bytes to `core`. Keep logic in `core`.
- **Every hook fails safe.** Each mixin and plugin hook catches everything thrown by project code and continues down the vanilla TCP path.

## Discovery and connection protocol

Write the full spec in `docs/protocol.md` before implementing it, and keep it versioned. The shape:

1. **Advertisement.** The server adds a namespaced top-level object to its status (server-list ping) JSON response, e.g.
   ```json
   "quicraft:quic": { "v": 1, "port": 25565, "alpn": "quicraft/1", "fp": "sha256:<cert fingerprint>" }
   ```
   - Vanilla clients ignore unknown fields.
   - The status response has a string-length cap (believed to be 32767; confirm in each version's source). If adding the object would push the response over the cap, leave it out, or vanilla clients would fail to ping.
   - Velocity's `ServerPing` API has no slot for custom top-level fields, so the plugin must hook ping serialization through internals. Test it against every supported Velocity release.
2. **Port.** Default QUIC port = the same number as the TCP port, on UDP. This collides with the query protocol when it is enabled: vanilla `query.port` defaults to 25565 and Velocity's `[query] port` to 25577, both equal to the default game ports. `protocol.md` must specify the behaviour: either demultiplex the shared socket by first bytes (query packets start `FE FD`), or detect the bind failure, use a configured alternative port and advertise that. Never fail startup over it.
3. **Address.** QUIC goes to the host the TCP connection resolved to, including SRV results, and the advertised port. TCP-only frontends (TCPShield, playit.gg, Cloudflare Spectrum and the like) relay the ping, advertisement included, but drop UDP. The failure cache below has to make that case cheap.
4. **Decision on connect.** If the client has a fresh advertisement for that address (cached from the server list, short TTL), it connects over QUIC. With no cached advertisement (e.g. direct connect), it does a status query first. If there is no advertisement, it uses TCP.
5. **Fallback, by racing.** Start QUIC. If the QUIC handshake hasn't finished after a short head start (value to be measured in the testkit and recorded in `protocol.md`), open TCP in parallel and use whichever completes first. Send the Minecraft handshake only on the winner. A handshake failure starts TCP immediately. The player never waits for a full QUIC timeout.
6. **Failure cache.** Remember QUIC failures per (address, fingerprint) with exponential backoff, persisted across restarts, so a UDP-blocked or frontend-proxied server costs at most one head start per backoff window. After a fallback, the client tells the server over TCP (plugin message) that QUIC failed, and the server logs a rate-limited hint that its UDP port may be unreachable.
7. **Trust.** The server generates a self-signed certificate on first run and advertises its fingerprint. Until Phase 9, the client only checks the QUIC certificate against the advertised fingerprint and falls back to TCP on mismatch. There is no trust prompt. Reason: Minecraft's own encryption still protects the session, and an attacker can strip the advertisement and force silent TCP anyway, so a TOFU warning would add scary UI without real protection. The client records fingerprints silently so pinning (TOFU, warn on change) can be turned on once QUIC TLS carries the session's security.
8. **Encryption.** In early phases keep Minecraft's own login encryption running inside QUIC, so login security stays exactly as vanilla. Multi-stream needs this changed, because AES/CFB8 runs as one continuous cipher over the whole framed stream (see Phase 5).
9. **Transport parameters.** `protocol.md` fixes these, with reasons:
   - The QUIC max idle timeout is longer than Minecraft's keepalive timeout (~30 s).
   - Explicit initial flow-control limits (`initialMaxData`, per-stream limits, max streams). With quiche's defaults, streams can't send at all.
   - The congestion-control algorithm.
10. User settings: `auto` (default: QUIC if advertised, else TCP), `tcp-only`, `quic-only` (debug).

## Supported versions

Exact game version ranges, loaders and Java targets per module live in `docs/version-matrix.md`. Keep it current.

- **Every 26.x release** (26.1, 26.1.1, 26.1.2, 26.2, 26.3, and each new one as it ships).
- **Every 1.21.x release** (1.21 through 1.21.11).
- **1.21.11 shares its Fabric hook group with 26.x**, separate from 1.21–1.21.10, per the user. This can only mean **shared source, separate jars**. 1.21.11 is the last obfuscated release, so Fabric runs it through Intermediary names. 26.1+ is unobfuscated and needs no remapping. They also need different Java targets (21 vs 25). Build both from one source set. If the mixin targets differ, record the actual split in the version matrix rather than forcing it.
- Group the other 1.21.x versions into as few adapter modules as their networking code allows. Decide the grouping by diffing the relevant classes, not by guessing.
- Later: 1.20.1 (Forge/Fabric) and 1.8.9 (Forge).
- Velocity covers every client version it accepts (1.7.2+) on the server side, as long as the transport is single-stream. Multi-stream at the proxy needs per-version packet routing tables. The client side still needs the mod for each version.

## Phases

Finish each phase's gate before starting the next. Report gate results to the user with numbers.

0. **Groundwork.** Gradle multi-project, README, LICENSE (GPL-3.0-or-later), license check, CI, `docs/` skeleton. Docker testkit: Velocity + Paper backend, `tc netem` profiles (clean, loss, delay, reorder), headless test client. Record TCP baseline metrics: join time, chunk-load time, movement round-trip, disconnects.
   - **Headless client on MCProtocolLib** (approved exception, testkit only). It covers handshake, login, configuration and play; QUIC support comes later through `core` and the bridge. Each MCProtocolLib release targets one protocol version, so pin one release per tested game version and record the mapping in `docs/version-matrix.md`.
   - **Test online-mode login too.** Offline mode never turns on Minecraft's encryption. Run a mock session server in the testkit: Velocity takes `-Dmojang.sessionserver=<full hasJoined URL>`, and vanilla/Paper take `-Dminecraft.api.session.host` (Paper also needs `minecraft.api.services.host`). Confirm per version.

   *Gate:* baseline numbers are reproducible. Each metric's median across N runs stays within a stated tolerance; pick N and the tolerance in Phase 0 and record them in `docs/benchmarks.md`.
1. **Core.** QUIC client and listener, `bridge-netty*` modules, advertisement encode/decode, racing and fallback, failure cache, fingerprint check, natives loading. Write `docs/protocol.md` v1.
   *Gate:* loopback tests pass, including fallback when UDP is dropped, when the handshake fails, and when the fingerprint doesn't match.
2. **Velocity plugin (single stream).** UDP listener on the TCP port number (with the query-port handling from the protocol), QUIC connections fed into Velocity's normal pipeline through the bridge, advertisement in Velocity's ping response. The TCP listener stays untouched. QUIC connections expose a normal `InetSocketAddress` as the remote address, so IP bans, forwarding and geo plugins keep working.
   *Gate:* test clients join over QUIC; unmodded clients still join over TCP; ping still works with a maximum-size MOTD and favicon.
3. **Fabric client and dedicated server for the 26.x + 1.21.11 hook group.** Read the advertisement, decide QUIC or TCP, settings screen, debug info showing the active transport. The server side advertises and listens on Fabric dedicated servers without a proxy.
   *Gate:* parity with TCP on a clean link, no desync in a full play session, fault-injection pass (`core` forced to throw at every hook, TCP join still succeeds), and loss/delay numbers measured and reported. The gate does **not** require single-stream to beat TCP under loss; it requires no regression.
4. **Public alpha.** Release the Velocity plugin and the 26.x + 1.21.11 Fabric builds. Before release:
   - A real Retry token handler. Never ship `InsecureQuicTokenHandler` from Netty's examples.
   - Per-IP and global limits on connection attempts and open connections.
   - Server-owner docs for the UDP firewall rule and DDoS caveats.
5. **Multi-stream on 26.x.** Packets are categorized onto streams with explicit ordering rules: state changes and object create/destroy on one ordered control stream, plus sequence barriers wherever a dependency crosses streams. The routing table is kept as data.
   - **Prerequisite:** Minecraft's AES/CFB8 can't be split across streams. Choose either per-stream ciphers derived from the login shared secret, or login bound to the QUIC TLS session (TLS exporter). Either option is a security design: draft it, then get the user's sign-off and a security review before release.
   - Offline-mode servers have no Minecraft encryption, so they can be tested first.

   *Gate:* measured improvement under loss over both TCP and single-stream, no desync in full play sessions under every netem profile, and security review done.
6. **Version spread.** Each port includes the single-stream adapter and, where multi-stream is enabled, that version's routing table. In order:
   - Fabric client and server for every 1.21–1.21.10 version.
   - NeoForge client and server for every 26.x and 1.21.x version NeoForge supports.
   - New 26.x releases supported soon after they ship.
   - Then 1.20.1 Forge/Fabric, then 1.8.9 Forge.

   *Gate per version:* the same join, play, fault-injection and fallback tests in CI.
7. **Velocity multi-stream.** Per-version packet routing tables at the proxy.
8. **Paper plugin** for single servers without a proxy. Paper has no API for extra listeners, so isolate the internals it hooks and test against every supported Paper build.
9. **Performance and hardening.** Connection migration (verify what Netty's QUIC codec exposes first), TOFU pinning with change warnings, congestion-control tuning, proxy CPU cost per player, and a full security review.

## Working rules

- Tests are the gate. Every behaviour above, especially fallback and "TCP unaffected", needs an automated test. Include fault injection: with project code forced to throw, vanilla TCP joins and pings must still work.
- Measure before claiming. Benchmarks go in `testkit/` with results committed to `docs/benchmarks.md`.
- Keep `README.md` current: what it is, install for players, install for server owners (including the UDP port), supported versions, how fallback works, license.
- Small commits with clear messages. Don't push, publish releases or upload to Modrinth/CurseForge without the user's go-ahead.
- Ask the user before any decision that's hard to reverse: name, license details, adding a dependency with an unlisted license, protocol format once released, dropping a version.
