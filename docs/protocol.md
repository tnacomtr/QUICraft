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
5. A QUIC loss or failure is recorded in the failure cache (§6), except `/3` resource aborts
   under 12.14. Losing to TCP after the head
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

- Key: (resolved IP, QUIC port, advertised fingerprint). `/3` additionally enforces the
  fingerprint-independent logical-server join-retry limiter in 12.14; rotating addresses,
  advertised ports or fingerprints cannot reset that limiter. Resource aborts (`0x12`)
  get only the failure-cache exceptions specified in 12.14; unauthenticated peer claims
  exceeding its allowance follow normal backoff.
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

**Draft v2 with 2026-10-16 shard-count transcript revision (12.7-12.9). The original binding design was approved 2026-10-15; the revised wire draft remains disabled by default and requires security review before release or enablement.**

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
   Advertise/offer `/3` only for online mode, except when both test endpoints explicitly set
   `-Dquicraft.multistream.offlineTest=true` (default false). That flag logs a prominent WARN
   at startup and every offline `/3` activation: test-only, unauthenticated, not for production.

### Cryptographic inputs and messages

4. **12.4 Login prerequisite.** Keep the vanilla RSA exchange, 16-byte Minecraft shared
   secret `S`, AES/CFB8, Mojang hash inputs and session authentication unchanged. Begin binding
   only in configuration state, after successful online authentication and TLS handshake
   confirmation on both ends. OFFER MUST be the first server message in configuration;
   hold all ordinary configuration messages, including Finish Configuration, until activation.
   The server terminating Minecraft login and the QUIC endpoint must be the same trusted
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
   `T = SHA256("quicraft/3 binding v2" || u32(protocol_number) || u8(mode) || F || R || u8(N))`.
   `N` is the selected chunk shard count (13.2); it is authenticated with the routing digest.
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
   u32(protocol_number) || F[32] || R[32] || u8(N) || tag[32]` (104 bytes). There are no optional
   fields or trailing bytes. `kind`: 0 OFFER (server), 1 CLIENT_PROOF, 2 SERVER_PROOF,
   3 CLIENT_SWITCH, 4 SERVER_SWITCH. OFFER has an all-zero tag; every other online tag is
   `HMAC-SHA256(K, "quicraft binding v2 message" || u8(kind) || T || E)`.
   Distinct kinds provide direction and phase separation. No bare tag is a valid packet.
   `protocol_number` MUST be in u31 range (0..2147483647), encoded in the existing four-byte
   u32 field with its high bit zero; this same range applies to routing resources, lookup,
   advertisements and all messages. Reject values outside
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
   in constant time after exact-length validation. Every message echoes the same selected
   `N`, in the client's preamble range and 1..6; a mismatch fails activation with `0x10`.
   Unknown versions/kinds, duplicates,
   reflection, mismatched metadata, unsolicited payloads or invalid MACs terminate `/3`.

### Exact cipher and framing cutover

10. **12.10 Quiescence.** Before sending OFFER, the server drains earlier login writes,
    never sends ordinary configuration packets ahead of OFFER, and holds configuration
    producers at the state boundary. On receiving OFFER the client drains its earlier writes
    before CLIENT_PROOF. Thereafter both pause ordinary outbound game packets and state
    changes. Client queue: at most 256 packets or 8 MiB. Server queue: at most 32 packets or
    64 KiB, charged before enqueueing to the isolated outbound node pool in 13.12, not its
    protected inbound shares; these are protocol queue caps, not game packet-size claims.
     Prefer producer backpressure over accumulating configuration data. On a refused server
     binding-queue charge, first shed outbound pressure victims under 13.12 and atomically
     retry the charge from the reclaimed capacity before serving new charges. Backpressure
     the producer without enqueueing uncharged bytes while waiting; retry until capacity is
     available or 10 seconds of local monotonic transport time elapse from first refusal,
     whichever comes first. The existing activation deadline still wins if earlier. Queue
     overflow, an unpausable producer, or expiry while resource-blocked closes with `0x12`
     (resource abort), not a binding-validation failure or a failure-cache entry (12.14).
     Never take another connection's inbound share. Reserving the full binding queue per
     idle connection would require at least 65536 additional payload bytes, exceeding the
     entire 24576-byte idle receive target even before packet metadata; shed-then-retry
     preserves that target without imposing an idle outbound reservation.
     Binding packets alone may bypass that queue. Earlier inbound game
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

14. **12.14 Fail closed.** Allow 10 seconds from OFFER to activation: the server starts its
    local monotonic clock when enqueueing OFFER, the client when receiving complete OFFER.
    Separately allow 10 seconds from configuration entry to receiving OFFER at the client
     (the shorter re-bind wait in 12.16 still applies). An ordinary configuration message
     received by the CLIENT before OFFER is immediately `0x10`, not permission to extend
     the missing-OFFER wait. The server MAY receive the client's legal early configuration
     writes (such as brand and client information) before OFFER/CLIENT_PROOF under 12.10.
     Any validation/exporter error, timeout or premature close closes
    the whole QUIC connection with application error `0x10` (binding/activation failure).
     Resource-caused closes instead use `0x12`, including expiry of an activation deadline
     while a local resource refusal prevents progress. Both peers recognize this application
      code, but locally detected resource aborts, authenticated peer resource reports (after
      SERVER_SWITCH verifies) and in-allowance pre-verification peer reports below MUST NOT
      create/advance §6's failure cache or the re-bind failure backoff, subject to the
      inbound peer-attribution rule in 13.12.
      Before SERVER_SWITCH verifies, a PEER-sent `0x12` cannot authenticate its resource
      claim. Apply the fixed 60-second resource window below to at most three such reports
      per rolling 10 minutes per logical server; the fourth and subsequent reports in that
      interval are treated like `0x10` for failure-cache/re-bind-backoff and retry limiting.
      Retain the last three report timestamps, including reports treated as `0x10`; decide
      from prior reports in the window before recording the current one. Persist them
      with the logical-host limiter below (also shared with 12.16);
      success, reconnects and endpoint/fingerprint changes do not reset them. This allowance
      never shortens an existing non-resource window. After verification a peer resource
      report also avoids failure-cache/re-bind-backoff updates. They still close fail-closed, log the resource reason,
     and use the same fresh join-time TCP retry and chat notice below. For non-resource
     failures, client records §6's failure-cache entry for the attempted address/port/fingerprint;
    activation failure overrides a previous handshake success and is cleared only by a
    later successful activation for that key. Server logs a reason code and rate-limits
    to one warning per minute per listener; client logs once per failed attempt. Logs contain
    no secrets, exporter values, tags or payload dumps. Do not retry cipher-off or continue
    half-switched. **Join-time TCP retry (user decision 2026-10-16).** A failure before
    activation completes (any of the above, a handshake or ALPN failure, or any exception in
     QUICraft code) closes QUIC first (with `0x10`, or `0x12` for resources, where a connection exists) and then starts a
    fresh vanilla TCP join in the same attempt: a new Login Start and, when online, a new
    Mojang session join. Never transfer old login state or `S`. `quic-only` fails instead.
    The client writes "QUIC multi-stream failed (<reason>), fell back to TCP" to its log and
    shows it in chat once the TCP join reaches play; `<reason>` is a short human-readable
     cause and never contains secrets. Independently of §6's endpoint-failure cache, persist
     a join-retry limiter keyed by (normalized logical host, original logical TCP port),
     exactly as in 12.16, ignoring fingerprint, resolved IP and advertised QUIC port. Reserve
     one possible automatic retry before starting a `/3` join; successful activation releases
      it. A failed non-resource attempt consumes it and starts §6's exponential schedule for this limiter;
     during that window start joins directly over TCP (`quic-only` fails instead), without another QUIC login/session
      join. For a locally detected resource abort (or an authenticated peer report after
      SERVER_SWITCH, or a pre-verification report within the allowance above), instead use
      a fixed 60-second window: at most one QUIC attempt and
      its automatic TCP retry per minute per logical server, with NO doubling and no
      increase of any existing non-resource exponent. Resource aborts cannot extend a
      non-resource window beyond its original expiry. This bounds extra login attempts,
      not endpoint trust; it does not modify failure-cache entries or pins. No new wire
      message is needed: a short honest server-pressure episode avoids exponential TCP
      suppression, while unauthenticated claims buy only three fixed-window exceptions
      per 10 minutes, not unlimited exemption from normal backoff. These counts/windows
      are provisional measure-then-set retry policy. The first
      join after this short window attempts QUIC again; TCP is not a scale-capacity remedy.
      Use 12.16's 4096-entry bound, active-entry protection and atomic persistence;
     if limiter state cannot be read/written or safely reserved, choose TCP before QUIC login.
     Thus rotating fingerprints cannot multiply the extra Mojang `joinServer` calls.
     Rationale: the retry only reaches vanilla
    TCP with Minecraft's own encryption, which an attacker can already force by stripping the
    advertisement (§7); the security review MUST confirm this. A failure
    after activation (during play) is not retried: the disconnect screen shows the reason and
     the next join consults §6 (resource aborts do not add an entry). Pre-login racing and compatibility fallback remain §5
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
     exporter error, timeout, write failure) closes with `0x10` (resource aborts use `0x12`
      and the cache/backoff exceptions are exactly those in 12.14), leaves the pin unchanged,
      applies 12.14's local/authenticated/bounded-unauthenticated distinction
      to §6's failure cache and re-bind backoff, and uses 12.14's
     join-time TCP retry and logical-server limiter:
     close the failed QUIC connection first, then start a fresh vanilla TCP join
    with a new Login Start and a new Mojang session join (when online). Never transfer old
    login state or `S`. `quic-only` instead fails. Wait for OFFER at most twice the measured QUIC handshake RTT,
    clamped to 500 ms..3 s, from entering configuration (use the transport's measured RTT
    at handshake confirmation); this replaces 12.14's 10-second
    missing-OFFER wait for re-binding only. The OFFER-to-activation deadline remains 10 seconds.
     At most one automatic attempt per persisted non-resource re-bind backoff window: use §6's exponential
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
    If a pinned server advertises `/2`, a different fingerprint still triggers the same
    deduplicated changed-fingerprint notice. Re-binding cannot run on `/2`: retain the pin,
    use fresh TCP under the pre-connect precedence above, and record re-bind backoff.
    An unchanged fingerprint with a `/2` advertisement uses normal `/2` compatibility,
    with no changed-fingerprint notice and no authenticated-pin update.

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

**Revised draft, 2026-10-16. This replaces the unshipped three-lane `/3` draft in place; no old-draft compatibility is required. Implementation remains disabled by default. Security review is required before release or enablement.**

This is spec only. Current `core/multistream` still has the 33-byte three-lane record codec,
full CONTROL fences and shared auxiliary budget; `core/routing` still uses schema 1 without
position extraction. Those implementations must change together before this revision can run.

### Direction (2026-10-16): specified, not implemented

1. **Large packets first.** Chunk install, updates and forget share one of N position-sharded chunk streams (13.3, 13.8, 13.18). Scoped fences and chunk joins replace full fences on every CONTROL packet (13.5, 13.9, 13.19). Entity storage does not require chunk install before spawn.
2. **Send priority.** Multiplayer-feel packets first, chunks second, other small packets third, with chunk progress guaranteed by dependency-aware sender scheduling (13.20), not by QUIC priority alone.
3. **Streams versus CPU.** Routing remains reviewed data (13.7). Measure CPU per player as streams are added, rather than assuming more streams are free (see `docs/benchmarks.md`, "Planned Phase 5 measurements").
4. **Chunk stream count.** Negotiated per connection (13.2), benchmarked from 1 to 6, with a draft hard cap of 6 justified by dedicated receive reservations (13.12/13.13). Compact headers are in 13.4. Cap/default confirmation is listed below.
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
   sign and no leading zero except `"0"`, at most 10 digits, in 0..2147483647; each
   value is exactly `sha256:` followed by 64 lowercase hex digits. Any malformed entry, and more
   than 64 entries, discards the whole member (all-or-nothing), never the rest of the
   advertisement; a client treats a discarded member as absent, which can only select TCP. A server whose response would exceed §1's cap with the member omits the routing object
   first and still advertises.
   Vanilla ignores it; obey §1's response-size cap. Before connecting, clients require a
   matching digest for their protocol to select `/3`; absent/unknown/mismatched entries
   select TCP (a changed pin likewise uses fresh TCP, 12.16). Never guess a routing table.
   OFFER's digest comparison remains authoritative even after an advertisement match.
2. **13.2 Preamble and shard negotiation.** Under `/3` only, stream 0 starts in each direction
   with `VarInt(length) || u8(version=2) || u8(token_length) || token || u8(min_shards) ||
   u8(max_shards)`. Token length is 0 or 16, length exactly 4 or 20. Retain §8's token
   semantics; malformed version, length or range closes with `0x1`. The client offers
   `1 <= min_shards <= max_shards <= 6`; the server replies with its selected `N` in both
   range fields, within that offer and its configured maximum. An empty intersection fails the
   connection (`0x10`), never clamps silently outside the offer. The draft client offer is
   1..6; both the configured maximum and preference must be within 1..6.
   Select N by clamping the configured preference to the intersection of the client's range
   and 1..configured_max; the draft preference is 3. Selection is immutable for the
   connection and echoed/authenticated in §12's exchange.
   Hardware profiles are local policy, not wire fields; no administrator setting overrides 6.
   Following bytes use ordinary Minecraft login/configuration framing until §12 cutover.
   This preamble does not alter `/2` or its version-1 parser. Never negotiate through a game
   plugin message on `/2`.
3. **13.3 Stream identities.** There are exactly N+2 bidirectional streams: CONTROL on QUIC
   id 0 and REALTIME on id 4, both client-opened; CHUNK[i] on id `1+4*i`, server-opened,
   for `0 <= i < N`. Logical stream indices used in dependencies are CONTROL=0, REALTIME=1,
   CHUNK[i]=2+i (not QUIC ids). All serverbound packets use CONTROL. The reverse auxiliary
   REALTIME direction sends its preamble but no records. Reverse CHUNK[i] directions send
   no preamble and no records: their one byte of guard credit exists only to detect a
   violation. Neither auxiliary direction needs an application receive slot at the server.
   Auxiliary streams open only after the opener activates under §12; each used direction starts
   `u8(version=2) || u8(logical_stream_index)`. CONTROL has no second preamble. No other
   stream ids, replacements, unidirectional streams or datagrams are allowed.
   The client may buffer early server chunk-stream bytes before validating SERVER_SWITCH,
   within each stream's reservation (13.12), without parsing game bodies or delivering them.
   The server rejects pre-activation stream-4 bytes with `0x11`. Server activation must run
   on the connection event loop before, or in the same task as, flushing SERVER_SWITCH.

### Records, control and routing data

4. **13.4 Compact record format.** Each activated direction carries repeated
   `VarInt(record_length) || u8(kind) || VarInt(sequence) || VarInt(epoch) ||
   VarInt(dependency_count) || dependency_entries || body`. Each entry is
   `u8(logical_stream_index) || VarInt(required_sequence)`. Entries are strictly increasing
   by index, positive sequences, at most N+1 entries, and never name the record's own stream.
   Own-stream `sequence-1` is implicit. Omit zero components; do not encode duplicate indices.
   All new VarInts are minimal nonnegative signed-31-bit values (0..2147483647), at most
   five bytes; sequence starts at 1, increases exactly by one per stream/direction and never
   resets, including across epochs. Exhaustion closes, never wraps. Epoch is defined in 13.18.
   Kind 0 GAME has exactly one Minecraft length-prefixed frame, including id and negotiated
   compression wrapper, without CFB8. Kind 1 END has empty body and is CONTROL/global only.
   Kinds 2 ACK_JOIN and 3 BATCH_JOIN carry one ordinary GAME frame of the designated packet
   type (13.19), on a chunk stream only. Reject other kinds and trailing/invalid inner bytes.
   Length excludes its own prefix. Header maximum is 54 bytes (1+5+5+1+7*6);
   body maximum is 2097154 (three-byte length plus at most 2097151 content bytes).
   Reject `record_length > 2097208` before allocating. Including the outer prefix, no
   record exceeds 2097212 bytes. A low-sequence, low-epoch, zero-explicit-dependency record
   has a four-byte header, five bytes including a one-byte length; one small dependency
   adds two bytes. The ~5-byte target is not a constant-size or long-session claim.
5. **13.5 Scoped CONTROL semantics.** CONTROL stays ordered, but is not itself a fence kind.
   Each route selects one of these policies, always including its own stream predecessor:
   - **Global fence:** waits for all previously assigned records on all streams; all later
     records wait for it. Use for play login, respawn/level replacement, registry and protocol
     state changes (including start/finish configuration and acknowledgements), compression
     changes, disconnect, END, and unknown/custom packet ids on either endpoint.
   - **View gate:** waits for previous CONTROL, REALTIME and all chunk frontiers, plus the
     latest global fence. Later chunk records wait for it. This revised safe rule drains
     earlier installs before changing the view, including a center moved away and back. Use for
     chunk cache center, cache radius and simulation distance before admitting new chunks
     outside the old view. chunk_batch_start also uses this scope (13.19). A view gate is
     also a CONTROL fence for later REALTIME.
     This deliberately adds HOL at view changes, not at every CONTROL packet. Center
     updates are NOT assumed rare: along one axis at speed v blocks/second, one chunk
     crossing per 16 blocks can mean about v/16 center updates/second; diagonal travel can
     cross either axis, and teleports/radius changes add gates. These are geometric rates,
     not measured traffic counts. Measure actual gate frequency and movement/REALTIME
     stalls before enablement; no claim that this safe rule is free during normal movement.
   - **Control fence:** waits for previous CONTROL and REALTIME, plus the latest global
     fence. Later REALTIME waits for it; chunks do not. Use for entity spawn/remove and
     teleport relations, preserving spawn-before-move, move-before-remove/id reuse, and
     teleport-before-dependent movement.
   - **Ordinary CONTROL:** waits for its CONTROL predecessor and latest global fence only.
     Keepalive and reviewed unrelated control packets MUST NOT fence REALTIME.
   Reconfiguration globally drains play, retains streams/sequences, and uses CONTROL only
   until play resumes. Fences/gates take effect after game-handler execution (13.10), not
   receipt. Scope is per sending direction; game acknowledgements handle cross-direction
   transitions, never implicit references to reverse-stream sequence numbers.
6. **13.6 Routing algorithm.** At the sender's original game packet ordering point, assign
   sequences and dependencies before parallel encoding or scheduling. Lookup is by exact
   Minecraft protocol number, protocol state, direction and numeric packet id. Missing entries
   default to CONTROL/global, never an inferred category. The receiver checks route, kind,
   shard, epoch and required dependencies against the same table before game delivery;
   wrong-route packets disconnect with `0x11`. A known packet with a failing position
   extractor is malformed, not permission to fall back to another lane.
   Unknown protocol numbers disable `/3` before connecting. Check advertised digests under
   13.1 before choosing `/3`; unequal digests in OFFER still fail activation under 12.14
   before entering play, never use a guessed routing table. A post-login routing mismatch
   therefore disconnects only when a previously matching advertisement was stale (including
   a changed or falsified advertisement), not as normal compatibility negotiation.
   Runtime packet-id remappers/modded registries require an explicitly
   matching reviewed table or advertise `/2` only.
7. **13.7 Data resource.** Store routing as UTF-8 JSON, one resource per protocol number,
   with `schema:2`, `protocol:<number>`, `default:"global"`, `extractors`, and `routes`.
   Each route has `state`, `direction` (`clientbound`/`serverbound`), `packet_id`, `lane`
   (`control`/`realtime`/`chunk`), `dependency` (`global`/`view`/`control`/`ordinary`/
   `lane`/`ack_join`/`batch_join`), `position`, and `epoch_change` (boolean).
   Position is `null` or the name of a reviewed per-version extractor. `extractors` maps
   names to versioned adapter decoder identifiers; these are a closed allowlist, not executable
   scripts or heuristic offsets. Each implementation supplies golden packet bytes for every
   identifier, including negative coordinates and malformed input. All entries/identifiers
   are covered by R; no unversioned decoder choice or external file changes routing semantics.
   An extractor returns one signed-32-bit chunk (x,z), a bounded list for multi-chunk packets,
   or the special `join` result for joins with no position. Block positions use mathematical
   floor division by 16, section positions their chunk x/z. Lists obey the game's packet
   size/count bounds; multi-chunk packets use CONTROL/global, not single-shard guessing.
   Require a single-position extractor for ordinary chunk routes, `join` for chunk joins,
   null for other routes except globally routed multi-chunk packets. `lane` is REALTIME or
   chunk only; global/view/control/ordinary are CONTROL only; joins are chunk only.
   Resources also carry `serverboundLimits`, keyed by protocol state. Each entry specifies
   positive integer `maxServerboundRecord` (record_length, excluding its prefix), `maxDecodedFootprint`
    (decoded packet plus scratch), and positive integer `ordinaryReadAllowance` P (complete retained wire,
    parser/read copies and decode footprint of at least one maximum-size ordinary small
   packet, including keepalive wherever legal). The reviewed ordinary packet set is marked
   by `ordinarySmall:true/false` on every serverbound route; clientbound routes omit it.
    Values come from that version's game's serverbound packet limits and reviewed decoder
    accounting, not guessed Minecraft constants. Each limits entry also specifies
    `decodeFootprintBound`, a versioned closed-allowlist accounting identifier (not executable
    code) giving a conservative decoded-output/scratch bound from wire lengths, declared
    output length and compression state alone. Include parser/read copies in that accounting
    within F and the one 16 KiB read allowance, never outside E. P must also cover the
    maximum bounded-prefix staging needed before classification. Check all limits and
    accounting identifiers into R with boundary vectors, as for position extractors.
   Require `maxServerboundRecord <= 2097208`, decoded footprint <= 8 MiB, and
   `P + 16 KiB + 2*6 + 1 <= 24 KiB` (13.12/13.13). Missing/unreviewed limits or an ordinary
   packet footprint that cannot fit P disable `/3`; never relabel keepalive as large to pass.
   Reject a play record exceeding its state's maxServerboundRecord immediately after its
   length prefix, before allocating/buffering its body; enforce the state limit elsewhere too.
    Oversized decoded data or an invalid compression declaration is `0x11`. `ordinarySmall`
    is a reviewed resource guarantee, not a peer-supplied flag or permission to use P:
    any legal record whose pre-inflate footprint fits remaining P may use it, including
    routes marked false or unknown ids. Never close a legal small record merely because
    its decoded id is not marked ordinarySmall. An ordinarySmall guarantee that cannot
    be met is a local resource-review error disabling `/3`, not honest-peer misconduct.
   `epoch_change:true` is clientbound CONTROL/global only and matches reviewed level replacement packets.
   Non-play routes are CONTROL/global. Every serverbound route MUST be CONTROL; reject a
   serverbound REALTIME or chunk route before offering `/3`. Serverbound records have no
   explicit cross-stream dependencies, since that direction uses only CONTROL; a nonzero
   serverbound dependency_count or a serverbound auxiliary record closes with `0x11`.
   Reject missing/unknown fields, duplicate keys/routes,
   unsupported schema/identifiers and invalid combinations before offering `/3`.
   R is SHA-256 of the exact resource bytes, whitespace included, identical at both peers.
   Mandatory CONTROL data must now enforce the required fence policy too, not merely lane.
   Every relaxation from global needs ordering tests. Synthetic route-shape fragment only
   (not a complete selectable resource or a game mapping; reviewed serverboundLimits omitted):

   ```json
   {"schema":2,"protocol":12345,"default":"global","extractors":{"chunk":"test.chunk_xz.v1"},"routes":[
     {"state":"play","direction":"clientbound","packet_id":10,"lane":"control","dependency":"control","position":null,"epoch_change":false},
     {"state":"play","direction":"clientbound","packet_id":11,"lane":"realtime","dependency":"lane","position":null,"epoch_change":false},
     {"state":"play","direction":"clientbound","packet_id":12,"lane":"chunk","dependency":"lane","position":"chunk","epoch_change":false}
   ]}
   ```

8. **13.8 Explicit game invariants (26.1.2, protocol 775).** Chunk install, block_update,
   section_blocks_update, block_entity_data, light_update and forget_level_chunk use the
   position's CHUNK shard, in original order: install, updates, forget. Never move an update
   to REALTIME; block/section/block-entity updates before install are lost, and stale light
   after forget must not overtake it. Multiple positions colliding on a shard share its order.
   chunks_biomes spans chunks: keep the whole packet CONTROL/global, with its list extractor;
   it waits for prior installs and later chunk updates wait for it. Do not split/rewrite it.
   Entity storage is chunk-independent: spawn does not wait for a chunk install; forgetting
   a chunk does not remove entities. Spawn and entity-data bundles stay whole on CONTROL
   under 13.15. Entity updates on REALTIME wait for their CONTROL fence; removal waits for
   earlier REALTIME, not chunk traffic. block_event, level_event, explode and
   block_destruction need no chunk ordering; reviewed routes may use REALTIME/lane without
   a chunk dependency. View changes and joins obey 13.5/13.19. Any unproven type remains
   CONTROL/global, including unknown ids at both sender and receiver.

### Sequence barriers, bounds and delivery

9. **13.9 Dependency construction.** Maintain A[0..N+1] (last assigned), latest global G,
   view V and control fence C (CONTROL sequences), independently per sending direction.
   At one logical game-send ordering point, before parallel encoding/scheduling, start with
   the implicit own predecessor and the latest G. Add the following component maxima:
   - Global: all of previous A; then set G to this record.
   - View: all previous A; then set V and C to this record. For chunk_batch_start also
     include the previous BATCH_JOIN frontier as an explicit semantic dependency (13.19).
   - Control fence: previous A[CONTROL,REALTIME]; then set C to this record.
   - Ordinary CONTROL: nothing further.
   - REALTIME: latest C.
   - Ordinary chunk: latest V only. It never waits for unrelated CONTROL or REALTIME.
   - Chunk join: latest V and the semantic frontier in 13.19.
   Encode only nonzero cross-stream maxima; own predecessor remains implicit. All edges
   MUST point to previously assigned records, including joins and bundle units. Never queue
   a join behind a record that depends on it on its own stream. No post-scheduling discovery
   of new predecessors is allowed. G/V/C start at zero. Required dependencies may be
   transitively satisfied by larger same-stream frontiers, but may not be omitted unsafely.
10. **13.10 Receiver algorithm.** Maintain contiguous delivered watermarks `D` per direction.
    Parse envelopes without decoding game bodies; queue each in lane order. Deliver only a
    lane's head record, only when every dependency is <= the corresponding `D`, and only
    after the game's serial packet handler has accepted/applied it in order (not merely
    when bytes arrive or decoding completes). Then advance that lane's `D` and retry heads.
    Serialize delivery on the game connection executor; preserve the vanilla main-thread
    dispatch order for each dependency. No cross-direction dependency is implied: request/
    response semantics still use the game's application acknowledgements. A record with an
    invalid index, skipped/duplicate sequence, wrong epoch, excessive dependency list,
    same-stream entry or known impossible reference is `0x11`. Cross-stream bytes can arrive
    before a referenced record; absence alone is not proof of impossibility. Track unresolved
    references, detect cycles as edges become known, and enforce the blocked-head deadline.
    Validate the decoded packet's required fence/route before execution; a header cannot
    downgrade an unknown id from global. No speculative game execution or blocked-body
    decompression. Commit dependency watermarks D only after actual serial handler application
    completes. Separately track FIFO-enqueued watermark Q for timeout accounting only:
    successful enqueue of a validated eligible unit commits it for the application timer,
    but Q MUST NOT release dependencies, reuse receive/workspace capacity or change epochs.
     Each endpoint uses its local monotonic transport clock for network/incomplete/resource
     waits (only the incomplete-read pause expressly specified in 13.12 applies), and its
     local executor-active clock for blocked-head/decode/application work. Here and below
     "executor" means the game's serial/main-thread executor, NOT the Netty event loop.
     Resource waits do not exclude executor stalls; no resource wait starts for an operation
     blocked solely on that local executor rather than a refused excess/outbound grant.
    Executor-active time excludes intervals waiting for the game executor to run, including
    prerequisites already queued there and a handler suspended by a main-thread/tick stall.
    Track the enqueue/completion acknowledgements and stall intervals on each endpoint;
    while one unit is FIFO-enqueued or stalled in that executor, pause the dependent-head
    timers and backpressure further reads. Retain/charge queued data until actual completion.
    Decode/dispatch work actually running BEFORE Q still consumes its 10-second allowance;
    a decoder busy loop is not an excluded scheduling wait. After Q there is no transport
    application timeout on that unit; local game-handler watchdogs are separate from `0x11`.
    Heads waiting on that unit remain paused until D commits. Never classify local game
    execution/stalls as peer misconduct or release dependencies early to evade a stall.
11. **13.11 Barrier example.** CONTROL spawn 5 precedes REALTIME move 9, whose explicit
    CONTROL dependency is 5 (own predecessor 8 implicit). CONTROL remove 6 waits for REALTIME
    9, not chunks. CHUNK[0] install 7, update 8 and forget 9 run in that order independently
    of those entities. A later view gate waits for missing chunk 7 (and its prior chunk
    frontier), and new chunk records wait for that gate. A respawn/global waits for every
    old chunk, and new-epoch
    records wait for respawn execution. Independently withhold every predecessor in tests.
12. **13.12 Direction-specific memory, reservations and progress.**
    **Clientbound (client receives):** give every active stream a non-borrowable
    receive slot of B = 2 MiB + 128 bytes and 128 record slots. Admit at most one head
    scheduling unit (one record or one complete bundle) per stream into application buffering;
    stop reading that stream until its unit commits. A bundle's total wire size, headers
    included, must fit B. Parser prefixes, partial records, pre-activation bytes and staged
    bundle bytes all occupy that stream's slot; no stream can consume another's reserve.
    At N=6 the six chunks need 12 MiB + 768 bytes; CONTROL and REALTIME reserve another
    4 MiB + 256 bytes. Thus the old 16 MiB wire target needs 1 KiB explicit framing headroom:
    aggregate wire ceiling `(N+2)*B <= 16 MiB + 1 KiB`, not a misleading exact 16 MiB claim.
    A seventh chunk would require at least 18 MiB with the same progress reserves: hard cap 6.
    At most `128*(N+2) <= 1024` records, including delimiters and partials, are retained.
    Separately reserve one connection-wide 8 MiB execution workspace for decompression and
    retained decoded packet/bundle data (or stricter game limits). Only one unit decodes/
    applies at a time; all copies and decoder scratch must fit this workspace or fail closed.
    Worst application-held data is therefore 24 MiB + 1 KiB, not 16 MiB; allocations are lazy.
    Do not decompress other heads while the execution workspace is occupied.
    **Progress argument:** all edges point backward in logical send order. Among pending
    stream heads there is an earliest unit whose prerequisites are already applied or earlier
    heads. Every stream has room to finish receiving its head even when every other slot is
    full, and an eligible head has its reserved execution workspace. Fair receiving/handler
    scheduling therefore advances the earliest unit; a blocked join cannot steal the missing
    predecessor's capacity. This assumes transport progress and a completing game handler,
    not a malicious or stalled peer. The timer is a failure bound, not the deadlock remedy.
    Client native credit and unparsed read chunks are additional exposure (13.13). The
    client reserves capacity for its progress slots, workspace and transport exposure;
    reserve capacity need not allocate buffers. These are client receive reservations,
    NOT reservations imposed on the server for every connected player.

    **Serverbound (server receives):** all packets use one ordered CONTROL direction, so
    there is no cross-stream prerequisite and no need for per-stream progress slots or a
    pre-reserved large execution workspace. Reserve only 24 KiB per admitted connection for
    native exposure plus protected small reads (13.13). Idle connections retain no
    partial/read/decode buffers
    and hold no more than this admission share against the receive budget. This figure is
    not total connection memory: QUIC metadata, crypto/handshake and other subsystems remain
    separately accounted. At 50,000 idle connections the receive admission shares total
    1,228,800,000 bytes (about 1.14 GiB), not a multi-MiB workspace per player.
    Admit at most one serverbound record, bounded by the state's reviewed serverboundLimits,
    the game's serverbound packet limits and 13.4. Let L be that state's maximum wire record
    including its outer prefix, F its maxDecodedFootprint and P its ordinaryReadAllowance.
    Reserve P non-refusable bytes inside the 24 KiB share for one entire ordinary small
    record's read/parser/decode footprint. Neither another connection nor this connection's
    outbound queue may borrow it. Native exposure is bounded by 16 KiB + N guard bytes,
    plus at most N+1 one-byte guard-read copies: hence `16 KiB + 2*N + 1 + P <= 24 KiB`.
    This partition requires the asymmetric auxiliary credit in 13.13; simply reserving
    24 KiB of native credit AND calling that same memory a protected read allowance is invalid.
    Read calls grant at most `min(16 KiB, remaining charged capacity, remaining unit bytes)`;
    reserve the entire grant BEFORE invoking read, and return unused grant afterwards.
     Classify BEFORE inflating or decoding a packet id: parse only the bounded envelope,
     inner length and compression Data Length prefixes using P, with read grants limited
      to those prefix bytes until the declaration is known. Prefix staging ALWAYS comes
      from a fixed reserved slice of P, never the excess pool. Reserve 160 bytes of P for
      staged prefixes and their read/parser copies (54-byte header plus three at-most-5-byte
      lengths, at most two retained copies = 138 bytes, rounded up to 160);
      validate this bound against the reviewed envelope/length encodings before enablement.
      This is a partition of P, not an increase of the 24 KiB idle share.
      Define footprint as the entire
     retained wire record (including prefixes), all parser/read copies, plus declared
     uncompressed output and a conservative reviewed decoder/scratch bound. When compression
     is disabled or Data Length is zero, use the uncompressed inner payload length instead.
     The resource's reviewed decodeFootprintBound accounting must compute this bound from lengths/compression
     state alone, without inspecting a compressed packet id, and include prefix staging.
     A record is in-share ONLY if that entire footprint fits remaining P; reserve it before
     further body reads/inflation. Every other record requires refusable excess grants for
     its footprint before proceeding, even if its wire length alone fits P. Prefix reads
     fitting P are non-refusable; reject invalid lengths before further allocation. Review
     P against this conservative calculation for every ordinarySmall route's boundary vectors.
     In-share reads/decodes, regardless of ordinarySmall marking, cannot be refused by the node pool. This guarantees
    keepalive progress against OTHER connections' pool consumption, not bypass of a large
    record ahead of keepalive on this connection's own ordered CONTROL stream or a game stall.
     Charge all out-of-share partial records/read copies/decode storage to an isolated inbound excess
    pool before taking bytes or allocating. Per connection, excess is capped at
    `E = L + F + 16 KiB` (one wire record, one bounded workspace, one read suffix), or a
    lower configured cap that still admits reviewed legal units; this is not an idle reserve.
    No speculative decode or second executing record. Count game-executor-queued data too;
    release charges only on actual completion, returning to the admission share when idle.
    Refusing an excess grant withholds/retries that operation without parser advancement;
    it MUST NOT refuse protected reads or start a resource timeout on an in-share connection.
     Configure the inbound excess pool separately: provisional node default 64 MiB,
     per-source-IP default 16 MiB and per-authenticated-account default 16 MiB, each capped by
     the node limit and each including all retained/queued excess. Enforce all applicable
     caps before grants; accounts do not replace the IP cap, and pre-authentication uses
     the IP cap. These are unmeasured operational defaults to benchmark at 50,000 players,
     not new per-idle reservations; configure each cap to admit at least one reviewed E.
      Under pressure compute deficits for the node, requester's source IP and authenticated
      account caps. A victim MUST hold excess whose release relieves at least one currently
      binding cap; an unrelated IP/account holder is ineligible for a source-cap-only refusal,
      however high its rank. Recompute all deficits and eligibility after EVERY reclamation;
      stop reclaiming as soon as the waiting grant fits all caps. This applies to shared-IP
      (including CGNAT) and account caps as well as the node cap, not just node exhaustion.
      Use the pressure ranking below. Exclude holders blocked solely by a known local
      game-executor stall; never substitute an in-share victim. Close selected holders with `0x12`,
     release their excess, then atomically retry waiting excess grants FIFO before allowing
     new charges. Skip a still-ineligible wait without blocking grants under unrelated caps;
     preserve its FIFO position for the next retry. Never reclaim an in-share
    connection, or let a node-refusal timer expire it, to make room for another's excess.
     A refused excess operation has a 10-second monotonic transport-clock resource-wait
     deadline, with NO executor-stall exclusion; expiry closes only that excess requester
     with `0x12`. Pressure selection uses byte ownership over time, not whether a
    record crossed a game-handler boundary. Rate-limit admissions independently to defeat
    many-connection attacks; the share protects admitted peers, not unlimited admissions.
    Conservative server receive ceiling is `24 KiB + E`, with E bounded by
    `2097212 + 8 MiB + 16 KiB`: at most 10526780 bytes (about 10.04 MiB), usually lower
    under the reviewed state limits. This peak is on-demand, never a reservation per idle player.

    **Shared accounting:** no advertised credit or retained allocation may overcommit the
    node budget. Before admission, reserve the server's 24 KiB share, not the client's
    progress/workspace budget; refuse admission if that share cannot fit, leaving TCP
    untouched. Debit further server buffering before taking ownership of bytes. Release
    credit reservations only when their exposure is gone. Record admission shares, actual
    active peaks and refusals at the 50,000-player target; profile values remain unmeasured.
     Outbound queues use a separate capped node pool, never the inbound pool or its admission
    shares. Each connection is limited to 1024 packets or 16 MiB unsent data, including
    binding quiescence bytes and bytes handed to QUIC but not yet released from its send
     buffers. Backpressure before either cap; an unpausable producer fails `0x12`.
      Provisional outbound defaults: 256 MiB node, 32 MiB per source IP and 32 MiB per
      authenticated account, enforcing all applicable caps, including queued binding bytes.
      Pre-authentication uses IP; account caps supplement, not replace, IP caps. Measure then
      set these pool sizes/caps at 50,000 players; they are not idle reservations or proven
      capacity. Use the same binding-cap eligibility/recompute rule as inbound. Shed only
      outbound byte holders with `0x12`, never a requester holding no outbound bytes.
      Atomically retry waiting outbound grants FIFO from released capacity before new
      charges. Skip a still-ineligible wait without blocking grants under unrelated caps;
      preserve its FIFO position for the next retry, exactly as for inbound excess grants.
      If no reclaimable holder exists, wait under backpressure for at most 10 seconds on
     the local monotonic transport
     clock, without executor-stall exclusion. During binding 12.10/12.14's earlier activation
     bound also applies. Never abort an honest binding on the first pool refusal while
     other connections retain reclaimable outbound bytes. Never deny honest protected
     inbound reads because another peer stopped reading. Locally detected genuine shared
      resource aborts do not update the failure cache (12.14); apply the inbound peer-attribution
      exception below before classifying a refusal as resource-caused.
     **Pressure ranking (both pools, separately accounted):** maintain exponentially decayed
     byte-seconds per source IP and authenticated account, not per connection; half-life
     60 seconds provisionally. Integrate retained charged bytes, excluding known local
     executor-stall intervals and intervals in which the holder was waiting only on OUR
     refusal of its request (those add no byte-seconds). Track progress over a rolling 10-second window per identity:
     newly received inbound record bytes (including partial records, counted once, not
     retransmissions or copies), outbound bytes acknowledged/released by QUIC (not merely
     handed to it). Rank descending `decayedByteSeconds / (1 + progressBytesPerSecond)`,
     then current retained bytes descending. There is NO strict zero-progress/stalled-first
     tier or 5-second acquisition clock: a short mobile radio stall does not automatically
     outrank every progressing holder, nor can a trickle escape a priority tier. A connection's
     rank is the WORST (highest score) of its IP, account and connection-local scores, so a
     sibling streaming cheap records on the same IP/account cannot shield a stalled holder
     (the connection-local score uses that connection's own byte-seconds/progress). Ties
     then compare current retained bytes, then oldest current acquisition, then an
     internal, non-rotating connection id (a local counter, not a QUIC connection ID). A reconnect starts only
     its new connection-local statistics, never the identity history; no 5-second clock restarts.
     Aggregate across simultaneous connections and retain identity history for 10 minutes
     after last use; reconnects never reset it. Bound each pool's exact history to the
     configured admission IP/account tracking capacities, plus K=256 overflow buckets
     per pool, selected by a keyed hash (per-process random secret) of the IPv4 /32,
     IPv6 /64 or account, so one attacker's identities cannot dominate a single shared
     bucket. An account association protects an IP entry only while that account has at
     most 4 IP entries; beyond that the extra IP entries are unprotected.
     Reclaim expired inactive entries first; if full, evict the lowest-score inactive IP
     entry with neither an account association nor a live connection, folding its history
     into its overflow bucket. Never evict live entries or unexpired account history. If no
     such slot exists, charge the new identity's history/progress to its hashed overflow bucket
     instead of refusing it; count a connection's bytes/progress only once in overflow
     (worst-of rule above applies per identity) even if both its IP and account lack exact entries. Its actual IP/account caps still
     apply independently. Until its overflow bucket has no live users and its 10-minute inactive
     history expires, identities without an exact entry continue using it even if exact
     slots become free; do not promote them and erase reconnect history. Existing exact
     entries remain separate and protected. Decay bucket history under the same rules.
     The ranking-history table NEVER causes admission/grant refusal or TCP fallback,
     even when all exact entries are protected. Independent rate admission limits
     still apply. All ranking windows/weights are
     provisional operational defaults: measure then set, including CGNAT fairness,
     reconnect/trickle attacks and overflow poisoning (an attacker protecting every exact
     slot with account-associated IPs and rotating /64s must not make honest newcomers
     inherit its bad score). Scores only choose among eligible holders, never protected shares.
    Incomplete record/bundle: 10 seconds from first byte on the local monotonic transport
    clock, paused while local executor backpressure prevents reads. Blocked head: 10 seconds
    from becoming head on the executor-active clock. Decode/application dispatch: 10 seconds
    of pre-enqueue active work; FIFO enqueue Q completes that timer, while D still waits for actual
    application (13.10). Executor queue/tick-stall time is excluded at both client and server;
    do not let a >10-second tick stall disconnect every peer. Unrelated network progress
     restarts none of these clocks. **Peer attribution before resource classification
     (INBOUND only):** "peer" means this one connection's OWN sent bytes; other
     connections sharing its IP or account (CGNAT, family accounts) are other peers.
     An inbound refusal is peer-attributable only when the bytes this connection itself
     SENT exceed its own legal caps: its connection-local inbound cap, or a shared
     IP/account/node binding cap whose holders are exclusively this connection's own
     retained bytes. Do not reclaim unrelated holders or grant the fixed resource retry
     exception in those cases. If an inbound abort is required (deadline expiry), close
     with `0x11` (during binding, `0x10`), using normal client failure-cache/backoff and
     the server's ordinary protocol-violation handling: log one warning per minute per
     listener with the reason code and no payload, apply no other penalty beyond the
     independent admission rate limits above. A refusal under an IP or
     account cap held entirely or partly by other users of that IP/account is never
     attributable to this connection. Local implementation/producer faults are not
     evidence of peer fault. When no connection-local cap independently blocks the grant,
     shared pressure caused by other peers remains `0x12` even when this peer also holds
     bytes. OUTBOUND queues, the pending-ACK caps and the server binding queue are NEVER
     peer-attributable: they fill from the local game producer's volume plus an honest
     slow or lossy link, so their per-connection caps (1024 packets/16 MiB, 128 entries/
     64 KiB, 32 packets/64 KiB), deadline expiries and unpausable-producer aborts close
     `0x12`. A peer that stops reading is handled by the outbound 10-second wait and
     shedding above, never by `0x11`.
     **Deadline precedence:** ANY deadline expiring while
     this node is withholding reads/grants needed for completion due to a LOCAL resource
     refusal closes `0x12`, never peer blame `0x11` (or binding blame `0x10`), except for
     the peer-attributable refusals just defined. The earlier
     deadline still wins in time; resource classification wins when causes/timers overlap.
     In particular, an incomplete timer started by a prefix may expire before the later
     resource-wait timer; it then closes `0x12`, without extending either 10-second bound.
     This rule also covers activation, missing-stream/preamble and bundle deadlines.
     Otherwise timeout, cycles, impossible references, malformed packets,
     wrong shard/epoch/sequence and oversized units close the whole connection with `0x11`;
     resource refusals/pressure aborts use `0x12` as specified above, never `0x11` peer blame.
     Never evict a record, skip, release a barrier or retry mid-play.

### Flow control, resets, bundles and compression

13. **13.13 Transport limits.** Listener parameters are fixed before N is known. The server
    allows two client-opened bidi streams, the client six server-opened bidi streams; both
    uni limits are zero. Never increase MAX_STREAMS. Application validation rejects any
    server stream index >= N even though transport permits six. The client initially
    advertises 256 KiB per bidi stream and 2 MiB connection credit; the server advertises
    24 KiB connection credit, preserving the existing per-connection credit and admission
    share. Server stream credit is asymmetric: 8 KiB on client-opened bidi directions
    (CONTROL and REALTIME), but only one byte on server-opened bidi directions (reverse
    CHUNK streams, which carry no valid bytes). Pin server stream maximum windows at
    8 KiB and its connection maximum at 24 KiB; client maxima equal its initial windows.
    No auxiliary guard read may replenish credit: its first byte closes the connection
    on the event loop before any further credit/send work. Thus the reverse chunk windows
    never grow beyond one byte on a live connection; the valid serverbound native exposure
    is at most `2*8 KiB + N`, leaving P and guard-read capacity inside the 24 KiB share.
    This is a least-privilege change to UNUSED chunk directions, not a reduction of CONTROL's
    8 KiB or the server's 24 KiB connection credit. Test initial and rolling exposure,
    including invalid bytes coalesced on all guards, before enablement. As the application
    reads into client-reserved or server-node-charged buffering, rolling flow control can
    replenish that stream's pinned window;
    a clientbound incomplete head must continue reading, even while dependency-blocked, until its
    complete frame/unit fits the reserved slot. Do not read ahead into the next unit.
    Client native exposure is 2 MiB. Server native plus protected-read capacity fits the
    24 KiB share as derived above; neither is an additional admission charge.
    Client: count one at-most-16-KiB unparsed read chunk per stream separately, at most
    128 KiB more. Server: at most one 16 KiB CONTROL read suffix charged under E and N+1
     one-byte auxiliary guard reads inside the protected share; in-share CONTROL grants
     shrink to the pre-reserved footprint within remaining P rather than requiring an
     excess-pool allocation. Before classification, read only bounded prefixes as in 13.12. Include
    suffixes beyond a unit boundary. Native transport and read charges may overlap conservatively,
    never be omitted. Crypto/handshake/0-RTT
    buffers need separate node admission accounting, not a claim they fit stream credit.
    Client receiving and credit replenishment MUST be fair across all streams; never stop
    all reads because one shard is full. The server may stop CONTROL reads on a node-budget
    excess refusal as in 13.12, but not for protected reads. After the REALTIME preamble,
    keep a one-byte pending guard read there and on every reverse CHUNK direction (no
    CHUNK preamble); charge each guard BEFORE its read call within the share. Parse the
    REALTIME preamble one byte at a time, validating and releasing each byte before the
    next read, using that same one-byte guard charge. Any byte after it (or any CHUNK byte) is
    immediately `0x11`, not left unread in the native window. Keep the respective exposure reserved
    until connection close; unused streams do not multiply the server's 24 KiB aggregate.
    Maximum-window pins are provided by the project's existing, tested Netty patch
    `natives/patches/netty/0005-max-flow-control-windows.patch` (netty/0005); no further native
    patch is planned. Retest pins and bounded reads for these revised limits before enabling
    `/3`; initial values alone are insufficient.
    These windows trade throughput for a provable memory bound. A clientbound 2 MiB record
    needs roughly eight window turns; a large serverbound record at 8 KiB per RTT can hit
    the 10-second timer (about 800 KiB at 100 ms RTT). Benchmark the limits before enablement;
    increasing windows requires an explicit revised reserve, not silent auto-tuning.
    All other §9 parameters remain unchanged. Sender policy: 13.20.
14. **13.14 Reset and shutdown.** RESET_STREAM, STOP_SENDING, unexpected FIN on any lane,
    failure to open/identify an allowed stream within 10 seconds of activation, or an extra stream
    terminates the whole connection with `0x11`, subject to 13.12's local-resource deadline
    precedence. Reads of the preamble/first bytes needed to identify EVERY allowed stream
    MUST use reserved connection capacity, never a refusable pool grant: the server's
    one-byte REALTIME preamble/guard charge and CONTROL prefix slice inside its existing
    24 KiB share, and the client's reserved per-stream slots. No extra idle bytes are needed.
    If a local grant is nevertheless withheld and this deadline expires, close `0x12`,
    not `0x11`, and log a reserve-accounting defect. A STOP_SENDING must be detected even on a
    lane that never writes; never reopen just one lane or remap lost
    packets to CONTROL. Normal close sends a CONTROL disconnect if applicable, then END
    as a global fence after all queued game records. On END delivery the peer closes with
    `0x0`; sender waits at most 10 seconds before closing. A connection close is always
    terminal. TCP retry is a new player join, never mid-session recovery.
15. **13.15 Bundles.** Keep opening delimiter, all members and closing delimiter as one
    CONTROL scheduling unit, separate consecutive GAME records, with no intervening logical
    game sends. A spawn/entity-data bundle is a control fence, not a chunk/global fence.
    Use the strongest member scope (global > view > control > ordinary), with at least
    control scope for any bundle. If any member requires global scope, is unknown or is a
    chunk packet, promote the whole bundle to global; bundle context is the only exception
    to individual chunk routing. It MUST NOT mix state, epoch, activation or compression
    changes into a bundle.
    Buffer completely before assigning sequences; validate total encoded size <= B, at most
    126 members plus delimiters (128 records), and total decoded/scratch storage <= 8 MiB
    or stricter game limits. These tighter limits replace the old draft's 1022-member bound;
    exceeding them closes with `0x11`, never splits a bundle. All external prerequisites
    attach to the opening record; later members have only their implicit CONTROL predecessor.
    Later external records depend on the closing record as the fence frontier, never a
    partially staged member. Receiver uses provisional CONTROL watermarks only inside the
    bundle, validates the strongest member policy before execution, and commits the closing
    watermark only after applying the complete bundle atomically to the game's bundle handler.
    A missing close after 10 seconds, nested delimiter or policy violation closes `0x11`.
    These bundle buffering limits apply clientbound. Serverbound records remain bounded by
    the game's serverbound packet limits and the single-record admission rules in 13.12.
16. **13.16 Compression.** Compression remains per Minecraft packet, not a shared compressed
    stream. Threshold changes are global fences; encode the change packet using the old
    threshold, subsequent packets using the new one. Receiver applies the new threshold
    after delivering that change and before decoding later dependent GAME bodies. Do not
     decompress a blocked packet using the current threshold speculatively. At the server,
     check declared output length and the full 13.12 footprint BEFORE inflating; a small
     compressed wire record declaring up to 8 MiB output is not automatically in-share.
     Acquire its excess charge first if the footprint exceeds remaining P, and enforce
     actual output/scratch against the reserved bound while inflating. Keep ordinary
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
    staged-bundle auxiliary dependencies released only on commit; and per-stream byte/slot
    reservations under chunk floods, including quiche auto-tuning and node-budget
    exhaustion. Verify out-of-u31 protocol rejection, exporter exceptions/wrong lengths,
    PSK-only resumption rejection, ticket-key/certificate rotation, inert binding payloads
    outside `/3` and Velocity's bidirectional stripping. Test failed re-bind close-before-
    fresh-TCP ordering, new Login Start/session join, bounded OFFER wait, unchanged pin and
    retained (unused) tickets/tokens/recorded flights on failure, failure-cache and re-bind
     backoff updated on non-resource failure (asserted separately), no failure-cache or
      re-bind-backoff update on locally detected genuine shared-resource `0x12`; peer-sent
      `0x12` before verified SERVER_SWITCH uses exactly 12.14's bounded allowance, then
      updates both as `0x10`. Test rotating unauthenticated peers, persistence of the allowance
      across successes/restarts, and the authenticated resource-report exception; success-only discard of tickets/tokens/
    flights, changed fingerprint with unavailable exporter or missing/mismatched routing
    digest selecting fresh TCP without `/2` or `0x10`, restart-persisted fingerprint-independent
    backoff, notice deduplication/eviction, the join-time TCP retry (close before fresh TCP, log and
      chat message, failure-cache entries/exceptions exactly as in 12.14) for every pre-activation failure kind, no retry after
    activation, and, with 0-RTT accepted on `/3`, neither side starting binding before the
    server's TLS handshake completes (12.7).
    Run a 50,000-concurrent-QUIC-connection node benchmark before enablement, recording
    configured node budget, admission reservations/refusals, retained and native memory,
    peak pending bytes/records, CONTROL progress, latency and throughput under auxiliary
    pressure; show the budgets hold without relying on TCP for the target QUIC population.
    Run full-play desync assertions under every testkit netem profile. Phase 5
    requires measured loss improvement over TCP AND single-stream and security review;
    this draft makes no performance or completed-review claim.

### Chunk scope, joins and sender policy

18. **13.18 Epoch and position sharding.** Epoch starts at 0 independently per sending
    direction after activation. A reviewed level-creating/replacing global packet (play login,
    respawn, including same-dimension level replacement) carries the old epoch; only its
    serial-handler completion advances that direction's receive epoch by exactly one.
    Sender advances its assignment epoch immediately after assigning that fence; subsequent
    records carry the new epoch and depend on it. Start-configuration alone does not create
    a level or increment epoch. Sequences remain connection-scoped, never restart at epoch
    boundaries; positions, installed-chunk tracking and batch/update frontiers are keyed by
    epoch and cleared at level replacement. No old-epoch record can execute after that fence.
    New-epoch envelopes may arrive early, but remain opaque until their transition dependency
    executes; reject stale epochs or a future epoch without the corresponding transition
    once prerequisites are available. Epoch exhaustion closes with `0x11`, never wraps.
    Serverbound epoch is always 0: no serverbound route may change epoch, and any nonzero
    serverbound epoch is `0x11`. Acknowledgements do not mirror the clientbound epoch.
    Chunk routes in this draft are clientbound, so there is no ambiguous reverse position scope.

    For signed-32-bit chunk coordinates x,z and epoch e, compute using unsigned 32-bit
    arithmetic (multiplication wraps modulo 2^32):
    `h = (uint32(x) * 0x9e3779b1) XOR rotl32(uint32(z) * 0x85ebca77, 16) XOR
    (e * 0xc2b2ae3d)`; then `h = h XOR (h >>> 16)`;
    `h = h * 0x7feb352d`; `h = h XOR (h >>> 15)`;
    `h = h * 0x846ca68b`; `h = h XOR (h >>> 16)`.
    Shard is unsigned `h mod N`. This is a fixed wire rule, not Java's remainder on a signed
    int or a runtime-selected hash. It need not be cryptographic. No coordinate bytes are
    duplicated in the header: extract them from the eligible packet and validate its stream
    before executing it. Invalid coordinates/extraction, wrong shard and invalid epoch are
    `0x11`. Supply cross-language golden vectors for all N=1..6, negative/extreme coordinates
    and successive epochs before implementation is accepted. The proposed hash is subject
    to the explicit user confirmation below, not an implementation choice left unspecified.

19. **13.19 Join records.** Joins live on CHUNK[0], even when their semantic positions hash
    elsewhere. They have explicit dependencies on the actual covered frontiers, not an
    estimate based on packet size, most recently installed chunk, or packet sequence field.
    Their own-stream predecessor is implicit. They never become CONTROL/view/global
    frontiers merely because they are joins; unrelated CONTROL and REALTIME continue.
    - **ACK_JOIN (block_changed_ack):** the adapter tracks the causal set of authoritative
      block/section/block-entity updates covered by the game prediction sequence. At the ack's
      logical send point, take the maximum sequence of every covered update on each chunk
      stream, plus the CONTROL frontier covering the relevant action processing. Include
      the current CONTROL A[0] conservatively, and any globally routed covered updates.
      Remove intentionally suppressed/not-sent updates from coverage; they have no wire
      frontier. Delay the ack at the sender until every remaining covered update has been
      assigned; this sender-local wait is not a reason to close the connection. Assignment
      MUST proceed independently of the outbound FIFO: retain the pending ack outside the sequenced
      queues, never let it block assignment/enqueueing of updates it covers, and never
      publish its sequence/frontier to later records before resolving coverage. Covered
      updates are ordered before the ack at the logical assignment point even if the game
      emitted the ack first. On resolution, snapshot only previously assigned frontiers
      and assign/enqueue the ack after those updates, including on CHUNK[0]. No pending
      ack or dependent record may reach the client and start a blocked-head timer while
      coverage is unresolved; an honest client cannot incur `0x11` from this sender wait.
      Pending, unassigned acks do NOT participate in a global/view/batch fence's drain.
      Preserve original game order of every non-ACK record: in particular NEVER move a
      covered update across a view/global/batch fence to satisfy an ack. If the game emits
      ack A, fence V, then covered update U, assign V then U then A, with A taking current
      epoch/frontiers and waiting for U (and V through the normal fence rules). This is an
      explicit sender-local deferral of A, not U-before-V reordering; V cannot depend on
      the unassigned A. Already assigned joins still drain normally. An adapter must verify
      that deferring prediction acknowledgements this way preserves its game's semantics;
      disable `/3` for an unverified version rather than inventing forward edges.
      Test assignment independence under outbound FIFO backpressure; all edges remain backward.
      Bound pending ACK storage to 128 entries and 64 KiB per connection, whichever is hit
      first, charged to the outbound pool and its 1024-packet/16-MiB connection caps from
      creation, not only after assignment. Moving an ack into the sequenced queue transfers
      its charge, never duplicates it. Backpressure the ack producer at the limit without
      stopping covered-update/fence assignment; if this separation is impossible or the
      producer cannot pause, resource-abort `0x12` (never peer-attributed, 13.12),
      never silently drop/coalesce or retain unbounded acks, except for the explicit drops
      below. These are provisional measure-then-set limits, not idle reservations.
      A pending ack has a 10-second monotonic transport-clock deadline from creation,
      never extended by other traffic or executor stalls. No later than that deadline, or
      before ANY protocol-state, epoch or disconnect transition (including graceful disconnect),
      settle coverage: remove suppressed updates and resolve/enqueue in the old state/epoch
      using the safe-superset rule below if all remaining covered updates are assigned;
      otherwise drop the still-unassigned ack and release its charge. Never carry pending
      ACKs into another state/epoch, synthesize missing updates or publish forward edges.
      An actual disconnect discards unsent ACKs; resolving does not delay transport close.
      Dropping a pending ack does not suppress any mandatory game update. This explicit
      deadline/transition drop is the only drop exception to the storage-limit rule above;
      count/log it without packet contents. Verify that both bounded deferral and these
      drops preserve game prediction semantics for the adapter, or disable `/3` for that
      version. No sender wait alone disconnects an honest client.
      If exact causal
      coverage cannot be established, use all prior chunk frontiers plus CONTROL as a safe
      superset once all non-suppressed covered updates are assigned; otherwise keep the ack
      pending only until the deadline/transition above, rather than guessing a frontier.
      The ack's game sequence is not a transport sequence and does not identify one chunk.
    - **BATCH_JOIN (chunk_batch_finished):** chunk_batch_start is a CONTROL/view gate,
      so every following chunk install waits for its execution through the latest V,
      without adding another dependency class. Finished depends on the start and the
      per-stream maximum of every chunk install in that batch, not just its final stream.
      Batch completion/rate feedback occurs only after every install's handler completes.
      Reject nested/unmatched batch markers; an empty batch still depends on its start.
      Every subsequent chunk_batch_start explicitly depends on the preceding BATCH_JOIN
      on CHUNK[0], even across empty batches; its installs then depend on that start through
      V. Assign finished A before start B in logical send order, never behind B installs.
      This additional backward edge is required even if the view-gate drain currently
      implies it: batch framing must not depend on a future relaxation of view gates.
    Ordinary chunks still depend only on own predecessor, global and view; the subsequent
    batch's view gate carries its preceding-finished dependency. Joins do not themselves
    become global frontiers. A global fence still drains assigned joins (not pending ACKs),
    and epochs cannot share a batch.
    A multi-chunk update remains the global packet in 13.8, not an invented join that later
    single-chunk updates could overtake. Receiver checks join packet id/kind/CHUNK[0], known
    coverage and required frontiers before execution. Causal facts unavailable from wire
    alone remain sender obligations, verified by adapter ordering tests, not claimed as
    independently provable receiver knowledge. Malformed or known impossible joins close
    `0x11`; missing prerequisites wait under 13.12, never block CONTROL by moving there.

20. **13.20 Sender scheduling (local policy, not wire format).** The sender SHOULD implement
    dependency-aware weighted scheduling before bytes enter QUIC: ready latency-sensitive
    entity movement/updates first, chunk records second, other small packets third. Bound
    REALTIME bursts, guarantee chunk streams a positive minimum byte share whenever runnable,
    round-robin among chunk shards, and age other traffic so it also progresses. Promote
    unsent prerequisites of urgent records, including view/global fences and chunk heads
    needed by a join, instead of filling QUIC with dependents whose prerequisite is unsent.
    This policy MUST NOT alter stream order, dependency construction, bundle atomicity or
    the progress reservations. Backpressure at stream/connection writability must stop
    unbounded enqueueing into QUIC; cap write quanta at 64 KiB and account for queued bytes.
    Once bytes enter a stream they cannot be reordered; a partially queued record on one
    stream cannot be preempted by a later record on that stream. Small CONTROL packets can
    still wait behind earlier CONTROL; this design does not claim otherwise.
    Netty 4.2.19's `QuicStreamPriority`/`updatePriority` MAY supplement this policy as hints.
    Test urgency direction (quiche is expected to send lower values first), incremental
    behavior and loss/flow-control interactions before relying on hints. No correctness or
    starvation guarantee may rely on QUIC priority, and it cannot undo already queued bytes.
    Weight/burst/default tuning is benchmark policy, not negotiated wire data.

    Extend 13.17's acceptance gate with all N=1..6 and offer/range/selected-N transcript
    tampering; compact-header boundaries/duplicates/excess deps/sequence exhaustion;
    hash/epoch vectors and late old-dimension data; withheld view gates and chunk installs;
    missing block/light/forget ordering; chunk-independent spawn/remove; unknown ids promoted
    to global at both ends; multi-chunk biomes; covered updates across all shards before ack;
    batch installs before finished/rate feedback; join-cycle rejection; bundle promotion;
    all slots simultaneously full with each missing prerequisite withheld independently;
    serial handler delayed after receipt; native-window pins and node admission pressure;
    all-serverbound-CONTROL routing, 24 KiB idle server receive shares, non-fatal node-budget
    read/allocation refusals followed by recovery or timeout, release of active buffers back
    to the idle share, and sustained REALTIME load with guaranteed chunk progress and aged
    CONTROL progress.
    Include two consecutive batches with A's install and finished independently withheld,
    including an empty A or B: no start/install B before finished A commits. Delay an
    old install while the view center moves away and back; both gates must wait for its
    frontier. Flood inbound excess with trickled maximum legal records and outbound queues
    with non-reading clients: in-share keepalives must still read/decode, only excess/queued
    consumers are pressure victims, and inbound/outbound caps cannot borrow across pools.
    Assert reviewed length rejection at the prefix, per-call precharging/adaptive grants,
    guard-byte close without credit growth, and all share/footprint inequalities at N=1..6.
    Stall each game's executor for >10 seconds, including after FIFO enqueue and while a
    dependent head waits: Q may advance, D/epochs may not, timers exclude the stall, no
     connection closes solely for the executor stall, and ordered application resumes.
     Separately refuse excess/outbound grants across a stall and verify their transport-clock
     10-second waits do not pause and only resource-blocked requesters/eligible holders close.
     Test a compressed record whose wire length fits P but declared output does not: refuse
      shared excess before inflation, retain bounded prefixes, then recover after grant. Test legal
     small records marked ordinarySmall:false using P without `0x11`, plus invalid declarations
      and output/scratch overruns. Start an incomplete timer with partial prefix bytes,
      refuse excess under other-peer shared pressure after classification later, and let the earlier incomplete deadline
      expire: assert `0x12`, not `0x11`, including simultaneous deadlines and an executor stall.
      With a tight node pool all allowed stream preambles must still read from reservations;
      inject an erroneous withheld preamble grant and assert the 13.14 expiry is `0x12`.
      Verify the fixed prefix slice never charges excess. Exercise node/IP/account excess
      caps: exhaust an IP/account cap with unrelated higher-ranked holders present; reclaim
      only holders relieving a binding deficit, recompute after each, stop when the grant
      fits, and retry waiting grants FIFO before new arrivals (including shared-IP peers).
      Verify decayed identity-level byte-second/progress ranking: long-held modest trickle
      versus fresh larger allocation, fast progress versus trickle, partial inbound-record
      progress, a 5-second radio stall with no strict priority tier, simultaneous connections
      and reconnects with unchanged IP/account history, connection-local tie-breaking and
      expiry. Saturate history with rotating IPv6 /64s: reclaim eligible lowest-score inactive
      IP entries or use overflow, protect account/live history, and admit new honest players
      over QUIC without history-based refusal; verify cap accounting remains exact and
      reconnects cannot escape unexpired overflow history when an exact slot becomes free.
      Poisoning: an attacker keeps every exact slot live and heavy (account-associated IPs,
      rotating /64s); honest newcomers hashed to other overflow buckets are not shed first
      because of its score. A sibling connection streaming cheap records on the same
      IP/account does not shield a stalled holder (worst-of ranking).
      Outbound tests must enforce node/IP/account caps and the same continuous score ranking;
      rotate connections without resetting aggregated progress/history. Under outbound
      contention queue A then B on the same binding cap and a new arrival C: released capacity
      grants A then B before C. With A still IP/account-ineligible and unrelated B eligible,
      grant B without moving A's FIFO position; grant A first when its caps permit.
     Fill outbound capacity with non-reading peers, then activate an honest connection:
     shed-then-atomic-retry must succeed without abort/cache/chat notice. Persistent refusal
     must backpressure then close `0x12` and retry TCP with notice; when the detecting node is
     the client, never write a failure-cache entry. When the server detects it and the client
     receives only a pre-verification peer `0x12`, assert fixed-window handling for the first
     three reports per rolling 10 minutes, then normal failure-cache/backoff updates: honest
     remote exhaustion is not distinguishable from a forged resource claim, so exceptions
     are bounded, not authenticated. Verify a brief honest server-pressure episode can retry
     QUIC after 60 seconds and an attacker cannot obtain unlimited fixed-window exemptions.
     Rotate fingerprints/IPs/advertised ports for one logical server across restarts
     and assert the independent join-retry limit still bounds extra session joins, including
      after resource aborts. Repeated locally detected resource aborts across restarts must
      permit QUIC again after each fixed 60-second window, never double to hour-long TCP
      suppression or advance failure/re-bind backoff; unauthenticated peer `0x12` follows
      12.14's bounded allowance then normal non-resource backoff. Fill only a requesting
      peer's own connection cap or a shared cap held exclusively by it, inbound/outbound
      (inbound only), including during binding: peer-caused refusal/deadline expiry is `0x11`/`0x10` with normal
      backoff, never a fixed-window resource exemption or eviction of unrelated peers.
      Contrast other-peer shared exhaustion and a local producer fault, neither blamed on
      the peer. CGNAT: a refusal under an IP cap held entirely by other users of the same
      IP is never `0x11`. An honest slow-link client that hits the outbound connection cap
      (or pending-ACK/binding-queue cap) during a chunk burst closes `0x12` at worst,
      never `0x11`; a non-reading peer is shed by the outbound wait, not blamed. Emit an ack before its covered updates with a full outbound FIFO:
     update assignment must progress independently, no future/cyclic dependency or client
      `0x11` may result, and the ack follows all covered updates. Test game order A, view/global
      fence V, covered U: assignment is V,U,A, never U,A,V, and all edges point backward.
      Test both ACK pending limits, pool charging before assignment, and inability to pause
      only the ACK producer: bounded resource abort, never unbounded retention or a cycle.
      Suppress a covered update and verify it is excluded, not awaited forever. Leave
      coverage unresolved until the 10-second pending deadline and assert a counted drop,
      released charge and no client close. For protocol-state, epoch and disconnect
      transitions test both safe-superset resolution and unresolved-coverage drop; no pending
      ack crosses the transition, no mandatory update is lost and all emitted edges remain backward.
      Also test active-work timeouts,
     CLIENT-only OFFER-first enforcement (server accepts early brand/client-information writes)
     and both OFFER clocks, server binding-queue/node caps, u31
    boundaries, offlineTest warnings/default-off, `/2` changed-pin notices and failure-cache
    bounds on additional Mojang session joins. These are acceptance requirements, not claims
    that existing implementation passes them.
    Measure actual header bytes, per-stream peak buffers, execution workspace, native/unparsed
    exposure, CPU per player, priority fairness, join latency and chunk-load throughput,
    comparing TCP and single-stream under every netem profile. No measurements are claimed.

### DECIDED QUESTIONS (historical user approval, 2026-10-15; revisions noted)

1. Approve `/3`, the configuration-stage four-proof/switch exchange after OFFER, and the
   conservative exporter gate even for offline multi-stream tests?
2. Approve changed-pin handling as: online mode re-binds automatically through channel
   binding (pin replaced only after a passing check, player notified), failure falls back to
   TCP with the pin unchanged?
3. The original three lanes/full CONTROL fences are superseded by the 2026-10-16 direction
   above. Exact-byte JSON routing digests remain; schema 2 includes extractor definitions.
4. The original memory/packet limits are superseded by 13.12/13.13's progress reservations.
   The 10-second fail-closed deadlines remain for benchmarking, including per-node cost.

### Open questions for the user

1. Confirm the proposed hard cap of 6, draft server preference of 3 (client offers 1..6),
   and immutable per-connection selection in 13.2? Benchmark 1..6 before choosing defaults.
2. Confirm the fixed epoch-mixed 32-bit position hash in 13.18, or choose another before
   implementing golden vectors? This is a wire decision, not administrator configuration.
3. Confirm the direction-specific receive policy: the client reserves up to 16 MiB + 1 KiB
   wire and 8 MiB execution, plus 2 MiB native exposure and at most 128 KiB unparsed reads;
   the server reserves only 24 KiB per idle connection, including a reviewed protected small
   read allowance and one-byte unused chunk-direction guards, charging larger active CONTROL
   buffering to a capped inbound excess pool separate from outbound queues (13.12/13.13)?
   Client progress and server capacity must be
   benchmarked separately, including 50,000 server connections. Hardware profiles stay planned.
4. Should the pre-enqueue decode/dispatch executor-active work budget be raised above the
   approved 10 seconds? Executor waits and tick stalls are excluded; after FIFO enqueue
   there is no transport application timeout. This is not a comparison with the keepalive's
   elapsed-time budget, and this draft does not change the approved duration.
5. Approve the new close code `0x12` (resource abort) next to `0x10` (binding/setup failure)
   and `0x11` (peer ordering/protocol violation)? It marks closes caused by the local node's
   genuine shared resource exhaustion, not inbound self-cap refusals attributable to the peer's own sent bytes (13.12).
   Locally detected resource aborts never write a failure-cache entry; before SERVER_SWITCH
   verifies, peer reports remain untrusted and receive only 12.14's bounded retry allowance.
   Close codes are wire format; nothing ships with `0x12` until this is confirmed.
