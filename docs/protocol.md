# QUICraft protocol

**Version: none yet (draft skeleton).** The full v1 spec is written in Phase 1, before any of it is
implemented. Once a version is released its wire format is frozen; changes need a new version and
the user's sign-off.

This skeleton lists what v1 must specify. The constraints come from `CLAUDE.md`.

## 1. Advertisement

- Namespaced top-level object in the status (server-list ping) JSON, e.g.
  `"quicraft:quic": { "v": 1, "port": 25565, "alpn": "quicraft/1", "fp": "sha256:<fingerprint>" }`.
- To specify: field types and limits, how unknown versions are handled, the status-response
  length cap per game version (believed 32767 characters; confirm in each version's source), and
  the rule that the object is left out if it would push the response over the cap.
- Velocity: the `ServerPing` API has no slot for custom top-level fields, so the plugin hooks
  ping serialization.

Verified (Oct 2026):

- **26.1.2:** `ClientboundStatusResponsePacket` reads and writes the status JSON with
  `ByteBufCodecs.lenientJson(32767)`. `Utf8String.read`/`write` enforce at most 32767 UTF-16 code
  units (`String.length()`), and at most `ByteBufUtil.utf8MaxBytes(32767)` encoded bytes. The writer
  checks too, so an oversized response fails on the sending side as well as in vanilla clients.
  Checked in Paper 26.1.2's patched server jar. Still to check: the vanilla client jar for every
  supported version, and Velocity's own status serializer.

## 2. Port and query-port collision

- Default QUIC port is the TCP port number, on UDP.
- To specify: what happens when that UDP port is taken by the query protocol (vanilla
  `query.port` 25565, Velocity `[query] port` 25577). Options: demultiplex by first bytes (query
  starts `FE FD`) or fall back to a configured port and advertise it. Startup never fails over it.

## 3. Address

QUIC goes to the host the TCP connection resolved to (SRV included) and the advertised port.

## 4. Connect decision

Fresh cached advertisement → QUIC. None (e.g. direct connect) → status query first. No
advertisement → TCP. To specify: cache TTL.

## 5. Racing and fallback

QUIC first; TCP opened in parallel after a head start; first to complete wins; the Minecraft
handshake goes only on the winner; a QUIC handshake failure starts TCP immediately. To specify:
the head start, **measured in the testkit** and recorded here.

## 6. Failure cache

Per (address, fingerprint), exponential backoff, persisted. After a fallback, the client tells the
server over TCP (plugin message) that QUIC failed; the server logs a rate-limited hint. To
specify: backoff parameters, storage format, plugin channel name and payload.

## 7. Trust

Self-signed certificate generated on first run; fingerprint advertised; client checks the QUIC
certificate against it and falls back to TCP on mismatch. No trust prompt before Phase 9.
Fingerprints recorded silently for later pinning.

## 8. Encryption

Single stream: Minecraft's own login encryption keeps running inside QUIC. Multi-stream (Phase 5)
needs a separately reviewed design.

## 9. Transport parameters

To specify, with reasons: max idle timeout (longer than Minecraft's ~30 s keepalive timeout),
initial flow-control limits (`initialMaxData`, per-stream limits, max streams; quiche's defaults
allow no sending at all), congestion control algorithm, ALPN.

Verified against `netty-codec-classes-quic` 4.2.19.Final (Oct 2026):

- `QuicCodecBuilder` exposes `maxIdleTimeout`, `initialMaxData`,
  `initialMaxStreamDataBidirectionalLocal`/`Remote`, `initialMaxStreamDataUnidirectional`,
  `initialMaxStreamsBidirectional`/`Unidirectional`, `ackDelayExponent`, `maxAckDelay`,
  `activeMigration`, `hystart`, `discoverPmtu`, `initialCongestionWindowPackets`,
  `maxSend`/`RecvUdpPayloadSize`, `datagram(recvQueueLen, sendQueueLen)`,
  `activeConnectionIdLimit`, `grease` and `version`.
- Each value is held as a nullable boxed field. A value left unset is not passed to quiche, so
  quiche's own default applies. For the flow-control and stream limits that default is 0, which is
  why every limit must be set explicitly. To confirm with a loopback test in Phase 1.
- Congestion control: `QuicCongestionControlAlgorithm` offers `RENO`, `CUBIC` and `BBR`.
- Retry/address validation: `QuicServerCodecBuilder.tokenHandler(QuicTokenHandler)`. Netty ships
  `InsecureQuicTokenHandler` (never to be used, per CLAUDE.md) and `NoQuicTokenHandler` (no
  validation). The interface is `writeToken(out, dcid, address)`, `validateToken(token, address)`
  and `maxTokenLength()`, so a real handler (e.g. HMAC over address + timestamp) is ours to write.
  The same builder also takes `connectionIdAddressGenerator` and `resetTokenGenerator`. Netty
  includes HMAC-signing implementations of both.

## Connection migration and addresses (Phase 2 and 9 notes)

Verified against 4.2.19.Final:

- `QuicChannel.remoteSocketAddress()` returns the peer's UDP `SocketAddress`. That is what the
  Velocity bridge should report as the player's `InetSocketAddress`; `remoteAddress()` is the
  connection-ID address.
- Path changes arrive as `QuicPathEvent` user events: `New`, `Validated`, `FailedValidation`,
  `Closed`, `ReusedSourceConnectionId` and `PeerMigrated`, each with `local()` and `remote()`.
  `collectPathStats(int)` gives per-path stats. Still open: what a migration means for IP bans and
  forwarding once the player's address changes mid-session.

## 10. Client settings

`auto` (default), `tcp-only`, `quic-only` (debug).
