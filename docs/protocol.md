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
26.1.2's server jar. Still to check: the vanilla client jar for every supported version, and
Velocity's own status serializer.

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
  over TCP like vanilla, and decides from its response.

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

**H = 250 ms, provisional.** Measured in the Phase 1 transport benchmark (handshake time
distribution under every testkit netem profile) and recorded here with its data.

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
- The client records fingerprints per server silently, so pinning (TOFU, warn on change) can be
  turned on in Phase 9.
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
- Application close error codes: `0x0` normal, `0x1` protocol violation, `0x2` internal error.
- Multi-stream (Phase 5) needs a separately reviewed encryption design.

### 0-RTT *(user: allowed in v1)*

- The server issues session tickets. A client holding a ticket for the same server and
  fingerprint MAY send its first flight as 0-RTT: the Minecraft handshake packet plus the packet
  right after it (Status Request or Login Start).
- **Replay protection.** 0-RTT data can be replayed by an attacker. The server MUST NOT pass
  early stream data to the game until the QUIC handshake has completed. A replayed first flight
  never completes the handshake, so it never reaches the game. Otherwise a replay could, for
  example, start a ghost login and kick the real player. The client still saves the round trip,
  because its data travels with the handshake.
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
| Congestion control | CUBIC, **provisional** | Chosen in the Phase 1 benchmark among RENO, CUBIC and BBR (user request). Sender-side only, so changing it needs no protocol version. |
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

- QUIC runs on Netty's NIO datagram transport, so quiche (with BoringSSL) is the only native
  library shipped. Natives: linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64,
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
