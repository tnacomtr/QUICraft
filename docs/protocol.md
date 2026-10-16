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
"quicraft:quic": { "v": 1, "port": 25565, "alpn": "quicraft/2", "fp": "sha256:<64 hex>" }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `v` | integer | Protocol version. This document is `1`. |
| `port` | integer 1–65535 | UDP port of the QUIC listener (§2). |
| `alpn` | string | ALPN the server accepts, naming the stream format. This document: `quicraft/2`. |
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
stays under the cap there through `Advertisement.insertInto`. 26.2 and 26.3 use the same cap:
their `ClientboundStatusResponsePacket` passes 32767 to `ByteBufCodecs.lenientJson` (checked in
the bytecode, 2026-10-11).

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
   certificate fingerprint matched (§7), or the session was resumed from a ticket issued under
   that fingerprint (§8). For TCP, it means the connection is established.
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
compares two runs; TCP connect was 150.5 and 150.4 ms in them. On a rejoin, 0-RTT (§8) lets the
server answer one round trip earlier still.

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
- A resumed session (§8) presents no certificate. It is trusted because its ticket came from an
  earlier full handshake whose certificate matched the same `fp`, and only the server holding
  that ticket's key can complete it.
- No trust prompt before Phase 9. Minecraft's own encryption still protects the session, and an
  attacker who can strip the advertisement can force silent TCP anyway, so a warning would add
  scary UI without real protection.
- The client records fingerprints per server silently, so pinning can be turned on later: in
  online mode via channel binding (§12, Phase 5), in offline mode as TOFU (Phase 9).
- Implementation note: Netty's `SelfSignedCertificate` needs BouncyCastle (license not in the
  project's table), JDK-internal `sun.security.x509` classes (blocked on modern JDKs), or the
  `keytool` binary (often missing). QUICraft encodes its one certificate itself.

## 8. Encryption and streams

- **v1 uses one stream.** The client opens client-initiated bidirectional stream 0, right after
  the handshake or, with 0-RTT, during it. After one preamble in each direction (below) it
  carries exactly the TCP byte stream, starting with the Minecraft handshake packet:
  VarInt-framed packets, then compression and Minecraft's AES/CFB8 encryption once negotiated,
  all unchanged. The server MUST NOT open streams in v1. Other streams are reserved for later
  versions.
- **Preamble.** Each direction of stream 0 starts with exactly one preamble, before any
  Minecraft byte:

  | Field | Type | Values |
  | --- | --- | --- |
  | length | VarInt | length of the rest, 2–64 |
  | version | byte | `1` |
  | token length | byte | `0` or `16` |
  | token | bytes | client → server: an early token this server issued (0-RTT, below), or none. Server → client: a fresh early token for the client's next connection, or none |

  An unknown version, a length outside 2–64, or a token length other than 0 or 16 closes the
  connection with `0x1`. The server sends its preamble as soon as it passes the stream to the
  game. Both sides strip the preambles; the game never sees them.
- End of session: a stream FIN or connection close is treated like a TCP close.
- **Closing, like TCP.** An endpoint that closes sends what it wrote, then FIN on stream 0, and
  lets the peer close the connection: the peer reads FIN only after all the data before it, so
  nothing is lost (e.g. a disconnect message sent right before the close). The peer that
  receives FIN closes the connection with `0x0`. If no answer comes, the closing endpoint closes
  the connection itself after 10 s. An endpoint that never wrote on the stream (e.g. a QUIC
  attempt that lost the race, §5), or that already received FIN, closes the connection at once.
  Closing the connection immediately instead would discard data still unsent or unacknowledged.
- Application close error codes: `0x0` normal, `0x1` protocol violation, `0x2` internal error,
  `0x3` early data never confirmed (below), `0x4` early data abandoned by the client (below).
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
- **0-RTT *(user: server acts on it, guarded by single-use tokens)*.** A rejoin sends the
  Minecraft handshake and Login Start as 0-RTT data, and the server answers them before the
  handshake completes: the first reply arrives **1 RTT** after the client's first Initial instead
  of 2 (measured through a 200 ms RTT relay: 1.02 RTT against 2.03; needs QUICraft's Netty build,
  netty/0003 in §11). A first join, and the first join after either side restarted, gets nothing
  from it: there is no ticket, token or recorded flight yet.

**Early tokens (server).**

- A token is 16 random bytes from a CSPRNG. The server issues a fresh one in its preamble on
  every stream it accepts and keeps it in memory, per listener, with its issue time. Tokens are
  valid for 24 hours; at most 65 536 are outstanding, the oldest dropped first. A restart drops
  them all (its ticket keys change too).
- **Single use.** A token in a client preamble is removed when the server reads it, whether the
  data was early or not, valid or not.
- **Early release.** Stream data that arrives before the handshake has completed is passed to the
  game at once if its preamble carries a token the server holds. Otherwise (no token, unknown,
  used, expired) it waits until the handshake has completed, as without 0-RTT.
- **Confirmation.** A connection passed early must complete its handshake within **10 s**. If
  not, the server closes it with `0x3`, and the game sees an ordinary disconnect. Long enough
  that a client which already counts QUIC as the winner (its handshake completed) gets its
  Finished through to the server under loss and high RTT; a login it abandoned is closed sooner
  where that matters (abandoned logins, below).
- Not bound to the client's address: a rejoin after a network change still gets 0-RTT *(user)*.

**0-RTT (client).**

- The client keeps, in memory only: the latest token per server (IP, QUIC port, fingerprint), and
  the **recorded first flight** per server and join inputs (host name and port sent in the
  handshake, intent, profile name and UUID, protocol version): the bytes of the handshake and
  Login Start the game wrote on its last QUIC join with those inputs.
- It sends 0-RTT data only when the TLS session allows early data and it holds a token and a
  recorded flight for this join's inputs: the preamble with the token, then the recorded flight.
  The token is forgotten once sent. Otherwise its preamble (no token) goes first on stream 0
  after the handshake, followed by the game's bytes.
- **Racing is unchanged** (§5): QUIC wins only when its handshake completes. 0-RTT changes when
  the server acts, not who wins.
- **Duplicate removal.** On a QUIC win, the game's own first writes on stream 0 are its handshake
  and Login Start again; the client drops them if they equal the recorded flight byte for byte.
- **Mismatch.** If they differ (e.g. another mod changed the packets), the server has already
  acted on the recorded flight. The client closes QUIC, forgets the recorded flight and token for
  that server, and starts the join again over TCP: the player sees the connect screen once more,
  no error.
- **TCP wins after 0-RTT went out.** The server may have acted on the early flight: a login is in
  progress there that the client has abandoned. The client closes the QUIC connection first and
  hands the TCP connection to the game one TCP round trip later (the time its connect took,
  between 20 and 250 ms), so the close reaches the server before the TCP login. quiche sends that
  CONNECTION_CLOSE only if the client has received a packet from the server; a client that heard
  nothing (server-to-client UDP lost or blocked) closes silently, and the abandoned login lasts
  until the server's 10 s confirmation deadline.
- **Abandoned logins on the server.** Velocity registers an offline-mode player before Login
  Acknowledged and refuses a second login with the same name ("already connected to this proxy")
  until the first connection closes. So a server platform that holds a name during login MUST,
  when a login arrives, close the unconfirmed early connections from the same IP whose Login
  Start carries the same name (except the login's own connection), and let the login continue
  only once they are closed (core: `QuicServer.closeUnconfirmedEarly`; Velocity: on
  `PreLoginEvent`). Vanilla, Fabric and Paper don't hold a name before the player is in the world
  and need nothing. Players behind one IP never lose a confirmed connection to this: only
  connections still unconfirmed are closed, and only for the same name.
- If the server rejects the 0-RTT data (ticket refused after a restart), quiche resends it after
  the handshake once loss detection declares it lost (4.2 RTT to the first reply measured at
  200 ms RTT, instead of 2); the token then works as for any data after the handshake.
- **Rejected 0-RTT.** Waiting for quiche's loss detection (above) is slow. So when the handshake
  completes and the server's preamble hasn't arrived yet, the client waits up to a grace period
  G = clamp(handshake time / 4, 5 ms, 100 ms), the handshake time being connect start to
  handshake completion (measured on the client). A preamble that arrives within G changes
  nothing, and the connection goes on as soon as it arrives, not when G ends. If none has
  arrived by then, the 0-RTT is treated as rejected: the client closes
  that connection with `0x4` (early data abandoned), without passing it to the game, and opens a
  new connection to the same address in the same attempt. The new connection is a full
  handshake with no 0-RTT and no session ticket for that server (the client may drop the tickets
  of other servers with the same certificate too), and the Minecraft first flight is sent
  normally after it. Only one such retry per attempt. If the retry fails, the attempt
  fails as usual: racing (§5) and the failure cache (§6) apply.
- A server treats `0x4` like any other close: the connection ends, and a login started from
  that stream is dropped, so the game sees a disconnect. A server may count `0x4` closes. Netty
  reports the peer's application close code, so a core server can count them. A lost
  CONNECTION_CLOSE is sent again by quiche when the server sends more packets on that
  connection; no further code is needed for that. Older servers see an unknown application
  close code and close normally, so no ALPN or version bump is needed.

**Replay (security).** 0-RTT data can be replayed by anyone who sees it.

- A copy of an earlier join's flight carries a token that join already used: the server holds it,
  the handshake never completes (the copier has no keys), and it never reaches the game.
- A copy of the current flight that arrives before the original is passed early instead of it,
  once; the original then waits for its handshake and gets in a round trip later. The copy stops
  where the game first needs the client: at the encryption response in online mode (nothing
  happens), at Login Acknowledged in offline mode, where the server has already started a login.
  On Velocity in offline mode that login holds the player's name until the real client's login
  arrives, which then closes it (abandoned logins, above), or for up to 10 s: an attacker on the
  path can make one join fail, which dropping packets would do as well. The game sees the copy's
  source address, unvalidated, for up to 10 s.
- Needs the security review before release (Phase 4): this changes the earlier rule that early
  data never reached the game before the handshake completed. Also for the review: the platforms'
  close of unconfirmed early logins by IP and name (who can trigger it: anyone who can open a TCP
  login with that name from that IP), and the token store under a connection flood (each accepted
  stream issues a token; at 65 536 the oldest go, which only costs those clients their next 0-RTT;
  Phase 4's per-IP and global limits bound the rate).

## 9. Transport parameters

Every limit is set explicitly. An unset value would fall back to quiche's default, which is 0 for
flow control and stream counts, meaning nothing could be sent.

| Parameter | v1 value | Reason |
| --- | --- | --- |
| Version | QUIC v1 (RFC 9000) | |
| ALPN | `quicraft/2` | Names the stream format of §8, preambles included. `quicraft/1` was the unreleased format without preambles (Phase 3 builds); renamed *(user, 2026-10-11)* so mismatched builds fall back to TCP instead of joining over QUIC and being closed. |
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
| Initial congestion window (`initialCongestionWindowPackets`) | 64 packets. System property `quicraft.initialCwndPackets` overrides it: an explicit `0` selects quiche's native default (10 packets), `1..1000` the value. A malformed or out-of-range value falls back to 64. | The initial window dominates connect-to-last-chunk under delay. At +150 ms, the 64-packet arm's connect-to-last-chunk median was 189 ms faster than TCP (run `20261011T184452Z-fabric-tcp-quic`, initial window experiment in docs/benchmarks.md). No clean-link regression of ~100 ms or larger was detected at 64, but equivalence is not established, and under 2% loss the two arms are indistinguishable (run `20261011T192603Z-fabric-tcp-quic`), so this is not a universal gain. Sender-side only, so changing it needs no protocol version. |
| Address validation (Retry) | always for refusals: an attempt over a limit, full open-connection limits, or a handshake backlog. For admitted attempts it depends on the mode (`quicraft.admission.retry`): `adaptive` (default) once the listener's attempts in the current second reach a threshold (default 50), `always` for every one, `never` for none | Retry tokens are HMAC-authenticated, bound to the client's IP and port, and expire after 30 s. `InsecureQuicTokenHandler` is never used. |

Retry and limits. Each listener limits connection attempts (per second, overall and per IP) and
open connections (overall and per IP), and drops floods of Initial packets before the QUIC codec.

- "Per IP" means per IPv4 address (IPv4-mapped IPv6 included) or per IPv6 /64. So the hosts of
  one /64 share their budgets, while a holder of a /48 gets 65536 separate keys; aggregating
  larger prefixes is left out on purpose, as it would penalise the many unrelated users behind
  shared prefixes.
- A datagram carrying an Initial that is shorter than 1200 bytes (RFC 9000 §14.1) is dropped
  unanswered, so a Retry (at most 124 bytes with QUICraft's tokens) only ever answers a datagram
  of at least 1200 bytes.
- Ingress flood protection counts every Initial datagram per IP and overall (defaults 200 and
  8000 per second), whether or not it carries a token, and drops those over budget before any
  other check.
- An Initial that passes ingress spends a connection attempt. A token-less one spends the
  attempt budget; one that brings back a valid Retry token spends a separate validated budget
  with the same limits, which a flood of token-less Initials can't use up. Validated Initials
  still count against the shared ingress budget.
- These are refusals, in every Retry mode: an attempt over its limit, an attempt while the
  open-connection limits are full, and every token-less attempt while a quarter of the
  open-connection limit is still in its handshake (a backlog, e.g. of spoofed half-open
  handshakes). A token-less Initial is then answered with a Retry and nothing more. A
  token-bearing Initial over its validated limit, or arriving while the open-connection limits
  are full, is dropped unanswered; a backlog alone doesn't refuse it, as its address is proven.
  The client falls back to TCP after its head start
  (§5). The modes differ only for admitted attempts: `never` sends them no Retry, `adaptive`
  sends Retry once the attempt rate reaches the threshold, and `always` sends it every time.
- A connection whose handshake hasn't completed 10 s after it was created is closed (transport
  error NO_ERROR); a real client that slow falls back to TCP.
- A join that goes through Retry may spend up to 3 attempts: one for each token-less Initial
  datagram of the client's first flight (often two), plus one when the token comes back. A Retry
  also discards 0-RTT data sent before it, so such a rejoin completes as a 1-RTT join.

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
- Known limit (measured 2026-10-14, `ReusePortListenerTest`, Linux, Netty QUIC 4.2.19): a
  connection survives **one** NAT rebind, with one listener socket or several. After a second
  rebind of the same connection the server's datagrams stop reaching the client and the connection
  runs into its idle timeout. The same happens with one socket, so it comes from the codec's path
  and connection-id handling, not from the socket dispatch. Changing it touches transport
  parameters (for example the active connection-id limit) and is left to Phase 9.
- Known limit (Retry against a changed 4-tuple, not fixed in v1): a Retry is a long header, so the
  socket that received the Initial answers it and the connection id the client then uses names that
  socket. If the client's next Initial arrives at a different socket (its 4-tuple changed in
  between; a long header decodes no index), no single socket can serve the connection: the client's
  later short-header datagrams are forwarded to the socket the id names, which knows no such
  connection, and the Retry token is bound to the IP address and port it was issued to
  (`HmacRetryTokenHandler`), so such an Initial is dropped rather than answered. Either way the
  handshake does not complete, and the client falls back to TCP after its head start (§5). Retry
  only starts under load in the default `ADAPTIVE` mode, so this needs the attempt rate to reach the
  Retry threshold.

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
QUIC connection (client) or the listener (server) closes the socket.

### Several UDP sockets per listener (SO_REUSEPORT)

One event loop thread per listener is the known bottleneck at the scale target in `CLAUDE.md`
(50,000+ concurrent players per node), so a listener can run on several UDP sockets bound to the
same address with SO_REUSEPORT, each on its own event loop thread.

- `QuicServer.bind(address, socketCount, identity, config, acceptor)`: core creates `socketCount`
  NIO datagram channels on a group of `socketCount` threads, one channel per loop, each with
  SO_REUSEPORT. The first socket binds the requested address; when the request had port 0, the
  others bind the port the kernel gave the first one. SO_REUSEPORT is looked up reflectively
  (`StandardSocketOptions.SO_REUSEPORT` exists only on JDK 9+ while core is Java 8 bytecode) and
  set through `NioChannelOption`. Where the JDK or the platform does not support it, the listener
  binds one socket, so a bind never depends on it.
- `QuicServer.bind(List<HostLoop>, List<HostDatagramSocket>, ...)` for the hosted transport above:
  the platform binds each of its own sockets with SO_REUSEPORT and hands one loop per socket. A
  failure to bind sockets 2..N is not fatal there: the platform closes them and keeps the first
  socket (see `udp-sockets` below).
- `QuicServer.defaultSocketCount()`: `min(availableProcessors(), 16)` on Linux. Everywhere else it
  is 1: macOS and the BSDs hash unicast UDP on the local address alone, so several sockets would
  starve, and Windows has no SO_REUSEPORT. The system property `quicraft.udpSockets` (1..64; an
  invalid value logs one warning and keeps the default) overrides it.
- `udp-sockets` in the platform's own config (`plugins/quicraft/config.properties` for Velocity,
  `config/quicraft/server.properties` for the Fabric dedicated server): `auto` (the default) is
  `QuicServer.defaultSocketCount()`, an integer 1..64 is that many, and anything else is `auto` with
  one warning. It is the count the hosted path binds: as many of the platform's own UDP sockets,
  one per distinct loop of the game's worker group (fewer when the group has fewer loops), each with
  SO_REUSEPORT found for that transport (the transport's own `ChannelOption`, or `NioChannelOption`
  over `StandardSocketOptions.SO_REUSEPORT` for NIO), the first on the configured address and the
  rest on its actual local address. An accepted stream stays on the loop of the socket that
  received it, and the listener's startup line names the socket count it bound.
- Only Linux, and only when the transport has a SO_REUSEPORT the kernel accepts, binds several:
  anywhere else, or if the option is refused, the listener keeps one socket with one INFO line. If
  binding a later socket fails, that socket is closed and the listener keeps the sockets already
  bound, also with one INFO line. So `udp-sockets` can never fail a startup. SO_REUSEPORT is not
  inert with one socket either: the first socket is configured with the option before it binds, so
  any process of the same OS user can also bind that UDP port with SO_REUSEPORT and take a share of
  the traffic (`docs/server-security.md`).
- `close()` closes every socket and, for core's own transport, the thread group. A bind that fails
  on a later socket closes the sockets already bound and the group, then rethrows.

Routing between the sockets is Netty's `QuicCodecDispatcher`, one shared instance in every
socket's pipeline. Its connection id generator encodes the socket's index in the first two bytes
of every connection id that socket's codec issues: the initial source id, a Retry source id, and
every extra source id issued later. A packet that the kernel's 4-tuple hash put on the wrong
socket (NAT rebinding, migration, an idle socket) still reaches the codec that owns the
connection: the dispatcher reads the destination connection id of a short-header packet, decodes
the index and forwards the datagram to that socket's codec, on that socket's loop. Long headers,
an Initial for example, always go to the socket that received them, and that socket then chooses
the connection id the client uses. Because that forwarding crosses loops (it is one queued task on
the target loop per datagram), it is bounded: at most 1024 datagrams may be pending per target
socket at a time (`QuicServer.ForwardLimiter.MAX_PENDING_FORWARDS`), counted across all source
sockets, plus at most one per source socket (each source socket measures the count and increments
it on its own loop). Over that the datagram is released and counted per target socket, so a client
that names one socket from many source ports can slow that socket down but cannot grow its queue
without bound. One datagram never reaches the dispatcher at all: a short header that stops before
the whole connection id (`40`, `40 00`, or a
header without the full 20-byte id) is one the dispatcher's own parser throws for
(`QuicHeaderParser.sliceCid`), and no handler on a datagram pipeline catches that, so Netty's tail
would log a stack trace and leak the datagram, once per datagram, for anything a remote sender
cares to send. The bound releases such a datagram and counts it against the socket that read it
(`QuicServer.droppedMalformedPerSocket`), and a last handler on every socket of a multi-socket
listener (`QuicServer.DatagramExceptionBackstop`) swallows whatever else a datagram handler throws,
logging at most one FINE line a minute per listener instead of a stack trace per datagram.

Only the sockets and their loops are multiplied. Admission (attempt budgets, open-connection
leases, Retry), the Retry HMAC key and its token handler, the ingress datagram budget, the 0-RTT
early tokens, the TLS context, the stream acceptor and `closeUnconfirmedEarly` stay one set per
listener, so every limit in section 9 is node-wide, not per socket. A count of 1 is exactly the
single-socket listener: no dispatcher is installed and the connection id length is unchanged.

### QUICraft's Netty QUIC build

`core` doesn't use Maven Central's `netty-codec-classes-quic`. It uses QUICraft's build,
`rs.sudoe.quicraft.netty:netty-codec-{classes,native}-quic:4.2.19.Final-quicraft5` from
`natives/`. That is Netty `netty-4.2.19.Final` (64cc10f3), quiche `be47c501` and BoringSSL
`d03dbc3e`, the same revisions as upstream 4.2.19, plus seven patches:

| Patch | What it does |
| --- | --- |
| `quiche/0001-ffi-relaxed-loss-threshold` | C FFI `quiche_config_set_enable_relaxed_loss_threshold`. quiche's C API lacks it, including master as of 2026-10-08. |
| `netty/0001-relaxed-loss-threshold` | `QuicCodecBuilder.relaxedLossThreshold(boolean)`, its JNI binding, and `Quic.isRelaxedLossThresholdSupported()`, which probes the loaded native. |
| `netty/0002-complete-connect-after-handshake-send` | Completes a client connect as soon as the handshake does (§5), in a task on the connection's event loop. |
| `netty/0003-server-reads-accepted-early-data` | A server that accepted 0-RTT reads early stream data before the handshake completes: the connection is active during early data, and after the send that decrypts a buffered 0-RTT packet it processes the readable streams. Java only, so it works with upstream natives too. `SslHandshakeCompletionEvent` still marks the completed handshake. |
| `netty/0004` | TLS keying material exporter accessor and capability probe, with JNI `SSL_export_keying_material` (12.2). Netty-only patch; the patched native is available only on linux-x86_64. |
| `netty/0005-max-flow-control-windows` | `QuicCodecBuilder.maxConnectionWindow(long)` and `maxStreamWindow(long)` with JNI `quiche_config_set_max_connection_window` / `quiche_config_set_max_stream_window`, and the capability probe `Quic.isMaxFlowControlWindowsSupported()`, so the maxima of quiche's flow-control auto-tuning can be pinned. A maximum equal to the initial window disables growth for it (quiche clamps every window to its maximum). The setters alone do not satisfy 13.13: the values `/3` configures must still keep growth within 13.12's budgets, and `/3` stays off until they do. Unset, quiche keeps its defaults. Netty-only patch; the quicraft5 native is built and tested on all five platforms by the `netty-quic-natives` workflow (first green run 38876125706). |
| `quiche/0002-backport-fc9fe129-handshake-close` | Upstream quiche fc9fe129 (2026-09-25), unchanged. Without it, an application close sent after the client's handshake completed but before HANDSHAKE_DONE (the window netty/0002 opens) never goes out, and the send loop spins forever. |

- `natives/build-linux.sh` runs Netty's own Maven build unchanged in Docker, on AlmaLinux 8 with
  pinned GCC 13, Rust 1.98.0, CMake 3.31.9, Ninja 1.12.1 and Temurin 11. quiche commits no
  `Cargo.lock`, so `natives/quiche-Cargo.lock` pins the crate versions. Built jars stay in
  `natives/build/` and are never committed.
- **Platforms.** The patched classes are used everywhere. The `netty-quic-natives` workflow
  builds the patched native for all five platforms and runs core's tests against it on each
  platform's own runner (first green run 38876125706, 2026-10-16). A local build bundles the patched
  native only for the platforms named in `-Pquicraft.patchedNatives` (linux-x86_64 by default,
  from `natives/build-linux.sh`); the others use upstream Netty's native. A patched class
  with an upstream native still works: the extra JNI method is simply unbound.
  `Quic.isRelaxedLossThresholdSupported()` then returns false, and core leaves quiche's default
  in place after one INFO line. The exporter probe also returns false with upstream
  natives, disabling `/3` under 12.3 rather than attempting the unbound exporter JNI call.
  `Quic.isMaxFlowControlWindowsSupported()` likewise returns false, the maximum-window setters
  throw `UnsupportedOperationException`, and `QuicSupport.hasMaxFlowControlWindows()` is false,
  so `/3` is not offered.
  `testUpstreamNative` checks this combination on every build.
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

## 12. Channel binding, pinning and dropping Minecraft's cipher (Phase 5)

**Draft v2. Design approved by the user 2026-10-15 (open questions 1-4 as written below); implementation may proceed behind a disabled-by-default flag. Security review is required before any release or enablement.**

### Scope and capability gate

1. **12.1 Compatibility.** Sections 12 and 13 describe only the proposed `quicraft/3`
   ALPN. They MUST NOT change shipped `quicraft/2` (v1), its preamble version 1,
   encryption, 0-RTT, stream counts, or TCP. Guarded implementation is authorized only
   behind a disabled-by-default flag; security review is required before enablement or
   release. Initially only reviewed 26.x Fabric client/dedicated-server adapters may
   enable it; Velocity remains single-stream until Phase 7.
2. **12.2 Exporter availability.** Upstream Netty QUIC exposes no TLS exporter. The required
   Netty-only `natives/patches/netty/0004` patch binds BoringSSL's
   `SSL_export_keying_material` through JNI and `BoringSSL.java`, exposes an accessor on
   `QuicheQuicChannel` restricted to that channel's event loop, and provides
   `Quic.isKeyingMaterialExportSupported()` in §11's `quicraft4` build. Patched natives
   for all five platforms are built and pass core's tests (native load, exporter, shaded jar)
   on their own CI runners (run 38876125706), but a release only carries the ones it is built
   with (`-Pquicraft.patchedNatives`). Ship and test patched natives for all five platforms
   before enabling this feature on all five; enable a platform only after its own native
   load, exporter equality, and upstream-native compatibility tests pass.
3. **12.3 Gate before negotiation.** Both peers MUST have a true exporter capability probe
   and a supported routing table before advertising/offering `/3`, even for the offline
   test profile. A server without either advertises and listens with `/2` only, retains
   Minecraft's cipher in online mode, and uses one stream. A client without either that sees a
   `/3` advertisement uses TCP, because a `/3` listener accepts only `/3` (13.1); it uses `/2`
   only for a `/2` advertisement. No native at all retains §11's TCP-only behavior. An exporter
   error after `/3` selection is fatal, not permission to continue cipher-off or to silently
   renegotiate that connection. A false capability probe is an ordinary compatibility
   condition, logged once at INFO, not a binding failure.

### Cryptographic inputs and messages

4. **12.4 Login prerequisite.** Keep the vanilla RSA exchange, 16-byte Minecraft shared
   secret `S`, AES/CFB8, Mojang hash inputs and session authentication unchanged. Begin binding
   only in configuration state, after successful online authentication and TLS handshake
   confirmation on both ends. Hold Finish Configuration until activation completes. The
   server terminating Minecraft login and the QUIC endpoint must be the same trusted
   component; forwarding a secret to an unrelated endpoint is not supported.
5. **12.5 Encoding.** In these sections `||` is byte concatenation; quoted cryptographic
   strings are US-ASCII without a terminating NUL. `u8`, `u32` and `u64` are unsigned
   big-endian fixed-width integers. Hashes and MACs are raw 32-byte values, not hex.
   Minecraft packet lengths/ids retain Minecraft VarInt encoding. New VarInts MUST be
   minimally encoded, nonnegative and at most five bytes; invalid encodings are fatal.
6. **12.6 Key derivation.** Use RFC 5869 HKDF-SHA256 exactly:
   `PRK = HKDF-Extract(salt = SHA256("quicraft binding v2 salt"), IKM = S)`;
   `K = HKDF-Expand(PRK, info = "quicraft binding v2 key", L = 32)`.
   Do not use a password encoding, UUID, Mojang hash, or TLS secret in place of `S`.
   Erase task-owned copies of `S`, `PRK` and `K` after activation or failure; never log them.
7. **12.7 Exporter and transcript.** On the channel event loop, after handshake confirmation,
   obtain `E = TLS-Exporter("EXPORTER-quicraft-binding-v2", context = empty byte string,
   length = 32)` using the RFC 8446 §7.5 normal exporter, with context explicitly supplied
   (JNI `use_context = 1`, context length 0), never the early exporter. Define
   `T = SHA256("quicraft/3 binding v2" || u32(protocol_number) || u8(mode) || F || R)`.
   `mode = 1` requires Hello's `shouldAuthenticate=true`, successful client `joinServer`
   and successful server `hasJoined`; `mode = 0` requires no Minecraft encryption at all.
   Encrypted-but-unauthenticated login MUST never offer `/3` or MUST close it with `0x10`.
   The client aborts `/3` at the first login packet showing authentication is absent
   (including Hello with `shouldAuthenticate=false`, or Login Success without authenticated
   encryption), before processing configuration. Apply offline handling in 12.16/12.19;
   do not infer authentication merely from possession of `S`.
   **Handshake confirmation without a native signal.** The pinned Netty reports TLS handshake
   completion, not RFC 9001 §4.1.2 confirmation (receipt of HANDSHAKE_DONE). `/3` allows
   0-RTT under 12.18, so a server reply to Login Start (which may be early data, answered
   in 0.5-RTT) proves nothing. The gate is instead: a client reads server stream data only
   after its own handshake has completed, so every packet it sends after reading server
   data is 1-RTT, never early data; a server MUST NOT process 1-RTT packets before its
   handshake is complete (RFC 9001 §5.7). The server enters configuration only after
   processing such a packet (Encryption Response in online mode; Login Acknowledged under
   the offline test flag), so any configuration-stage server message proves the server's
   handshake is complete, which for a server is confirmation. Binding begins only in
   configuration. The server additionally refuses to start binding (`0x10`) unless it has
   seen its own TLS handshake completion. An implementation MAY use handshake completion
   plus this ordering as the confirmation gate, with no native patch, and MUST test, with
   0-RTT accepted, that neither side starts binding before the server's handshake completes.
   `F` is SHA-256 of the actual leaf certificate DER
   (or the original ticket-associated certificate on resumption); `R` is the routing
   resource digest in 13.7. Both peers calculate these locally and reject differing inputs.
8. **12.8 Plugin-message format.** Use configuration Custom Payload packets on channel
   `quicraft:binding` in both directions, intercepted by the adapter, never delivered to
   ordinary plugins. Payload is exactly `u8(version=2) || u8(kind) || u8(mode) ||
   u32(protocol_number) || F[32] || R[32] || tag[32]` (103 bytes). There are no optional
   fields or trailing bytes. `kind`: 0 OFFER (server), 1 CLIENT_PROOF, 2 SERVER_PROOF,
   3 CLIENT_SWITCH, 4 SERVER_SWITCH. OFFER has an all-zero tag; every other online tag is
   `HMAC-SHA256(K, "quicraft binding v2 message" || u8(kind) || T || E)`.
   Distinct kinds provide direction and phase separation. No bare tag is a valid packet.
   `protocol_number` MUST be in unsigned 32-bit range (0..4294967295); reject values outside
   that range before encoding, never truncate or wrap. The transport MUST catch exporter
   errors (including native exceptions) and wrong-length values (anything other than 32
   bytes) and close `/3` with `0x10`, never allow an exception to bypass fail-closed handling.
   Outside `/3`, `quicraft:binding` payloads MUST trigger no binding, pin, cipher, framing
   or transport action. Velocity (Phase 7) MUST strip this channel in both directions,
   including backend forwarding, so an unrelated endpoint cannot drive a binding exchange.
9. **12.9 Validation.** Accept exactly OFFER, CLIENT_PROOF, SERVER_PROOF, CLIENT_SWITCH,
   SERVER_SWITCH, in that order, one each, in the specified direction and configuration
   state. Client responds only to a matching OFFER; server sends SERVER_PROOF only after
   verifying CLIENT_PROOF; client sends CLIENT_SWITCH only after verifying SERVER_PROOF;
   server sends SERVER_SWITCH only after verifying CLIENT_SWITCH. Compare all 32 MAC bytes
   in constant time after exact-length validation. Unknown versions/kinds, duplicates,
   reflection, mismatched metadata, unsolicited payloads or invalid MACs terminate `/3`.

### Exact cipher and framing cutover

10. **12.10 Quiescence.** Before sending OFFER, the server drains all previously queued
    outbound configuration packets. On receiving OFFER the client drains its earlier writes
    before CLIENT_PROOF. Thereafter both pause ordinary outbound game packets and state
    changes, queueing at most 256 packets or 8 MiB per endpoint (whichever is reached first;
    overflow disconnects). Binding packets alone may bypass that queue. Earlier inbound game
    packets remain legal until the peer's final switch packet. No other streams may carry
    bytes before activation. Cutover and pipeline changes execute on the connection event loop.
11. **12.11 Client-to-server boundary.** CLIENT_SWITCH is the last complete Minecraft-framed,
    compressed-as-negotiated, AES/CFB8-encrypted packet in the client-to-server direction.
    Client disables its outbound cipher immediately after encrypting its final byte and
    sends nothing more until SERVER_SWITCH is verified. Server decrypts through precisely
    the final byte of CLIENT_SWITCH, validates it, then disables its inbound cipher.
12. **12.12 Server-to-client boundary.** SERVER_SWITCH is likewise the last encrypted
    Minecraft packet in the server-to-client direction. Server queues its entire encrypted
    frame before disabling its outbound cipher; all following bytes use §13 records.
    Client decrypts exactly through that packet, verifies it, then disables its inbound
    cipher and starts the §13 parser on the very next byte. Server may start records after
    queuing SERVER_SWITCH; client starts records only after validating it. Stream ordering
    provides the agreement; QUIC ACKs are not application activation acknowledgements.
13. **12.13 No read-ahead ambiguity.** The adapter MUST consume ciphertext only as far as the
    current packet boundary while binding, not decrypt an entire received buffer blindly.
    If encrypted SERVER_SWITCH and new record bytes arrive in one read, the suffix MUST
    remain untouched until the cipher/parser transition; split reads must produce the same
    result. Pending encrypted writes remain encrypted, and queued ordinary packets are
    encoded anew as records after activation. Do not reset or reuse CFB8 on another stream.
    Install the boundary-aware decoder on the client before it sends CLIENT_SWITCH and
    on the server before it sends OFFER: vanilla's cipher decoder decrypts the entire
    readable buffer and is not safe at this boundary. In 12.11/12.12 remove only the
    cipher handlers, not vanilla's authenticated-encryption state. `Connection.isEncrypted()`
    (or that version's equivalent) MUST remain true after online cutover so profile keys,
    chat signing and tab-list behavior remain vanilla.

### Failure, pins and rotation

14. **12.14 Fail closed.** At each endpoint, allow 10 seconds total from entering configuration
    to activation, using a monotonic clock; a missing OFFER also expires this deadline.
    Any validation/exporter error, timeout, premature close or queue overflow closes
    the whole QUIC connection with application error `0x10` (binding/activation failure).
    Client records §6's failure-cache entry for the attempted address/port/fingerprint;
    activation failure overrides a previous handshake success and is cleared only by a
    later successful activation for that key. Server logs a reason code and rate-limits
    to one warning per minute per listener; client logs once per failed attempt. Logs contain
    no secrets, exporter values, tags or payload dumps. Do not retry cipher-off or continue
    half-switched. **Join-time TCP retry (user decision 2026-10-16).** A failure before
    activation completes (any of the above, a handshake or ALPN failure, or any exception in
    QUICraft code) closes QUIC first (with `0x10` where a connection exists) and then starts a
    fresh vanilla TCP join in the same attempt: a new Login Start and, when online, a new
    Mojang session join. Never transfer old login state or `S`. `quic-only` fails instead.
    The client writes "QUIC multi-stream failed (<reason>), fell back to TCP" to its log and
    shows it in chat once the TCP join reaches play; `<reason>` is a short human-readable
    cause and never contains secrets. The failure-cache entry above still applies, so later
    joins go straight to TCP during the backoff. Rationale: the retry only reaches vanilla
    TCP with Minecraft's own encryption, which an attacker can already force by stripping the
    advertisement (§7); the security review MUST confirm this. A failure
    after activation (during play) is not retried: the disconnect screen shows the reason and
    the next join uses TCP under §6. Pre-login racing and compatibility fallback remain §5
    behavior.
15. **12.15 Pin persistence.** Only the client, after valid SERVER_SWITCH in online mode,
    records an authenticated pin. Store in its config directory `quicraft/pins-v2.json`,
    schema version 1: entries contain normalized logical host (lowercase IDNA ASCII, or
    canonical literal IP), original logical TCP port, lowercase `sha256:` hex fingerprint,
    first-bound and last-bound UTC timestamps. Do not key pins by SRV target or transient
    resolved IP. Write via temporary file and atomic replacement with owner-only permissions
    where supported. An authenticated pin means only "the last endpoint at this name that
    completed an authenticated login", not proven server identity (12.20). v1 silent
    observations MUST NOT be promoted to authenticated pins.
    **Pin expiry (user decision 2026-10-16).** An authenticated pin whose last-bound timestamp
    is more than **30 days** old expires. Before any pin lookup or store-full decision the
    client removes expired pins, with their re-bind backoff and detailed-notice entries,
    in one atomic write (12.16's Bloom filter bits stay). An expired pin is first contact:
    the next join runs the normal binding exchange, with no changed-fingerprint notice and
    no re-bind backoff. This is safe because every `/3` join runs the full binding check
    whether or not a pin exists (12.18); a pin only adds change notices and re-bind rate
    limits, never permission to skip binding. A wall clock moved backwards never revives or
    extends a pin; a last-bound time in the future counts as expired. Never expire a pin
    during an active attempt for that logical server.
    A corrupt/unreadable existing pin file or failed write disables `/3` until repaired;
    notify the player and use `/2` (for a `/2` advertisement) or TCP on the next attempt, never
    silently treat corruption as first contact. A pin write failure at activation closes the
    current connection with `0x10`; do not attempt a live transport downgrade. 12.14's
    join-time TCP retry then applies, after closing QUIC.
16. **12.16 Changed fingerprint.** With an existing authenticated pin (online mode, 12.15), a
    different advertised fingerprint F' MUST NOT be trusted from the ping. The client
    bypasses (but retains) tickets, tokens and recorded flights for that logical server,
    tells the player the fingerprint changed (a notice naming old and new fingerprints,
    not a prompt, at most once per logical server/new fingerprint), and
    makes ONE automatic re-binding attempt: full handshake, no early data, `/3`, the
    binding exchange of 12.4-12.12 with `F = F'` in `T`. The pin is replaced only after
    SERVER_SWITCH verifies (12.15); the old pin stays until then. **Pre-connect precedence:**
    the re-bind needs `/3`, so if a 12.3 prerequisite is missing before any QUIC connection
    is created (no exporter capability, no supported routing table, or an absent, unknown or
    mismatched advertised routing digest under 13.1), the client neither selects `/2` nor
    trusts F': it leaves the pin unchanged, records §6's failure-cache entry and re-bind
    backoff, and joins over fresh vanilla TCP directly (`quic-only` fails), never `/2`;
    `0x10` is sent only when a `/3` connection exists. Any failure after connection creation (binding,
    exporter error, timeout, write failure) closes with `0x10`, leaves the pin
    unchanged, records §6's failure-cache entry, and uses 12.14's join-time TCP retry:
    close the failed QUIC connection first with `0x10`, then start a fresh vanilla TCP join
    with a new Login Start and a new Mojang session join (when online). Never transfer old
    login state or `S`. `quic-only` instead fails. Wait for OFFER at most twice the measured QUIC handshake RTT,
    clamped to 500 ms..3 s, from entering configuration (use the transport's measured RTT
    at handshake confirmation); this replaces 12.14's 10-second
    missing-OFFER wait for re-binding only. The total activation deadline remains 10 seconds.
    At most one automatic attempt per persisted re-bind backoff window: use §6's exponential
    schedule but key it exactly like the pin (normalized logical host, logical port),
    ignoring fingerprint so rotating fingerprints cannot reset it. During backoff use TCP.
    Replace the pin and discard the logical server's old tickets/tokens/recorded flights
    only after binding and atomic pin persistence succeed; failure retains them unused.
    Persist re-bind backoff and notice-deduplication state across restarts. Bound pins and
    backoff to 4096 logical-server entries each, and detailed notices to 16 fingerprints
    per server (65536 total). Apart from 30-day expiry (12.15), never automatically evict
    pins: when the pin store is full after removing expired pins, use TCP for new names
    until a pin expires or the player explicitly deletes one. For backoff and
    detailed notices, evict least-recently-used entries when full. Never evict an active
    attempt's state; if no entry can be evicted, use TCP without a re-bind. To preserve
    at-most-once notices after detailed-history eviction, also persist a fixed 1 MiB
    monotone Bloom filter of (normalized logical host, logical port, new fingerprint).
    Before showing a notice, atomically mark that tuple in the filter; if already marked,
    suppress it. Never automatically clear filter bits; false positives may suppress a
    notice but cannot repeat one. If this state cannot be read/written reliably, suppress
    notices and use TCP, rather than risk repeated prompts or reset backoff. Include the
    filter and detailed state in the atomic persistence discipline of 12.15.
    If login now lacks authentication (including encrypted-but-unauthenticated login),
    abort `/3` as in 12.7, keep the old pin and use the same fresh TCP retry, with any notice
    subject to the same deduplication;
    no mode-0 exchange is allowed for this re-bind. Deleting a pin before it expires still
    requires explicit player confirmation. Offline-mode pins
    (Phase 9 TOFU) are out of scope here and keep an explicit-action rotation.

### Resumption, offline mode and threat model

17. **12.17 Resumption.** Partition tickets, tokens and recorded flights by logical server,
    resolved endpoint, fingerprint and ALPN; never reuse `/2` material for `/3`. On a resumed
    `/3` connection retain the ticket's original `F` and require fresh Minecraft login,
    fresh `S`, and the current connection's normal exporter. Binding authenticates possession
    of that Minecraft secret on the same resumed TLS connection, anchored to the certificate
    associated with the ticket; it does not prove a fresh certificate was presented. Missing
    ticket provenance requires a full handshake. Reject stale/changed-pin tickets before use.
    Require TLS 1.3 resumption with `psk_dhe_ke` only; disable PSK-only `psk_ke` so resumed
    connections retain forward secrecy. With the pinned BoringSSL this holds without
    configuration: it offers and accepts only `psk_dhe_ke`, and there is no knob to change it.
    It is an invariant to re-check on every BoringSSL bump, not a setting. Rotate ticket keys with the certificate, invalidating
    old tickets; otherwise honest resumed bindings can fail against the new certificate
    fingerprint (an availability failure, not permission to bypass binding).
18. **12.18 Early data and replay.** `/3` permits only the preamble, Minecraft handshake and
    Login Start as early bytes, with §8's single-use tokens, duplicate removal, confirmation
    deadline and abandoned-login handling unchanged. No binding payload, encryption response,
    configuration/play packet, auxiliary stream, pin update or cipher transition may occur
    before handshake confirmation. Replayed early data may cause the bounded login effects
    in §8, not activation. Old binding messages fail under fresh `S` and `E`; never cache a
    successful binding as permission to skip the next check.
19. **12.19 Offline mode.** Offline login has no Mojang-authenticated `S`: do not derive `K`,
    claim binding, or create/update authenticated pins. Minecraft's cipher is already off,
    exactly as vanilla; no cipher is being dropped. Mode 0 is legal only if no Minecraft
    encryption occurred at all; encrypted-but-unauthenticated sessions fail with `0x10`,
    never mode 0. The client no-authentication abort in 12.7 applies in production; the
    explicit offline test flag alone may allow an unencrypted login to reach this exchange,
    and never overrides 12.16's existing-pin handling. For explicit offline `/3` testing,
    use the same five-message exchange with mode 0 and all-zero tags, still enforcing TLS
    confirmation, metadata, state, deadlines and byte boundaries. It negotiates framing only,
    not identity. Never accept mode 0 for a connection authenticated in online mode, or as a
    fallback after online binding fails. Offline TOFU and change warnings remain Phase 9.
20. **12.20 Security claims and exclusions.** Test a transparent relay (same TLS channel,
    check passes), a fingerprint-swapping TLS terminator (different exporters, check fails
    before cipher-off), reflected/modified/replayed messages (fail), and honest resumed
    sessions (fresh check passes). Passive observers see only QUIC ciphertext after TLS;
    a TLS terminator must not obtain post-binding game plaintext. This does not add integrity
    to pre-binding CFB8, conceal Login Start from a TLS terminator, stop advertisement stripping,
    UDP blocking, replay-induced login DoS, a malicious authenticated server, compromised
    endpoints/session services, or stolen Minecraft/TLS secrets. Offline first contact remains
    vulnerable to active MITM. No WebPKI validation, per-stream CFB8, exporter-derived Minecraft
    secret, TCP-first authentication or live TCP-to-QUIC handover is introduced.
    Binding authenticates the Minecraft login's co-location with this TLS endpoint, not
    ownership of the logical server name. Pins have only 12.15's last-authenticated-endpoint
    meaning. A cluster with per-node certificates may therefore show repeated fingerprint
    notices as nodes alternate; those notices are informational only, not identity verdicts.

## 13. Multi-stream (Phase 5)

**Draft v2. Design approved by the user 2026-10-15 (open questions 1-4 as written below); implementation may proceed behind a disabled-by-default flag. Security review is required before any release or enablement.**

### Direction (2026-10-16): planned, not implemented

This subsection records the user's direction for the next revision of `/3`. None of it is implemented yet. Sections 13.1 to 13.17 still describe the current draft. The revision is made in place, because `/3` has never shipped enabled.

1. **Large packets first.** The main goal of multi-stream is to stop large packets (chunk data) from causing head-of-line blocking. The current draft (13.8) keeps chunk installs on the ordered CONTROL stream, which still blocks everything queued behind a large chunk. The planned revision moves chunk data onto N streams sharded by chunk position, with scoped fences, so only the updates that depend on a chunk wait for it. Small-packet lanes come as a second step.
2. **Send priority.** When several packets are ready, send in this order: (a) latency-sensitive packets that shape how multiplayer feels, such as entity movement and updates; (b) large packets that can block others, such as chunks; (c) other small packets. Chunk loading must not be starved by (a).
3. **Streams versus CPU.** Carry as many packet types as possible on parallel streams, while keeping server CPU per player in check. CPU per player is measured as streams are added (see `docs/benchmarks.md`, "Planned Phase 5 measurements").
4. **Chunk stream count.** Negotiated per connection and benchmarked from 1 to 6 streams. The server never exceeds a hard maximum, whatever its settings say.
5. **Server profiles (planned).** Built-in profiles keyed on the server's hardware: memory given to Java and CPU cores it may use. Each profile sets how many players may connect over `/3` and the maximum number of parallel streams. Every profile value is pushed to the highest the hardware sustains, measured with all of the profile's values running together (they share CPU and memory), taken from `docs/benchmarks.md` runs, not a cautious guess. Custom values are range-checked and checked against each other, and are applied all at once or not at all. Admins may create a custom profile with `/quicraft value set <VARIABLE> <int>` and restore with `/quicraft value restore default` or `/quicraft value restore <profile>`. The first time an admin changes a value, the command warns that changing these values is risky and asks them to proceed with caution. The hard stream cap in item 4 still applies to custom values.

### Negotiation and stream map

1. **13.1 Version selection.** Keep the advertisement object version `v:1`; a capable,
   explicitly enabled server advertises `alpn:"quicraft/3"`. **Such a listener accepts only
   `/3`** (user decision 2026-10-16): quiche applies one set of transport parameters per
   listener, and `/2`'s MUST stay unchanged (12.1), so there is no dual `/2`+`/3` listener. A
   server with `/3` disabled keeps the shipped `/2` listener unchanged. A client offers the
   advertised ALPN only. Existing clients that do not recognize `/3`, and new clients that
   cannot do `/3`, use TCP; new clients seeing `/2` use unchanged v1. `/2` is expected to be
   deprecated once `/3` covers every platform native, supported game version and Velocity. A failed `/3`
   handshake races/falls back under §5, never sends a v2 preamble to a `/2` peer, and never
   alters the TCP listener. No feature is inferred from a plugin payload on `/2`.
   Add optional advertisement field `routing` containing an object mapping decimal unsigned
   protocol-number strings to lowercase `sha256:` hex routing-resource digests (13.7).
   `routing` holds at most 64 entries. Each key is a decimal unsigned protocol number with no
   sign and no leading zero except `"0"`, at most 10 digits, fitting a signed 32-bit int; each
   value is exactly `sha256:` followed by 64 lowercase hex digits. Any malformed entry, and more
   than 64 entries, discards the whole member (all-or-nothing), never the rest of the
   advertisement; a client treats a discarded member as absent, which can only select TCP. A server whose response would exceed §1's cap with the member omits the routing object
   first and still advertises.
   Vanilla ignores it; obey §1's response-size cap. Before connecting, clients require a
   matching digest for their protocol to select `/3`; absent/unknown/mismatched entries
   select TCP (a changed pin likewise uses fresh TCP, 12.16). Never guess a routing table.
   OFFER's digest comparison remains authoritative even after an advertisement match.
2. **13.2 Preamble.** Under `/3` only, stream 0 begins in each direction with exactly
   `VarInt(length) || u8(version=2) || u8(token_length) || token`; token length is 0 or 16,
   length exactly 2 or 18. Retain §8's token semantics. Unknown version/length is `0x1`.
   Following bytes remain the ordinary single-stream Minecraft login/configuration format
   until 12.11/12.12, then become records. The existing v1 `Preamble`/`PreambleStream` are
   not extensibility negotiation; `/3` requires a separate version-aware parser and cutover.
3. **13.3 Stream identities.** Exactly three bidirectional QUIC streams are allowed:
   stream 0 CONTROL (client opens, both directions), stream 4 REALTIME (client opens,
   both directions), stream 1 WORLD (server opens, both directions). The latter two open
   only after the opener activates under §12. This explicitly lifts v1's ban on server-opened
   streams for `/3` only. No unidirectional streams, datagrams, replacement streams or other
   ids are permitted. WORLD and REALTIME each start in each direction with
   `u8(version=2) || u8(type)` where type is 1 REALTIME or 2 WORLD; CONTROL has no second
   preamble. Only the client may buffer early stream-1 bytes until it validates
   SERVER_SWITCH, under 13.12's bounds, without delivery. The server MUST treat any
   stream-4 frame before its own activation (CLIENT_SWITCH validated and SERVER_SWITCH
   queued) as fatal `0x11`, not buffer it. Stream 4 cannot legitimately precede that point.
   Adapter note: the server's activation must take effect on the connection's event loop
   before, or in the same task as, the flush of SERVER_SWITCH. Activating from another thread
   only queues it, and the client's stream 4 can then arrive first, which is fatal.

### Records, control and routing data

4. **13.4 Record format.** Each activated stream direction carries repeated
   `VarInt(record_length) || u8(kind) || u64(sequence) || u64(dep_control) ||
   u64(dep_realtime) || u64(dep_world) || body`. Length excludes its own VarInt and
   includes the 33-byte fixed header. Kind 0 is one GAME packet; body is exactly one
   Minecraft VarInt-length frame including packet id and the negotiated compression wrapper,
   without CFB8. Kind 1 is END, CONTROL only, with empty body. Reject unknown kinds,
   trailing bytes, invalid inner lengths or `record_length > 33 + 3 + 2097151`
   (2097187 bytes): 33 header bytes plus at most a three-byte Minecraft length prefix
   and 2097151 bytes of framed packet content. Validate this before allocating the body.
   Sequence starts at 1 independently per stream and direction and increments by exactly
   one per record, including END; zero dependencies mean no predecessor. No integer wraps.
5. **13.5 CONTROL semantics.** State transitions (including Finish Configuration and
   acknowledgements), registry/dimension changes, compression changes, object create/destroy,
   teleport/respawn, disconnects, and unknown/custom packets MUST use CONTROL. CONTROL is
   ordered and is a full fence: a control record depends on every record previously assigned
   in that sending direction on all lanes; every subsequent non-control record depends on
   that control record. Receivers apply state changes only at delivery, before decoding
   subsequent dependent packet bodies. Reconfiguration retains the streams and sequences,
   drains play through a control fence and uses CONTROL exclusively until play resumes.
6. **13.6 Routing algorithm.** At the sender's original game packet ordering point, assign
   sequences and dependencies before parallel encoding or scheduling. Lookup is by exact
   Minecraft protocol number, protocol state, direction and numeric packet id. Missing entries
   default to CONTROL, never an inferred category. The receiver checks the selected lane
   against the same table after dependencies permit decoding; wrong-lane packets disconnect.
   Unknown protocol numbers disable `/3` before connecting. Check advertised digests under
   13.1 before choosing `/3`; unequal digests in OFFER still fail activation under 12.14
   before entering play, never use a guessed routing table. A post-login routing mismatch
   therefore disconnects only when a previously matching advertisement was stale (including
   a changed or falsified advertisement), not as normal compatibility negotiation.
   Runtime packet-id remappers/modded registries require an explicitly
   matching reviewed table or advertise `/2` only.
7. **13.7 Data resource.** Store routing as UTF-8 JSON data, one resource per protocol number,
   with `schema:1`, `protocol:<number>`, `default:"control"`, and `routes` entries containing
   `state`, `direction` (`clientbound`/`serverbound`), `packet_id` (nonnegative integer),
   `lane` (`control`/`realtime`/`world`) and `dependency` (`fence`/`lane`). Reject duplicates,
   unknown fields and unsupported schema. Require `fence` for CONTROL and `lane` otherwise;
   every non-control entry needs ordering tests. `R` is SHA-256 of the exact resource bytes,
   including whitespace, shipped identically to both peers; no runtime normalization. The
   following is a synthetic schema example, NOT a mapping for any released Minecraft version:

   ```json
   {"schema":1,"protocol":12345,"default":"control","routes":[
     {"state":"play","direction":"clientbound","packet_id":10,"lane":"control","dependency":"fence"},
     {"state":"play","direction":"clientbound","packet_id":11,"lane":"realtime","dependency":"lane"},
     {"state":"play","direction":"clientbound","packet_id":12,"lane":"world","dependency":"lane"}
   ]}
   ```

   The shipped 26.1.2 table (`protocol-775.json`, protocol 775) routes clientbound play packets only:
   entity movement, metadata, animation and motion, plus level particles, to REALTIME, and block, light, block-entity and
   biome updates to WORLD. Everything else, including every serverbound packet, stays CONTROL. Its
   packet ids are pinned to the live game by `RoutingTableGameTest`, and each route's ordering by
   `ShippedRoutingOrderingTest`.

8. **13.8 Explicit game invariants.** Entity spawn/create and destroy are CONTROL fences;
   entity movement/metadata may be REALTIME only after the corresponding spawn is delivered,
   and destroy must wait for all prior updates before id reuse. Chunk install, unload and
   world reset are CONTROL fences. Block, light and block-entity updates for that chunk
   MUST share WORLD in original order, or remain CONTROL; they depend on the install fence.
   No update may pass its chunk
   install or reference an entity after its destroy. Dimension/registry changes fence all
   affected lanes. Where a packet's dependency cannot be demonstrated from these rules,
   route it to CONTROL. Stream classification is not permission to omit a predecessor.
   The planned revision (see "Direction (2026-10-16)" above) moves chunk install off CONTROL
   onto sharded chunk streams; these fence rules then apply to that lane instead.

### Sequence barriers, bounds and delivery

9. **13.9 Dependency vector.** For each sending direction maintain last-assigned sequences
   `A[control,realtime,world]`. A non-control record depends on the latest CONTROL and its
   own lane predecessor; other components default to zero. A CONTROL record depends on
   the whole previous `A`. The sender MUST additionally include any cross-lane semantic
   predecessor required by 13.8, taking componentwise maxima. Only already assigned records
   may be referenced; a record's own-lane dependency is exactly `sequence-1`. These rules
   make dependencies acyclic. Initial records have zero predecessors as appropriate.
10. **13.10 Receiver algorithm.** Maintain contiguous delivered watermarks `D` per direction.
    Parse envelopes without decoding game bodies; queue each in lane order. Deliver only a
    lane's head record, only when every dependency is <= the corresponding `D`, and only
    after the game's serial packet handler has accepted/applied it in order (not merely
    when bytes arrive or decoding completes). Then advance that lane's `D` and retry heads.
    Serialize delivery on the game connection executor; preserve the vanilla main-thread
    dispatch order for each dependency. No cross-direction dependency is implied: request/
    response semantics still use the game's application acknowledgements. A record with a
    future own-lane dependency, skipped sequence or duplicate is a protocol error.
11. **13.11 Barrier example.** CONTROL spawn sequence 5 followed by REALTIME move sequence 9
    carries `dep_control=5`, `dep_realtime=8`. If move arrives first, hold it until spawn 5
    and move 8 have been delivered. A subsequent CONTROL destroy sequence 6 depends on
    REALTIME 9 (and every prior WORLD record), so cannot overtake that move. CONTROL chunk
    install 7 followed by WORLD block update 8 carries `dep_control=7`; the update waits
    for the install even if their bytes arrive out of order. Fault tests
    MUST withhold each predecessor independently and observe these delivery orders.
12. **13.12 Memory and timeout.** Per connection, cap pending received records (including
    partial records, pre-activation auxiliary bytes and decoded-but-undelivered packets)
    at 1024 records or 16 MiB total wire/retained decoded bytes, whichever comes first.
    Cap each decompressed packet at 8 MiB or the game's stricter limit. Count parser buffers,
    staged bundles and decompression output in these budgets, not just complete envelopes.
    Reserve a further 1 MiB receive budget for CONTROL so blocked auxiliary queues cannot
    prevent reading small control predecessors; aggregate cap is 17 MiB. Within the 1024
    pending-record cap, reserve 64 slots exclusively for CONTROL (at most 960 auxiliary
    records, including partial/pre-activation records). A predecessor
    larger than the remaining budget disconnects rather than evicting buffered data.
    Stop granting auxiliary credit
    before exceeding its budget; if data already received cannot fit, close, never evict
    a predecessor. Also cap unsent application queues at 1024 packets or 16 MiB and apply
    backpressure; an unpausable producer over the cap disconnects. A head record blocked on
    a dependency, incomplete record, or unapplied handler for 10 seconds disconnects with
    `0x11` (ordering/resource failure). A lane that holds the auxiliary budget while its head waits for a
    predecessor on the other auxiliary lane (cross-lane starvation) is resolved by this same
    10 second blocked-head disconnect. Cycles, impossible references, limit violations and
    parser/ordering errors use the same code; do not skip packets or release a barrier on
    timeout. Start the incomplete-record timer at its first byte and the blocked-head timer
    when it becomes head; progress on other lanes does not restart either deadline. This
    deliberately favors disconnect over an undetected game desync.
    These are ceilings, not eager allocations. Each listener/node MUST also enforce a
    configured aggregate pending-bytes budget across connections, counting all retained
    buffers/decoded data above, including the unparsed transport chunks held before a
    record claims them. The read chunk a lane has taken but not parsed, at most one
    `READ_CHUNK` per lane, counts toward that node budget and not toward the per-connection
    ceilings above. Reserve each admitted connection's initial receive-credit
    exposure against that budget, and refuse new QUIC admissions when the reservation
    cannot fit (leave TCP untouched). Debit further credit/allocations before granting
    them; release reservations only when credit exposure or retained bytes are gone.
    No allocation or advertised receive credit may overcommit this node budget. Record
    the configured budget and admission behavior in the 13.17 scale benchmark.

### Flow control, resets, bundles and compression

13. **13.13 Transport limits.** For clientbound traffic `/3` sets `initial_max_data=16 MiB`
    and both bidi per-stream initial limits to 8 KiB (grow up to 4 MiB only against budgets).
    For serverbound traffic use small windows: the server advertises
    `initial_max_data=24 KiB` (three streams times 8 KiB) and both bidi per-stream initial limits of 8 KiB.
    These windows may grow only against the budgets below, including while
    receiving a legal record larger than its initial window. Set
    `initial_max_streams_bidi=2` at the server (client streams 0 and 4),
    `=1` at the client (server stream 1), and both uni limits to 0. Never increase MAX_STREAMS;
    grow MAX_DATA/MAX_STREAM_DATA only against uncommitted bounded receive budget, reserving
    both CONTROL byte capacity and its 64 record slots as in 13.12, and reclaim that budget
    after delivery. When granting auxiliary credit, account for outstanding credit as
    well as retained bytes and both reservations; if record count is not yet known, reserve
    for the maximum number of minimum-size records that credit can carry, or withhold it.
    Never extend credit for a record exceeding 13.4's cap; no legal record exceeds 4 MiB.
    Pin quiche's maximum auto-tuned windows. Server: connection window 24 KiB and stream
    window 8 KiB, equal to the initial serverbound windows, so automatic growth is off and the
    exposure is exactly the initial credit the node reserved. The server's connection window equals
    the sum of its three pinned stream windows, the most quiche can hold unread, so each admitted
    connection reserves 24 KiB against the 13.12 node budget. That reservation covers only unread
    serverbound stream data; 0-RTT early data and crypto/handshake buffers are not counted in it.
    Client: stream window 4 MiB and connection window 12 MiB, three streams times the
    stream maximum, since only three streams can hold quiche-unread data. The client's aggregate exposure is therefore the application-held
    data (the 17 MiB budget of 13.12) plus quiche-unread data bounded by 12 MiB; the initial
    clientbound MAX_DATA credit is not debited from the receiver budget. Automatic growth beyond
    these pins is not allowed. Because the server windows are pinned, a serverbound record
    streams at about 8 KiB per round trip per stream: a serverbound record larger than roughly
    8 KiB x (10 s / RTT) hits the 10 second incomplete-record timer (about 800 KB at a 100 ms
    RTT). Vanilla serverbound packets are far smaller. Smaller initial serverbound windows MUST remain effective;
    transport auto-tuning may not silently override them. If the pinned Netty exposes no
    setters for these maximum windows/growth controls, patch it before enabling `/3`;
    initial limits alone are insufficient. Account for already granted credit when
    tightening limits; never promise credit that cannot be honored within the budgets.
    All other §9 values remain unchanged. Prioritize available
    CONTROL writes, then round-robin REALTIME and WORLD in at most 64 KiB write quanta;
    priority must not starve a WORLD predecessor needed by CONTROL. Measure memory and
    throughput before changing these draft limits, including at the 50,000-player target.
14. **13.14 Reset and shutdown.** RESET_STREAM, STOP_SENDING, unexpected FIN on any lane,
    failure to open an allowed stream within 10 seconds of activation, or an extra stream
    terminates the whole connection with `0x11`; a STOP_SENDING must be detected even on a
    lane that never writes; never reopen just one lane or remap lost
    packets to CONTROL. Normal close sends a CONTROL disconnect if applicable, then END
    as a full fence after all queued game records. On END delivery the peer closes with
    `0x0`; sender waits at most 10 seconds before closing. A connection close is always
    terminal. TCP retry is a new player join, never mid-session recovery.
15. **13.15 Bundles.** Treat a vanilla bundle (opening delimiter, all members, closing
    delimiter) as one CONTROL-fenced scheduling unit, keeping its actual packets as separate
    sequential GAME records, all on CONTROL, with no other game record assigned between
    them. Buffer before assigning routes, at most 4096 members or 8 MiB total uncompressed
    bytes, further limited by the game's own cap and by 13.12's pending-record cap: the
    whole bundle, both delimiters included, must fit in 1024 records, so at most 1022
    members. A sender that would exceed any of these bounds closes with `0x11` instead of
    emitting the bundle, assigning no sequence numbers for it; missing closing delimiter
    for 10 seconds or excess bounds at the receiver disconnects. Do not split members
    onto auxiliary lanes or deliver a partial bundle to game logic. Receiver stages
    contiguous CONTROL members through the
    closing delimiter using provisional watermarks for their internal predecessor checks;
    external dependencies must already satisfy the committed watermarks. It commits those
    watermarks only after delivering the whole validated bundle atomically to the game's
    bundle handler. Auxiliary records may reference sequences of staged CONTROL members
    as required by 13.9, but may not satisfy those dependencies using provisional
    watermarks; only committed delivery releases them. Reserve/validate
    the complete bundle against queue bounds before scheduling it; never interleave an
    activation, compression change or state transition with a bundle. Such a member makes
    the bundle invalid rather than changing decoder state partway through it.
16. **13.16 Compression.** Compression remains per Minecraft packet, not a shared compressed
    stream. Threshold changes are CONTROL fences; encode the change packet using the old
    threshold, subsequent packets using the new one. Receiver applies the new threshold
    after delivering that change and before decoding later dependent GAME bodies. Do not
    decompress a blocked packet using the current threshold speculatively. Keep ordinary
    Minecraft malformed-compression and size checks; no dictionary spans streams.
17. **13.17 Acceptance tests.** Before enablement require golden HKDF/exporter/message/record
    vectors; wrong/absent exporter and upstream-native tests; all MAC/state/timeout failures;
    switch packets split at every byte and coalesced with following plaintext; changed pins,
    rotation and corrupt storage; full/resumed/accepted and rejected early-data sessions;
    offline/online confusion; v1/new-client/new-server/TCP compatibility; resets on every
    stream; exhausted credit/buffers; delayed spawn/chunk/fences, bundles and compression
    changes. Include post-cutover profile-key/chat-signature validation and tab-list parity
    against vanilla with `isEncrypted()` still true; encrypted-but-unauthenticated aborts
    before configuration; pre-activation stream-4 rejection; boundary/max-length records;
    advertised digest mismatch before connect and stale-advertisement OFFER rejection;
    staged-bundle auxiliary dependencies released only on commit; and CONTROL byte/slot
    reservations under auxiliary floods, including quiche auto-tuning and node-budget
    exhaustion. Verify out-of-u32 protocol rejection, exporter exceptions/wrong lengths,
    PSK-only resumption rejection, ticket-key/certificate rotation, inert binding payloads
    outside `/3` and Velocity's bidirectional stripping. Test failed re-bind close-before-
    fresh-TCP ordering, new Login Start/session join, bounded OFFER wait, unchanged pin and
    retained (unused) tickets/tokens/recorded flights on failure, failure-cache and re-bind
    backoff updated on failure (asserted separately), success-only discard of tickets/tokens/
    flights, changed fingerprint with unavailable exporter or missing/mismatched routing
    digest selecting fresh TCP without `/2` or `0x10`, restart-persisted fingerprint-independent
    backoff, notice deduplication/eviction, the join-time TCP retry (close before fresh TCP, log and
    chat message, failure-cache entry) for every pre-activation failure kind, no retry after
    activation, and, with 0-RTT accepted on `/3`, neither side starting binding before the
    server's TLS handshake completes (12.7).
    Run a 50,000-concurrent-QUIC-connection node benchmark before enablement, recording
    configured node budget, admission reservations/refusals, retained and native memory,
    peak pending bytes/records, CONTROL progress, latency and throughput under auxiliary
    pressure; show the budgets hold without relying on TCP for the target QUIC population.
    Run full-play desync assertions under every testkit netem profile. Phase 5
    requires measured loss improvement over TCP AND single-stream and security review;
    this draft makes no performance or completed-review claim.

### DECIDED QUESTIONS (user, 2026-10-15: all four approved as written)

1. Approve `/3`, the configuration-stage four-proof/switch exchange after OFFER, and the
   conservative exporter gate even for offline multi-stream tests?
2. Approve changed-pin handling as: online mode re-binds automatically through channel
   binding (pin replaced only after a passing check, player notified), failure falls back to
   TCP with the pin unchanged?
3. Approve the three fixed lanes, conservative full CONTROL fences, and exact-byte JSON
   routing digests as the first reviewable design?
4. Approve the proposed memory/packet limits and 10-second fail-closed deadlines for
   benchmarking, particularly their per-node cost at 50,000 concurrent players?
