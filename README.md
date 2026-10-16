# QUICraft

QUIC transport for Minecraft: Java Edition. QUICraft is a client mod plus server and proxy
plugins. When both sides have it, the game connection runs over QUIC (UDP). In every other case
it runs over plain TCP, exactly like vanilla.

> **Status: public alpha (0.1.0-alpha.2).** The Velocity plugin and the Fabric client and server mod
> for 26.x and 1.21.11 are released for testing on [GitHub](https://github.com/tnacomtr/QUICraft/releases)
> and [git.sudoe.rs](https://git.sudoe.rs/tna/QUICraft/releases). Expect rough edges; TCP keeps working
> whatever happens. See [Roadmap](#roadmap).

## What it does, and what it doesn't

- **Adds QUIC; never takes TCP away.** The vanilla TCP listener stays as it is. Vanilla clients
  and clients without the mod join over TCP with no difference at all.
- **Automatic.** A server with QUICraft advertises QUIC support in its server-list ping. A client
  with QUICraft that sees the advertisement uses QUIC; otherwise it uses TCP.
- **Falls back silently.** If UDP is blocked, the QUIC handshake fails, or anything else goes
  wrong, the client uses TCP. A QUICraft bug must never stop a player from joining.
- **Faster rejoins.** Rejoining a server in the same game session sends the first login packets
  with the QUIC handshake (0-RTT), so the server answers a round trip sooner: about 150 ms less
  per rejoin at 150 ms of latency ([`docs/benchmarks.md`](docs/benchmarks.md)). The first join
  after starting the game is a normal one.
- **Where it helps:** lossy Wi-Fi, mobile links, long-distance connections and network changes.
  On a clean wired connection the difference is close to zero. Benchmarks will be published in
  [`docs/benchmarks.md`](docs/benchmarks.md) as they are measured. No numbers are claimed before
  that.

## How fallback works

1. The server adds a `quicraft:quic` object to its status response with the QUIC port, protocol
   version and certificate fingerprint. Vanilla clients ignore it. The client reads it from the
   server list ping, or asks for the status itself when you connect directly.
2. The client starts a QUIC handshake. If it hasn't finished after a 250 ms head start, the client
   opens TCP in parallel and keeps whichever connects first. A failed handshake starts TCP at once.
3. Failures are remembered per server with exponential backoff (1 minute, doubling, up to 1 hour),
   so a server whose UDP port is unreachable costs at most one short head start now and then. The
   client also tells the server, which logs a hint about its UDP port.

The protocol is specified in [`docs/protocol.md`](docs/protocol.md).

## Installing

Download the jars from the [releases](https://github.com/tnacomtr/QUICraft/releases) page (the same
files are on [git.sudoe.rs](https://git.sudoe.rs/tna/QUICraft/releases)).

- **Players:** install the client mod for your loader and game version. Nothing to configure.
  The multiplayer screen has a **QUICraft** button (top right) with the transport setting:
  `Auto` (default), `TCP only` and `QUIC only` (debugging), and a button to forget remembered
  QUIC failures. F3 shows `QUICraft: QUIC` or `TCP` under the server line.
- **Server owners:** install the Velocity plugin (or the server mod) and **allow UDP on the same
  port number as your TCP game port** (default 25565/udp) in your firewall. If players connect
  through a TCP-only frontend (TCPShield, playit.gg, Cloudflare Spectrum and similar), QUIC can't
  reach you and clients use TCP automatically.

  On Linux a server spreads QUIC over several UDP sockets with SO_REUSEPORT, one per game event
  loop, because one loop is the bottleneck at the scale target (`udp-sockets` in
  `plugins/quicraft/config.properties` or `config/quicraft/server.properties`: `auto` by default,
  an integer 1..64 to pin it; see [server security](docs/server-security.md)). The startup line
  names the socket count. Nothing to do for it in your firewall: they share the QUIC port.

  Velocity details (Velocity 4.2, Java 25): drop the jar into `plugins/`. On first start it
  writes `plugins/quicraft/config.properties` and a self-signed certificate
  (`quic-key.pem`, `quic-cert.pem`; keep them, since clients remember the fingerprint). If
  `[query]` is enabled on the game port, QUIC moves to the game port + 1 (configurable). Velocity
  logs one expected WARN, "The server channel initializer has been replaced by
  rs.sudoe.quicraft.velocity...": that is how the plugin adds the advertisement to the ping.

  Fabric dedicated server details: the same mod jar as the client (`quicraft-fabric-26x` for
  26.1–26.3, `quicraft-fabric-1.21.11` for 1.21.11) goes into `mods/`; Fabric API is not needed.
  On first start it writes `config/quicraft/server.properties` and the certificate
  (`config/quicraft/quic-key.pem`, `quic-cert.pem`). With `enable-query` on the game port, QUIC
  moves to the game port + 1 (configurable).

Before opening the UDP port, read [UDP firewall and DDoS caveats](docs/server-security.md), which
also lists the admission limits and their defaults.

### Server updater (Velocity and Fabric dedicated servers)

The server side checks for new QUICraft releases. **What it sends:** once an hour by default, the
server asks `github.com` and `git.sudoe.rs` for the signed release list. Those hosts see the server's
IP address, and the request reveals the running QUICraft version and platform. Nothing else is sent.
The server says this once in its log on first start.

- **Turn checks off:** `update.check-interval=off` in `plugins/quicraft/updater.properties`
  (Velocity) or `config/quicraft/updater.properties` (Fabric). `daily` is also accepted.
- **Nothing is downloaded on its own.** An admin runs `quicraft update check`, then
  `quicraft update` (console, or `/quicraft update` in game: Velocity needs the
  `quicraft.admin.update` permission, Fabric accepts the server's OPs).
- Every release list and jar is checked against Ed25519 signatures built into the jar before it is
  used; an unsigned, tampered, older or revoked release is refused.
- **Linux and macOS:** the new jar replaces the old one in place (atomically, with a backup) and
  takes effect on the next restart. **Windows and other systems**, or `update.install=stage-only`:
  the verified jar is only staged, and the log says where; copy it over the old jar while the
  server is stopped.
- Manual rollback: stop the server, put the backed-up jar (named in the log) back, start.

Details: [`docs/updater.md`](docs/updater.md).

## Supported versions

- **Fabric**, client and dedicated server: Minecraft **26.1–26.3** and **1.21.11** (tested on each).
- **Velocity** 4.2 proxy: server side for every client version Velocity accepts.
- Planned: 1.21–1.21.10 (Fabric), NeoForge, Paper (servers without a proxy), 1.20.1
  (Forge/Fabric), 1.8.9 (Forge).

Exact version ranges per module: [`docs/version-matrix.md`](docs/version-matrix.md).

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Build, license checks, CI, Docker testkit, TCP baseline | done ([baseline](docs/benchmarks.md)) |
| 1 | `core`: QUIC client/listener, discovery, racing, fallback, fingerprint check | done (loopback gate) |
| 2 | Velocity plugin (single stream) | done ([gate](docs/benchmarks.md)) |
| 3 | Fabric client + dedicated server for 26.x and 1.21.11 | done ([gate and gaps](docs/benchmarks.md)) |
| 4 | Public alpha: Retry, admission limits, multi-socket listener, server-owner docs, signed server updater | done (0.1.0-alpha.1) |
| 5 | Multi-stream on 26.x | in progress; the 2026-10-16 direction (large packets first, chunk streams, server profiles) is planned, not implemented ([protocol §13](docs/protocol.md)) |
| 6–9 | More versions and loaders, Velocity multi-stream, Paper, hardening | |

## Building

Requires JDK 25 and Docker.

```sh
natives/build-linux.sh   # once (and after natives/ changes): patched Netty QUIC jars, ~15 min cold
./gradlew build          # compile, test, license check, SPDX header check
```

`core` uses QUICraft's own build of Netty's QUIC classes and native
([`natives/`](natives/), docs/protocol.md §11). It is upstream Netty 4.2.19 with two small
patches: quiche's relaxed loss threshold exposed, and a client connect that completes one round
trip sooner. The build script runs Netty's own Maven build in Docker. Its output stays in
`natives/build/` and is never committed. Linux x86_64 uses the patched native. The other platforms
use upstream Netty's native for now, where the relaxed loss threshold is unavailable.

Fabric client gametests (a real client and server, headless) need Xvfb:
`xvfb-run -a ./gradlew :fabric-26x:runClientGameTest :fabric-1.21.11:runClientGameTest`.

The Docker testkit (needs Docker and the `sch_netem` and `ifb` kernel modules on the host) is
described in [`testkit/README.md`](testkit/README.md).

## License

QUICraft is free software under the **GNU General Public License v3.0 or later**
([`LICENSE`](LICENSE)). Release jars bundle Netty and its QUIC native library (quiche and
BoringSSL), which are under Apache-2.0 and BSD-2-Clause; their license and notice files ship
inside the jars. QUICraft's build of the native also carries the license files of every Rust crate
it links, under `META-INF/license/quiche-deps/`.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
