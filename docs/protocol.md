# QUICraft protocol

**Version 1, draft.** Not released. Once a version ships, its wire format is frozen: changes
need a new version and the user's sign-off. Decisions marked *(user)* were made by the project
owner on 2026-10-09. Values marked **provisional** are placeholders until the named measurement
replaces them.

Terms: *client* is the game client with QUICraft. *Server* is the QUICraft endpoint the client
connects to: the Velocity proxy, or a Fabric/Paper server without a proxy. "MUST", "SHOULD"
and "MAY" are used as in RFC 2119.

## 0. Principles

- QUIC is added next to TCP. The TCP listener and the vanilla protocol are untouched.
- Any failure, anywhere, ends in a normal TCP connection. The player never sees an error caused
  by QUICraft and never waits for a full QUIC timeout.
- v1 carries exactly the bytes vanilla would send over TCP, on one QUIC stream. Framing,
  compression and Minecraft's login encryption are unchanged.

## 1. Advertisement

The server adds one top-level member to its status (server-list ping) JSON:

```json
"quicraft:quic": { "v": 1, "port": 25565, "alpn": "quicraft/1", "fp": "sha256:<64 hex>" }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `v` | integer | Protocol version. This document is `1`. |
| `port` | integer 1–65535 | UDP port of the QUIC listener (§2). |
| `alpn` | string | ALPN the server accepts. v1: `quicraft/1`. |
| `fp` | string | `sha256:` followed by the lowercase hex SHA-256 of the DER encoding of the server's leaf certificate (§7). |

Client rules:

- Use QUIC only if `v` is a version it implements, `alpn` is one it offers, `port` is in range
  and `fp` is well formed. Otherwise treat the server as not advertising QUIC.
- Ignore unknown members inside the object. Future versions may add fields; a v1 client MUST
  still work with them.
- The member's absence, or any parse error, means TCP.

Server rules:

- The status response is capped at 32767 UTF-16 code units (verified for 26.1.2 below). If adding
  the member would push the serialized response over the cap, the server MUST leave the member
  out. Vanilla clients would otherwise fail to ping the server. A v1 member is about 120
  characters.
- Velocity's `ServerPing` API has no slot for custom top-level members, so the plugin hooks ping
  serialization. Tested against every supported Velocity release.

Verified (Oct 2026), 26.1.2: `ClientboundStatusResponsePacket` reads and writes the status JSON
with `ByteBufCodecs.lenientJson(32767)`. `Utf8String.read`/`write` enforce at most 32767 UTF-16
code units (`String.length()`) and at most `ByteBufUtil.utf8MaxBytes(32767)` encoded bytes. The
writer checks too, so an oversized response fails on the sending side as well. Checked in Paper
26.1.2's server jar, and in the decompiled 1.21.11 and 26.1.2 game (client and server use the same
codec). Velocity's own serializer has no check (docs/platforms/velocity.md); the advertisement
stays under the cap there through `Advertisement.insertInto`. Still to check: 26.2 and 26.3
(their status codec wasn't read; the Fabric mod's classes are identical across 26.x, and the
gametests ping successfully).

## 2. Port

- Default QUIC port: the TCP game port number, on UDP.
- **Query collision** *(user: separate port)*. If the platform's query protocol is enabled on the
  same UDP port (vanilla `query.port` defaults to 25565, Velocity's `[query] port` to 25577,
  both equal to the default game ports), the server binds QUIC to a configured alternative port
  instead and advertises that port. Default alternative: game port + 1. Configurable. The
  server never intercepts query traffic.
- If binding fails for any reason, the server logs one WARN line, does not advertise QUIC, and
  keeps running TCP-only. Startup never fails because of QUIC.

## 3. Address

The client sends QUIC to the IP address its TCP connection would use, after SRV and DNS
resolution, and the advertised `port`. TCP-only frontends (TCPShield, playit.gg, Cloudflare
Spectrum and similar) relay the ping, advertisement included, but drop UDP. The failure cache
(§6) keeps that cheap.

## 4. Connect decision

| Client setting | Behaviour |
| --- | --- |
| `auto` (default) | QUIC if a fresh advertisement exists for the address and the failure cache allows it, else TCP. |
| `tcp-only` | Always TCP. |
| `quic-only` | QUIC with no TCP fallback (debugging). Failures are shown to the player. |

- An advertisement is **fresh** for **60 seconds** after it was received *(user)*. The server
  list's own pings refresh it.
- With no fresh advertisement (e.g. direct connect), the client sends a status query first,
  over TCP like vanilla, and decides from its response. The query is the game's own: handshake
  with intent "status", the host name and port the game would send (virtual hosts route on
  them) and the game's protocol version, then a status request. It is bounded by **5 seconds**;
  no answer, an error or a malformed response means TCP. Its advertisement is cached like one
  from the server list.
- Cache key: the resolved IP and the TCP port, without any host name.

## 5. Racing and fallback

1. The client starts the QUIC handshake.
2. If the handshake hasn't completed after the **head start H**, the client also opens TCP. The
   first transport to complete wins. For QUIC, "complete" means the handshake is done *and* the
   certificate fingerprint matched (§7). For TCP, it means the connection is established.
3. A QUIC failure before that point (handshake error, ALPN mismatch, fingerprint mismatch, ICMP
   unreachable, any exception) starts TCP immediately, without waiting for H.
4. The Minecraft handshake goes **only** over the winner. The loser is closed at once: QUIC with
   `CONNECTION_CLOSE` error `NO_ERROR`, TCP with a normal close.
5. A QUIC loss or failure is recorded in the failure cache (§6). Losing to TCP after the head
   start counts as a failure.

Known limit (Netty 4.2.19, verified in `QuicLoopbackTest`): when the server rejects the
handshake for lack of a common ALPN, no CONNECTION_CLOSE reaches the client. The error escapes
quiche's send path in Netty before the close packet is produced, so the client only fails at its
connect timeout (10 s). Step 2 still bounds the player's wait to H. A v1 client only offers an
ALPN the server advertised, so this needs a wrong advertisement to happen. A fingerprint
mismatch is detected on the client and fails at once (~20 ms on loopback).

Measured connect cost (QUICraft's Netty build, §11; `HandshakeFlightProbeTest`): a fresh QUIC
connect completes **1.02 RTT** after the client's first Initial (203–204 ms at 200 ms RTT), like
TCP connect. Upstream Netty 4.2.19 needed about **2 RTT**. Netty runs the certificate check as an
SSL task after `quiche_conn_recv`. quiche finishes the handshake only in the next send, and Netty
then didn't look again until the server's next packet arrived, one RTT later.
`natives/patches/netty/0002-complete-connect-after-handshake-send.patch` completes the connect
right after that send. In the transport benchmark at +150 ms RTT, QUIC handshake p50 went from
303 ms (run `transport-20261009T191726Z`) to 153 ms (run `transport-20261010T010019Z`). This
compares two runs; TCP connect was 150.5 and 150.4 ms in them. Session resumption (§8) takes the
certificate work off a rejoin but not the round trip.

**H = 250 ms, provisional.** Transport benchmark with the connect fix: QUIC handshake p50/p99
was 2.7/3.2 ms (clean), 153/155 ms (+150 ms RTT) and 23/24 ms (reorder). The earlier run measured
2.9/1005 ms under 2% loss; the tail is a lost first Initial. With H = 250 ms, QUIC completes well
inside the head start at +150 ms RTT, and a lost Initial costs at most H instead of ~1 s. Open
idea: derive H from the RTT the server-list ping already measured.

## 6. Failure cache

- Key: (resolved IP, QUIC port, advertised fingerprint).
- After a failure, the client skips QUIC for that key for the backoff period. It starts at
  **1 minute** and doubles with each consecutive failure, up to **1 hour** *(user)*. A successful
  QUIC connection removes the entry.
- Persisted across restarts in the client's config directory. A missing, unreadable or corrupt
  file is treated as empty.
- **Fallback report.** After falling back to TCP because of a QUIC failure, the client sends one
  plugin message on channel `quicraft:fallback` during configuration:

  | Field | Type | Values |
  | --- | --- | --- |
  | version | VarInt | `1` |
  | reason | VarInt | `1` handshake timeout or lost race, `2` handshake failure, `3` fingerprint mismatch, `4` UDP unreachable, `5` other |

  The server treats the payload as untrusted. It logs at most one rate-limited hint ("QUIC
  failed for some clients; check that UDP port N is reachable"). It never changes behaviour and
  never echoes the content. Unknown versions and reasons are ignored. Vanilla servers ignore the
  channel.

## 7. Trust

- On first start, the server generates an EC P-256 key pair and a self-signed X.509 v3
  certificate (signature `SHA256withECDSA`). It persists both in its config directory and reuses
  them. Deleting them rotates the fingerprint.
- The client checks the server's leaf certificate only against the advertised `fp`. It does not
  check names, dates or chains. Mismatch → QUIC fails → TCP (§5).
- No trust prompt before Phase 9. Minecraft's own encryption still protects the session, and an
  attacker who can strip the advertisement can force silent TCP anyway, so a warning would add
  scary UI without real protection.
- The client records fingerprints per server silently, so pinning can be turned on later: in
  online mode via channel binding (§12, Phase 5), in offline mode as TOFU (Phase 9).
- Implementation note: Netty's `SelfSignedCertificate` needs BouncyCastle (license not in the
  project's table), JDK-internal `sun.security.x509` classes (blocked on modern JDKs), or the
  `keytool` binary (often missing). QUICraft encodes its one certificate itself.

## 8. Encryption and streams

- **v1 uses one stream.** Right after the handshake, the client opens client-initiated
  bidirectional stream 0. It carries exactly the TCP byte stream, starting with the Minecraft
  handshake packet: VarInt-framed packets, then compression and Minecraft's AES/CFB8 encryption
  once negotiated, all unchanged. The server MUST NOT open streams in v1. Other streams are
  reserved for later versions.
- End of session: a stream FIN or connection close is treated like a TCP close.
- **Closing, like TCP.** An endpoint that closes sends what it wrote, then FIN on stream 0, and
  lets the peer close the connection: the peer reads FIN only after all the data before it, so
  nothing is lost (e.g. a disconnect message sent right before the close). The peer that
  receives FIN closes the connection with `0x0`. If no answer comes, the closing endpoint closes
  the connection itself after 10 s. An endpoint that never wrote on the stream (e.g. a QUIC
  attempt that lost the race, §5), or that already received FIN, closes the connection at once.
  Closing the connection immediately instead would discard data still unsent or unacknowledged.
- Application close error codes: `0x0` normal, `0x1` protocol violation, `0x2` internal error.
- Multi-stream (Phase 5) needs a separately reviewed encryption design; the proposed one is §12.

### Session resumption and 0-RTT *(user: 0-RTT allowed in v1)*

- **Resumption.** The server issues TLS 1.3 session tickets. The client keeps them in memory
  only, per advertised fingerprint and per server (IP, QUIC port), so a ticket is only ever
  offered to a server advertising the fingerprint it was issued under. A resumed handshake has no
  certificate exchange: the server proves itself with the ticket's key, which only the server
  that issued it holds, so it counts as a fingerprint match (§5, §7). A ticket the server
  refuses (restart, new key, expiry) turns into a full handshake with the certificate, checked as
  usual; a different server behind the same address fails the fingerprint check as before.
  Tickets don't survive a client restart.
- **What resumption saves:** the certificate, its signature and its check, i.e. CPU time on both
  ends. Not a round trip: the connect still completes after 1 RTT (§5).
- **0-RTT data: allowed, not sent by v1 clients.** A client holding a ticket MAY send its first
  flight as 0-RTT: the Minecraft handshake packet plus the packet right after it (Status Request
  or Login Start).
- **Replay protection.** 0-RTT data can be replayed by an attacker. The server MUST NOT pass
  early stream data to the game until the QUIC handshake has completed. A replayed first flight
  never completes the handshake, so it never reaches the game. Otherwise a replay could, for
  example, start a ghost login and kick the real player.
- **Why v1 clients don't send it.** With that hold, 0-RTT data saves nothing. The server's
  handshake completes when the client's Finished arrives, 1.5 RTT after the first Initial; that
  is also when 1-RTT data sent right after the client's own handshake completed (at 1 RTT, §5)
  arrives. Either way the first answer reaches the client 2 RTT after the start, as over TCP.
  Saving a round trip would require the server to act on early data before the handshake
  completes, which reopens the replay case above: a security decision for the user, not made.
- If the server rejects early data, the client resends it normally after the handshake; nothing
  else changes.
- Needs a security review before release (Phase 4), as part of the alpha's review.

## 9. Transport parameters

Every limit is set explicitly. An unset value would fall back to quiche's default, which is 0 for
flow control and stream counts, meaning nothing could be sent.

| Parameter | v1 value | Reason |
| --- | --- | --- |
| Version | QUIC v1 (RFC 9000) | |
| ALPN | `quicraft/1` | |
| `max_idle_timeout` | 60 s | Longer than Minecraft's 30 s keepalive timeout, so QUIC never times out a session the game still considers alive. |
| `initial_max_data` | 16 MiB | Covers a full chunk burst at login without stalling on flow control. **Provisional**, to tune in the benchmark. |
| `initial_max_stream_data_bidi_local` / `_remote` | 8 MiB | Same, per stream. **Provisional**. |
| `initial_max_stream_data_uni` | 0 | No unidirectional streams in v1. |
| `initial_max_streams_bidi` | 1 (server), 0 (client) | v1 uses exactly one client-opened stream. |
| `initial_max_streams_uni` | 0 | |
| `disable_active_migration` | true | No deliberate migration before Phase 9; NAT rebinding is still handled. |
| Datagrams (RFC 9221) | off | Not used before a later version. |
| Congestion control | BBR, **provisional** | Phase 1 transport benchmark (docs/benchmarks.md): the only variant faster than TCP at +150 ms RTT (−509 ms on a 2.2 MiB burst). Reno, CUBIC and plain BBR trail TCP badly under heavy reordering; BBR with the relaxed loss threshold (next row) doesn't. Sender-side only, so changing it needs no protocol version. |
| Relaxed loss threshold (quiche `enable_relaxed_loss_threshold`) | on, where the native supports it | Reordering makes quiche declare late packets lost and retransmit them. With this on, the first spurious loss disables the packet threshold, and each further one doubles the time-threshold overhead, up to 2× RTT. Transport benchmark, reorder profile (run `transport-20261010T010019Z`, N=5): 2.2 MiB burst 799 ms with it, 2754 ms without (−1955 ms [−2155, −1561]); TCP took 1212 ms. Lost packets per run were 93 against 579. No significant difference on a clean link or at +150 ms RTT. quiche 0.30 implements it only for BBR (gcongestion), so it does nothing under Reno or CUBIC. Sender-side only: no transport parameter, no protocol version. Needs QUICraft's patched native (§11). With an upstream native it is unavailable, and core logs one INFO line. |
| Address validation (Retry) | when handshake rate exceeds a threshold | A real token handler is required before the public alpha (Phase 4). `InsecureQuicTokenHandler` is never used. |

Verified against `netty-codec-classes-quic` 4.2.19.Final (Oct 2026):

- `QuicCodecBuilder` exposes `maxIdleTimeout`, `initialMaxData`,
  `initialMaxStreamDataBidirectionalLocal`/`Remote`, `initialMaxStreamDataUnidirectional`,
  `initialMaxStreamsBidirectional`/`Unidirectional`, `ackDelayExponent`, `maxAckDelay`,
  `activeMigration`, `hystart`, `discoverPmtu`, `initialCongestionWindowPackets`,
  `maxSend`/`RecvUdpPayloadSize`, `datagram(recvQueueLen, sendQueueLen)`,
  `activeConnectionIdLimit`, `grease` and `version`.
- Each value is held as a nullable boxed field. A value left unset is not passed to quiche, so
  quiche's own default applies. A Phase 1 loopback test confirms that unset flow-control limits
  can't send.
- QUICraft's build (§11) adds `relaxedLossThreshold(boolean)`, which throws
  `UnsupportedOperationException` without the native binding, and
  `Quic.isRelaxedLossThresholdSupported()`.
- Congestion control: `QuicCongestionControlAlgorithm` offers `RENO`, `CUBIC` and `BBR`. It is set
  per codec builder and applies to connections created afterwards. No API to switch a live
  connection was found.
- Retry: `QuicServerCodecBuilder.tokenHandler(QuicTokenHandler)`. Netty ships
  `InsecureQuicTokenHandler` and `NoQuicTokenHandler` only. The interface is
  `writeToken(out, dcid, address)`, `validateToken(token, address)` and `maxTokenLength()`. The
  builder also takes `connectionIdAddressGenerator` and `resetTokenGenerator`, and Netty includes
  HMAC-signing implementations of both.

## 10. Addresses and migration

- The server reports the peer's UDP address (`QuicChannel.remoteSocketAddress()`) to the platform
  as the player's `InetSocketAddress`, so IP bans, forwarding and geo plugins keep working.
- Netty reports path changes as `QuicPathEvent` user events (`New`, `Validated`,
  `FailedValidation`, `Closed`, `ReusedSourceConnectionId`, `PeerMigrated`), and
  `collectPathStats(int)` gives per-path stats. What a mid-session address change means for IP
  bans and forwarding is decided in Phase 9.

## 11. Native library and platforms

- On its own thread, core runs QUIC on Netty's NIO datagram transport, so quiche (with
  BoringSSL) is the only native library shipped. Platforms normally use the hosted transport
  below instead. Natives: linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64,
  windows-x86_64.
- Netty is relocated by adding a prefix: `io.netty` → `rs.sudoe.quicraft.shaded.io.netty`.
  Netty derives the native library name from that prefix: dots become underscores, and existing
  underscores become `_1`. So the shaded jar ships
  `librs_sudoe_quicraft_shaded_netty_quiche42_<os>_<arch>.<ext>` (no `lib` on Windows). Verified in
  `NativeLibraryLoader.calculateMangledPackagePrefix` (netty-common 4.2.19), and by
  `core`'s `shadedTest`, which loads quiche from the shaded jar.
- **The prefix must not contain `lib` or underscores.** The native code finds its JNI classes by
  parsing the prefix back out of its own file name, starting from a `lib` match. A first attempt
  with `rs.sudoe.quicraft.libs` loaded the library but then looked for classes under `s/io/netty/…`
  and failed with `NoClassDefFoundError`.
- Only the native jars carry Netty's licence and notice files (35 files: Netty, BoringSSL, quiche
  and others). The build copies them into the shaded jar, and `checkShadedNotices` verifies every
  one is present byte for byte.
- On a platform without a native, or if loading fails, the endpoint logs one INFO line and runs
  TCP-only. The client doesn't attempt QUIC; the server doesn't advertise it.

### Hosted transport (platforms)

Core's QUIC can run on the platform's own event loop instead of a thread of its own:
`QuicServer.bind(HostLoop, HostDatagramSocket, …)` and `QuicClient.connect(HostLoop,
HostDatagramSocket, …)`. Core puts its (relocated) Netty QUIC codec on a small adapter
channel and event loop that execute on the host's loop. bridge-netty implements both interfaces
over the game's Netty (`GameHost`): an `EventLoop`, and a `DatagramChannel` of the platform's
own transport (epoll on Linux for Velocity and MCProtocolLib).

Why: the UDP socket, QUIC and the game pipeline (on Velocity also the backend connection, which
Velocity opens on the player's loop) then share one thread, as with TCP. On core's own thread,
each round trip crossed four threads (game loop to QUIC loop and back on each side). On an idle
loop every crossing costs a thread wake-up of about 80–90 µs, which made QUIC about 0.35 ms
slower than TCP per round trip (`quicraft-testkit latency`, in-process loopback, 20 ms idle
between pings, p50: TCP 352 µs, bridge on core's thread 739 µs, hosted 487 µs). What remains
is quiche's per-packet work on cold caches, four packets per round trip; the wake-up segments
match TCP's exactly.

Datagrams are copied between the two Netty copies (one copy each way); nothing is shared.
Datagrams that arrive before core starts the socket are dropped (QUIC retransmits). Closing the
QUIC connection (client) or the listener (server) closes the socket. The proxy runs all QUIC
connections on one loop (one UDP socket); spreading them over several loops needs
connection-ID routing between sockets (Phase 9).

### QUICraft's Netty QUIC build

`core` doesn't use Maven Central's `netty-codec-classes-quic`. It uses QUICraft's build,
`rs.sudoe.quicraft.netty:netty-codec-{classes,native}-quic:4.2.19.Final-quicraft2` from
`natives/`. That is Netty `netty-4.2.19.Final` (64cc10f3), quiche `be47c501` and BoringSSL
`d03dbc3e`, the same revisions as upstream 4.2.19, plus four patches:

| Patch | What it does |
| --- | --- |
| `quiche/0001-ffi-relaxed-loss-threshold` | C FFI `quiche_config_set_enable_relaxed_loss_threshold`. quiche's C API lacks it, including master as of 2026-10-08. |
| `netty/0001-relaxed-loss-threshold` | `QuicCodecBuilder.relaxedLossThreshold(boolean)`, its JNI binding, and `Quic.isRelaxedLossThresholdSupported()`, which probes the loaded native. |
| `netty/0002-complete-connect-after-handshake-send` | Completes a client connect as soon as the handshake does (§5), in a task on the connection's event loop. |
| `quiche/0002-backport-fc9fe129-handshake-close` | Upstream quiche fc9fe129 (2026-09-25), unchanged. Without it, an application close sent after the client's handshake completed but before HANDSHAKE_DONE (the window netty/0002 opens) never goes out, and the send loop spins forever. |

- `natives/build-linux.sh` runs Netty's own Maven build unchanged in Docker, on AlmaLinux 8 with
  pinned GCC 13, Rust 1.98.0, CMake 3.31.9, Ninja 1.12.1 and Temurin 11. quiche commits no
  `Cargo.lock`, so `natives/quiche-Cargo.lock` pins the crate versions. Built jars stay in
  `natives/build/` and are never committed.
- **Platforms.** The patched classes are used everywhere. The patched native exists for
  linux-x86_64 only, and the other four platforms use upstream Netty's native. A patched class
  with an upstream native still works: the extra JNI method is simply unbound.
  `Quic.isRelaxedLossThresholdSupported()` then returns false, and core leaves quiche's default
  in place after one INFO line. `testUpstreamNative` checks this combination on every build.
  `.github/workflows/netty-quic-natives.yml` builds all five platforms, but has not run yet.
- **glibc.** The Linux native built on AlmaLinux 8 needs glibc 2.28 (`statx`), where upstream's
  needs 2.16. On an older system it fails to load, and the endpoint runs TCP-only (above).
- **Licences.** `natives/crate-licenses.py` checks every Rust crate the native links (38 for all
  targets, from `cargo tree -e normal,no-proc-macro`) against CLAUDE.md's table, and fails the
  build otherwise. It copies their licence files into the native jar under
  `META-INF/license/quiche-deps/`, with an `INVENTORY.txt`, and `checkShadedNotices` carries them
  into the shaded jar. BoringSSL at `d03dbc3e` is Apache-2.0, including the fiat-crypto code
  compiled into libcrypto. As upstream, the native statically links libstdc++ and libgcc (GPL-3.0
  with the GCC Runtime Library Exception).

## 12. Channel binding, pinning and dropping Minecraft's cipher (Phase 5 draft)

**Status: proposed direction (user, 2026-10-09). Draft only: not part of v1. Needs a full spec
here, the user's sign-off on that spec and a security review before any of it ships.**

### Problem

- v1 runs Minecraft's AES/CFB8 inside QUIC (§8). Every byte is encrypted twice, CFB8 has no
  integrity, and its single continuous cipher state can't be split across streams, which blocks
  multi-stream.
- Simply switching Minecraft's cipher off after login is **unsafe**. The fingerprint arrives in an
  unauthenticated, plain TCP ping (§1, §7). An active attacker who swaps it terminates QUIC/TLS on
  both sides. Minecraft's login inside still succeeds end to end (the shared secret is
  RSA-encrypted to the real server, and Mojang authenticates the player). But once the cipher is
  off, the attacker reads and injects everything between the two TLS hops.
- Encrypting the ping doesn't help either. Without a key the client already trusts,
  encryption stops only passive eavesdroppers; an active attacker runs both exchanges.

### Design: compound authentication over a TLS exporter (online mode)

After an online-mode login completes over QUIC, still under Minecraft's encryption:

1. Each side derives `E = TLS-Exporter(label = "EXPORTER-quicraft-binding", context = "", length = 32)`
   from its QUIC TLS session (RFC 8446 §7.5). With no attacker both get the same `E`. Behind an
   attacker there are two TLS sessions and two different values.
2. Client → server (plugin message): `HMAC-SHA256(K, "quicraft client binding" ‖ E)`.
3. Server → client: `HMAC-SHA256(K, "quicraft server binding" ‖ E)`, sent only if step 2
   verified.
4. `K` is derived from Minecraft's login shared secret, which only the real client and the real
   server know (exact derivation to be specified, e.g. HKDF with a QUICraft-specific label).
5. If both checks pass, both sides switch Minecraft's cipher off at an agreed packet boundary in
   each direction, the same way vanilla switches it on. From then on QUIC/TLS alone protects the
   session: authenticated encryption, one encryption layer, and per-stream protection for
   multi-stream.
6. If either check fails: disconnect, record the failure in the failure cache (§6), and log. The
   attacker learns nothing beyond what vanilla exposes.

The Minecraft login and the hash sent to Mojang's session server stay exactly as vanilla.

### Pinning

- A binding check that passes authenticates the server's QUIC certificate through Mojang's login.
  The client then **pins** that fingerprint for the server, so the fingerprint is
  authenticated without any TCP-first join: the first join can be over QUIC directly.
- On a later join, a different advertised fingerprint is not trusted silently. Behaviour (TCP
  fallback, re-binding, user-visible notice) to be specified, including legitimate key
  rotation by the server owner.

### Offline mode

There is no Mojang-bound secret, so no binding is possible. QUIC/TLS stops passive
eavesdropping only. Minecraft's cipher isn't used in offline mode anyway. Pinning is TOFU
(Phase 9).

### Optional, later

CA-signed certificates for owners with a domain, validated like WebPKI, would authenticate even
the first contact. Off by default.

### Limits (unchanged from vanilla)

- An attacker can always strip the advertisement and force TCP: the worst case equals vanilla.
- The handshake and Login Start (player name, UUID) are visible to a fingerprint-swapping
  attacker, as they are in plain vanilla TCP.

### Considered and set aside

- Per-stream Minecraft ciphers derived from the shared secret: keeps double encryption and CFB8's
  lack of integrity.
- Deriving Minecraft's shared secret from the TLS exporter: changes the Mojang hash input and the
  vanilla login.
- A TCP-first join, followed by a live handover to QUIC or a vanilla transfer packet: binding
  makes a QUIC first join safe, so neither is needed.

### Open points

- Whether Netty's QUIC TLS engine exposes BoringSSL's exporter (`SSL_export_keying_material`).
  If not, it's a small patch in the custom Netty build.
- Exact key derivation, message format, the switch-off boundary, and pin storage and rotation.
