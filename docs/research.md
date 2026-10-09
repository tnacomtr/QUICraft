# QUIC for Minecraft Java Edition — Research & Feasibility

Oct 9, 2026 · @Osman TANRIYEDI

## Verdict

QUIC for Minecraft Java is very feasible as a Fabric mod plus a Velocity/Fabric server component, and Claude Code can carry most of the build — but swapping TCP for one QUIC stream buys little; the value is in a multi-stream design that keeps chunk bursts from delaying movement and combat.

- **Doable now:** Minecraft 26.x already ships Netty 4.2.7, and Netty's QUIC codec graduated into Netty core in 4.2.1. You only add the QUIC codec jars and their native (quiche/BoringSSL) libraries.
- **Not greenfield:** at least five mods already do QUIC or UDP transport (QUICify, QUIC-MC, QuicProtocolSupport, NetBridge, Raknetify). Contributing to or forking one is cheaper than starting over.
- **Where gains are real:** lossy Wi-Fi, mobile hotspots, long-RTT links, IP changes mid-session. On a clean wired link the difference is close to zero.
- **Where it gets hard:** packet-ordering dependencies across streams, binding Mojang's online-mode auth to the QUIC TLS session, UDP being blocked on some networks, and DDoS protection that assumes TCP.
- **Mojang shipping it in vanilla** is out of anyone's control; plan for a mod that both sides opt into, with automatic TCP fallback.

## How Minecraft networking works today

Every packet in a session travels down one ordered TCP byte stream through a Netty pipeline, so one lost segment stalls everything behind it.

The pipeline in both client and server (the `Connection` class and its channel initializers) is roughly:

1. **Frame splitter / prepender** — VarInt length prefix per packet.
2. **Cipher** — AES/CFB8, switched on after the online-mode login handshake; the shared secret is folded into the hash the server sends to Mojang's session server.
3. **Compression** — zlib above a configurable threshold.
4. **Packet codec** — per protocol state (handshake → status/login → configuration → play).
5. **Packet listener** — handed to the game thread.

Facts that matter for this project:

- **Minecraft 26.1 is the first release requiring Java 25**, and it is the first shipped without obfuscation, which makes mixins far easier to keep working across versions.
- **26.1 bundles Netty 4.2.7** (buffer, codec, handler, transport, epoll/kqueue). The QUIC codec is not among the bundled jars, so a mod must ship `netty-codec-classes-quic` and `netty-codec-native-quic`.
- **The game logic assumes strict order.** An entity must be spawned before it moves; a chunk must arrive before block updates inside it; bundle packets group updates that must apply in the same tick. Any design that splits packets across independent streams has to respect these.

## Prior art

QUICify is the closest to the ideal design (auto-discovery, five prioritized streams, BBR); Raknetify proves the multi-channel idea on UDP at scale with roughly 600k downloads.

| Project | Transport | Server side | Discovery | Streams | Notes |
| --- | --- | --- | --- | --- | --- |
| [QUICify](https://www.curseforge.com/minecraft/mc-mods/quicify) | QUIC via Netty + quiche, TLS 1.3 | Fabric | Advertised in status ping | 5 by category (control, real-time, UI, ambient, world) | BBR, zstd; falls back to TCP on unsupported platforms; Java 25 |
| [QUIC-MC](https://modrinth.com/mod/quic-mc) | QUIC tunnel | Velocity plugin | Ping; ⚡ icon in server list | 1 (local TCP↔QUIC tunnel) | Trust-on-first-use identity code + access key; Java 21 |
| [QuicProtocolSupport](https://modrinth.com/project/BcxaHU2T) | QUIC via Netty | Fabric + Velocity | `quic://` address prefix | 1 | Needs a CA-signed cert; MC 1.20.1 only; LGPL-3.0 |
| [NetBridge](https://github.com/PCL-Community/NetBridge) | Plaintext QUIC or KCP+FEC (Rust via JNI) | Fabric | Ping `networks` object | 1 | Disables QUIC encryption; TCP fallback after 2 failures; alpha |
| [Raknetify](https://metamods.net/en/mods/raknetify) | RakNet (UDP) | Fabric, Velocity, BungeeCord | Address-based | Multiple prioritized channels | Most mature; MC 1.17–1.21.11 |

For the native-dependency question: [Kwik](https://github.com/ptrd/kwik) is a 100% Java QUIC stack (LGPL-3.0), but its server lacks connection migration and it is blocking per connection. JDK 26's new HTTP/3 support explicitly does not expose a QUIC API ([JEP 517](https://openjdk.org/jeps/517)), so it cannot be used here.

## Integration approaches

The recommended path is a channel-level swap (B) shipped first with one stream, then upgraded to categorized streams (C); terminate QUIC at the Velocity proxy and keep TCP on the LAN behind it.

| Approach | How | Code touched | Head-of-line fix | Effort |
| --- | --- | --- | --- | --- |
| A. Tunnel | Client mod runs a local TCP listener and forwards over QUIC; server side unwraps to TCP | Almost none in-game | No | Low |
| B. Channel swap, 1 stream | Mixin into `Connection` / the server listener to use a `QuicStreamChannel` instead of a TCP channel; rest of pipeline unchanged | Connection setup only | No (still one ordered stream) | Low–medium |
| C. Categorized streams | Route each packet type to one of N streams with priorities; one control stream keeps state changes ordered | Encoder/decoder + routing table | Yes | High |
| D. Datagrams | Send movement/keepalive as unreliable QUIC datagrams (RFC 9221), latest-wins | Specific packet handlers | Yes, for those packets | High, risky |

**Where each side lives:**

- **Client:** a Fabric mod (NeoForge port later). Mixin points: the multiplayer connect path, the server-list ping (to read the QUIC advertisement), and channel creation in `Connection`.
- **Proxy:** a Velocity plugin is the highest-leverage target. One install covers a whole network, and backends stay vanilla TCP on a low-loss LAN where QUIC adds nothing. Velocity 4 already requires Java 25.
- **Single server:** a Fabric server mod hooking the dedicated server's listener. Paper has no API for extra listeners, so a Paper version would need channel injection or a fork patch.
- **Vanilla:** out of scope; Mojang would have to adopt it.

For A–C the outer pipeline (frame, compression, codec) stays as is. B's main win over A is no extra local hop and access to QUIC connection events (migration, path changes).

## Hard problems

The transport is the easy part; the real design work is stream ordering and how trust is established, and both are where a bug becomes a desync or a security hole.

### 1. Ordering across streams

- QUIC only orders bytes **within** a stream. Splitting packets across streams breaks cross-packet dependencies (entity spawn → move, chunk → block update, bundle start → bundle end).
- Practical rule: everything that changes protocol state or creates/destroys an object goes on a single ordered control stream. Only independent, self-contained traffic (chunk data for unrelated chunks, particles, sounds, tab list, chat) moves to other streams.
- Where a dependency crosses streams, add a sequence barrier: the receiver holds a packet until the packet it depends on has been applied.
- Build the routing table from the decompiled packet list and keep it as data, so a new Minecraft version means reviewing a diff, not rewriting code.

### 2. Encryption and authentication

- QUIC always runs TLS 1.3. Keeping Minecraft's AES/CFB8 on top doubles encryption for no gain.
- But Minecraft's own key exchange is what binds the session to Mojang's auth: the shared secret goes into the hash the client sends to the session server, which blocks man-in-the-middle relays. Turning it off without a replacement removes that protection.
- Safer option: keep Minecraft's login handshake, then replace its secret with a value derived from the QUIC TLS session (a TLS exporter) so the session-server hash still pins this exact connection.
- Certificates: most servers will not have a CA cert. Use self-signed certs with trust-on-first-use (QUIC-MC's identity-code approach), or publish the cert fingerprint in the status ping and pin it.
- NetBridge's plaintext QUIC sidesteps TLS but is non-standard and loses QUIC's header protection.

### 3. Discovery and fallback

- Advertise QUIC in the status (ping) response with its UDP port and a version string, as QUICify and NetBridge do. No address prefix is needed.
- Race QUIC against TCP on first connect and keep the winner per server for a while, the same pattern JDK 26's HttpClient uses for HTTP/3.
- Fallback is mandatory: UDP is blocked on some school, corporate and ISP networks, and Hytale, which uses QUIC as its primary transport, has had players hit network-driver bugs with it.

### 4. Operations

- **DDoS:** Minecraft protection services and firewall rules are largely built around TCP. UDP floods are cheaper to send; use QUIC Retry tokens and per-IP rate limits, and check your provider's UDP filtering.
- **Native libs:** quiche/BoringSSL ship for Windows, Linux and macOS (x86-64, ARM64). FreeBSD and some ARM devices are not covered; the mod must detect that and stay on TCP.

## Benefits and costs

The benefits concentrate on bad networks and need multi-stream to fully appear; the costs are mostly operational and land on server owners.

| Benefit | Needs | Who notices |
| --- | --- | --- |
| No head-of-line blocking: a lost chunk packet no longer delays movement, combat or chat | Categorized streams (C) | Players on lossy Wi-Fi, mobile, satellite |
| Faster loss recovery: no ambiguous retransmits, richer ACKs | Any QUIC mode | Same, plus long-RTT players |
| Connection migration: survive a Wi-Fi → cellular switch or NAT rebinding without a disconnect | Any QUIC mode (server must support migration) | Laptop and phone-hotspot players |
| Shorter setup: transport + crypto in 1 RTT, 0-RTT on resume | Any QUIC mode | Everyone, small effect |
| Userspace congestion control, e.g. BBR, suited to the login burst then 20 Hz small packets | Any QUIC mode | Players on congested links |
| Stream priorities: player-facing packets first during chunk loading | Categorized streams (C) | Everyone joining or flying fast |

| Cost | Size | Mitigation |
| --- | --- | --- |
| Higher CPU per byte than TCP | Studies find QUIC stacks slower and more CPU-hungry than TCP+TLS, especially at gigabit rates ([Zhang et al.](https://arxiv.org/pdf/2310.09423), [Koenig et al. 2025](https://networking.ifip.org/2025/images/Net25_papers/1571125566.pdf)) | Minecraft's per-player rate is low, so this mainly matters on a busy proxy; measure it |
| UDP blocked on some networks | Unknown share of players | Race and fall back to TCP |
| Weaker DDoS tooling for UDP | Depends on host | Retry tokens, rate limits, UDP-aware protection |
| Native libraries | \~3 OS × 2 arch builds to ship | Bundle quiche via Netty; stay on TCP elsewhere |
| Mod on both sides | Adoption limited to opted-in servers | Velocity plugin covers whole networks |

No published measurements exist yet for Minecraft-over-QUIC specifically. Hosting guides for Hytale claim around 30% lower latency, but that figure is unsourced; treat any number as something to measure yourself.

## Feasibility with Claude Code

Claude Code can write most of this project, because it is mostly Java, Netty and mixin code against now-unobfuscated sources; what it cannot do alone is judge gameplay feel, run long real-network soak tests, or sign off on the auth design.

| Work | Claude Code fit | Why |
| --- | --- | --- |
| Fabric/Loom + Velocity project scaffolding, Gradle, CI | Strong | Well-documented, repetitive |
| Mixins into `Connection`, server listener, ping | Strong | 26.x sources are readable after `genSources`; it can grep and patch |
| Netty QUIC channel setup, codec reuse, fallback race | Strong | Netty QUIC has examples; code is self-contained |
| Packet → stream routing table | Good, needs review | It can list every packet from source; a human must confirm the ordering rules |
| Headless test bot + `tc netem` loss/delay/reorder harness | Good | Scriptable in Docker (needs `NET_ADMIN`) |
| Auth binding via TLS exporter, cert pinning | Draft only | Security design needs a human threat-model review |
| Judging whether it *feels* better in-game | Weak | Needs real play on real networks |
| Keeping up with each Minecraft release | Good per session | Each update is a fresh mixin-repair session |

**Rough effort**, part-time with Claude Code doing most typing (an estimate, not measured): phases 0–1 about 1–2 weeks; phase 2 about 1 week; phase 3, multi-stream with correct ordering, about 3–6 weeks; hardening is ongoing.

**To make Claude Code effective:**

- Put decompiled Minecraft sources in the repo and keep a `CLAUDE.md` with the ordering invariants and the auth rules, so every session starts from them.
- Make tests the gate: a headless bot that joins, moves, loads chunks and asserts no desync, run under several netem profiles in CI.
- Check licenses before forking: QuicProtocolSupport is LGPL-3.0 and Kwik is LGPL-3.0; confirm QUICify's and QUIC-MC's before reusing code.
- Start from approach B so you have a working, measurable baseline before attempting C.

### Phased plan

&#91;embedded content: phased plan · 5 phases, 4 gates\]

Each gate is a measurement, not a date: multi-stream work starts only once single-stream QUIC matches TCP on a clean link.

## Sources

- [QUICify (CurseForge)](https://www.curseforge.com/minecraft/mc-mods/quicify)
- [QUIC-MC (Modrinth)](https://modrinth.com/mod/quic-mc)
- [QuicProtocolSupport (Modrinth)](https://modrinth.com/project/BcxaHU2T) · [architecture notes (DeepWiki)](https://deepwiki.com/winxpqq955/QuicProtocolSupport)
- [NetBridge (GitHub)](https://github.com/PCL-Community/NetBridge)
- [Raknetify (MetaMods)](https://metamods.net/en/mods/raknetify)
- [Netty 4.2.1 release: QUIC codec graduates to core](https://netty.io/news/2025/05/06/4-2-1.html)
- [Minecraft 26.1 library list showing Netty 4.2.7 (Controlify issue #825)](https://github.com/isXander/Controlify/issues/825)
- [Minecraft 26.1 server guide: Java 25, unobfuscated release](https://supercraft.host/wiki/minecraft/minecraft-tiny-takeover-26-1-server-guide)
- [Minecraft 26.x versions and Velocity 4 Java 25 requirement](https://www.gameserverkings.com/knowledge-base/minecraft/updating-your-server-version/)
- [JEP 517: HTTP/3 for the HTTP Client API](https://openjdk.org/jeps/517) · [inside.java on HTTP/3 fallback racing](https://inside.java/2025/10/30/quality-heads-up/)
- [Kwik, pure-Java QUIC](https://github.com/ptrd/kwik)
- [Hytale networking docs (QUIC primary, TCP fallback)](https://hytale-docs.pages.dev/modding/networking) · [Hytale QUIC driver issues](https://help.freakhosting.com/games/hytale/slow-connection-and-world-not-loading-fix)
- [QUIC is not Quick Enough over Fast Internet (Zhang et al.)](https://arxiv.org/pdf/2310.09423)
- [Performance Landscape of QUIC Implementations (Koenig et al., 2025)](https://networking.ifip.org/2025/images/Net25_papers/1571125566.pdf)
