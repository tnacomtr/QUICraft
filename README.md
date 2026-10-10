# QUICraft

QUIC transport for Minecraft: Java Edition. QUICraft is a client mod plus server and proxy
plugins. When both sides have it, the game connection runs over QUIC (UDP). In every other case
it runs over plain TCP, exactly like vanilla.

> **Status: early development (Phase 1, core transport).** Nothing is released yet and nothing in
> this repository can be installed in a game yet. See [Roadmap](#roadmap).

## What it does, and what it doesn't

- **Adds QUIC; never takes TCP away.** The vanilla TCP listener stays as it is. Vanilla clients
  and clients without the mod join over TCP with no difference at all.
- **Automatic.** A server with QUICraft advertises QUIC support in its server-list ping. A client
  with QUICraft that sees the advertisement uses QUIC; otherwise it uses TCP.
- **Falls back silently.** If UDP is blocked, the QUIC handshake fails, or anything else goes
  wrong, the client uses TCP. A QUICraft bug must never stop a player from joining.
- **Where it helps:** lossy Wi-Fi, mobile links, long-distance connections and network changes.
  On a clean wired connection the difference is close to zero. Benchmarks will be published in
  [`docs/benchmarks.md`](docs/benchmarks.md) as they are measured. No numbers are claimed before
  that.

## How fallback works (planned)

1. The server adds a `quicraft:quic` object to its status response with the QUIC port, protocol
   version and certificate fingerprint. Vanilla clients ignore it.
2. The client starts a QUIC handshake. If it hasn't finished after a short head start, the client
   opens TCP in parallel and keeps whichever connects first. A failed handshake starts TCP at once.
3. Failures are remembered per server with exponential backoff, so a server whose UDP port is
   unreachable costs at most one short head start now and then.

The exact protocol will be specified in [`docs/protocol.md`](docs/protocol.md) before it is
implemented.

## Installing

Not available yet. When it is:

- **Players:** install the client mod for your loader and game version. Nothing to configure;
  a settings screen offers `auto` (default), `tcp-only` and `quic-only` (debugging).
- **Server owners:** install the Velocity plugin (or the server mod) and **allow UDP on the same
  port number as your TCP game port** (default 25565/udp) in your firewall. If players connect
  through a TCP-only frontend (TCPShield, playit.gg, Cloudflare Spectrum and similar), QUIC can't
  reach you and clients use TCP automatically.

## Supported versions (planned)

- Minecraft **26.x** (every release) and **1.21–1.21.11**: Fabric first, then NeoForge.
- **Velocity** proxy: server side for every client version Velocity accepts.
- Later: Paper (servers without a proxy), 1.20.1 (Forge/Fabric), 1.8.9 (Forge).

Exact version ranges per module: [`docs/version-matrix.md`](docs/version-matrix.md).

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Build, license checks, CI, Docker testkit, TCP baseline | done ([baseline](docs/benchmarks.md)) |
| 1 | `core`: QUIC client/listener, discovery, racing, fallback, fingerprint check | in progress |
| 2 | Velocity plugin (single stream) | |
| 3 | Fabric client + dedicated server for 26.x and 1.21.11 | |
| 4 | Public alpha | |
| 5 | Multi-stream on 26.x | |
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

The Docker testkit (needs Docker and the `sch_netem` and `ifb` kernel modules on the host) is
described in [`testkit/README.md`](testkit/README.md).

## License

QUICraft is free software under the **GNU General Public License v3.0 or later**
([`LICENSE`](LICENSE)). Release jars bundle Netty and its QUIC native library (quiche and
BoringSSL), which are under Apache-2.0 and BSD-2-Clause; their license and notice files ship
inside the jars. QUICraft's build of the native also carries the license files of every Rust crate
it links, under `META-INF/license/quiche-deps/`.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
