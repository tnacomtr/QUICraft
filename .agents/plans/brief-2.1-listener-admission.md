# Brief: Unit 2.1, wire Retry and connection admission into the QUIC listener

GOAL: Wire the reviewed `HmacRetryTokenHandler` and `ConnectionAdmission` primitives into
`QuicServer`, so that both listener binds use them: the ordinary NIO bind and the hosted
`HostLoop` bind. After this unit the Phase 4 limits protect real traffic. Do NOT change their
contracts. Small additive package-private helpers are fine.

WHY: CLAUDE.md Phase 4 requires a real Retry handler plus per-IP and global limits on attempts and
open connections. Today `QuicServer.java:133` still installs `NoRetryTokenHandler`.

BRANCH: `agent/phase4-listener-admission`, worktree `/home/tna/QUICraft/.worktrees/phase4-listener-admission`
(created for you from `feat/phase4-alpha`, with the patched-Netty Maven repo already copied into
`natives/build/out/repo`). Work and commit only there.

READ FIRST:
- `CLAUDE.md` sections "Discovery and connection protocol" (point 5 racing, point 9) and Phase 4.
- `core/src/main/java/rs/sudoe/quicraft/core/transport/QuicServer.java` (all of it).
- `ConnectionAdmission.java`, `HmacRetryTokenHandler.java`, `NoRetryTokenHandler.java`,
  `TransportConfig.java`, `Codecs.java` (closeLater), `HostedDatagramChannel.java`.
- Netty source (patched, read-only):
  `natives/build/work/netty/codec-classes-quic/src/main/java/io/netty/handler/codec/quic/`
  - `QuicheQuicServerCodec.java:104-200`. writeToken is called only for a token-less Initial on an
    unknown DCID: true means a stateless Retry, false means create the connection. validateToken is
    called for a token-bearing Initial on an unknown DCID: invalid means a silent drop.
  - `QuicHeaderParser.java`: zero-based absolute offsets; parse a slice.
  - `QuicheQuicCodec.java:175-203`: it releases each datagram it reads.
- `docs/protocol.md` §8 (0-RTT, early tokens) and §9.

DESIGN (decided; you implement it). Two separate limiters:

1. **Ingress guard (packet-flood safety only)**: a new handler placed before the QUIC codec in BOTH
   binds.
   - It handles only `DatagramPacket`. It parses the first header with `QuicHeaderParser` on a slice
     (indices untouched). Only long-header INITIAL packets are counted, with
     `ingress.allowAttempt(senderIp)` on its own `ConnectionAdmission` instance (generous
     datagram-level budgets).
   - Denied: release the packet exactly once and drop it.
   - Everything else, including a parse failure and short-header, Handshake or 0-RTT packets, is
     forwarded unchanged and not released.
   - Fail-open: if the guard itself throws, forward the packet (log at FINE, rate-unlimited is fine
     at FINE).
   - The codec owns the localConnectionIdLength used by the parser; read it from wherever `Codecs`
     sets it, and don't hardcode a different value.
2. **Connection admission (logical connections)**: a second `ConnectionAdmission` instance.
   - A small package-private `QuicTokenHandler` wrapper delegates to one `HmacRetryTokenHandler`.
   - `writeToken`: call `admission.allowAttempt(ip)`. When it is denied, force a Retry (stateless,
     smaller than the Initial, so no amplification). Otherwise Retry is required iff
     `admission.requiresRetry()`.
   - Implement the "force" with the BooleanSupplier policy the Hmac handler already takes. The
     codec runs on the listener's single event loop: assert or document that, and don't use
     ThreadLocals unless needed.
   - `validateToken`: authenticate first via the Hmac handler. If the token is valid, call
     `allowAttempt(ip)`. If that is denied, return the invalid result (silent drop).
   - Result: a denied client gets at most a Retry and then silence, and falls back to TCP after the
     head start.
3. **Open-connection leases**: in the `ChannelInitializer<QuicChannel>`, before adding
   `Connection`, call `admission.tryAcquire(remoteIp)`. Verify from Netty source that the remote
   address is set at that point.
   - null: close at once with a QUIC transport-level CONNECTION_REFUSED (transport error 0x2, i.e.
     NOT an application close; `Codecs.closeLater(conn, false, 0x2)` or equivalent). Do not add the
     Connection handler.
   - Otherwise: release the lease exactly once on `closeFuture()`, covering TLS failure,
     idle-timeout, early-unconfirmed close and listener shutdown.
   - No new application close code: that would be a protocol change, which needs the user.
4. **Settings**: add to `TransportConfig`, following the existing `defaultCongestionControl()` /
   `defaultInitialCwndPackets()` style. Each has a Builder setter, a getter, and a system property
   `quicraft.admission.<name>`. An invalid or out-of-range value falls back to the default, with one
   WARNING log.
   - `globalAttemptsPerSecond` 200
   - `perIpAttemptsPerSecond` 20
   - `retryThresholdPerSecond` 50
   - `maxOpen` 2048
   - `maxOpenPerIp` 32
   - `maxTrackedIps` 8192
   - `ingressInitialsPerSecond` 4000
   - `ingressInitialsPerIpPerSecond` 200

   Clamp `retryThreshold` to `globalAttempts` (the primitive rejects a higher value). Ingress
   tracking reuses `maxTrackedIps`. The ingress instance's retryThreshold is its global budget,
   unused.
   - Also `quicraft.admission.retry` = `adaptive` (default), `always` or `never`. `always` is for
     tests and for owners under attack. `never` keeps today's behaviour and still enforces limits.
5. Leave `NoRetryTokenHandler` in place (the `never` mode may use it, or the policy may return
   false; your choice). Each bind gets its own two admission instances and its own Hmac key.
6. Update the comment at `QuicServer.java:132` and the class javadoc. Add one or two sentences on
   Retry and limits to `docs/protocol.md` §9, describing behaviour only. Another unit writes the
   owner docs.

CONSTRAINTS:
- `core` is Java 8: no `var`, records, `List.of` or text blocks. Every new file starts with
  `// SPDX-License-Identifier: GPL-3.0-or-later`. No new dependencies.
- Don't edit Velocity, Fabric, bridge, testkit, CLAUDE.md, README, `.claude/`, `AGENTS.md`,
  `docs/.plans/`, `agent_skills/` or `agent_config.yaml`.
- Every hook fails safe (CLAUDE.md): a bug here must never stop a TCP join, and should prefer
  letting a QUIC packet through over crashing the listener.
- Never use `InsecureQuicTokenHandler`.
- Don't run Gradle in `/home/tna/QUICraft` itself; only in your worktree. Never push, merge or
  delete branches.

TESTS you write in this unit, minimal wiring proof only (a separate tester writes the full matrix
later). Add them to the existing loopback test class nearest to QuicServer (grep core/src/test for
`QuicServer.bind`).
- (a) With retry=`always`, a loopback client connects and echoes, and a resumed 0-RTT connection
  still succeeds, even if it's no longer 0-RTT. Report which.
- (b) With `maxOpenPerIp=1`, a second simultaneous connection from loopback fails fast. Report the
  measured time to failure, which must be well under the client's connect timeout. After the first
  closes, a new connection succeeds (the lease was released).
- (c) The default config still gives 0-RTT acceptance at low load. The existing 0-RTT tests must
  pass unchanged.
- Run `./gradlew :core:test` and `./gradlew :core:check` in the worktree; both must be green.

DONE WHEN: both binds are wired, the settings exist with the defaults above, tests (a)-(c) pass,
`:core:check` is green and the work is committed on the branch, in small commits with clear
messages.

REPORT: the standard report (RESULT, CHANGED with file:line, ASSUMPTIONS / CONCERNS, BRANCH and
COMMITS, MEMORY CANDIDATES). Include the test counts and the measured times from (b).
