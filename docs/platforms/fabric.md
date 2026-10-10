# Fabric integration notes (Phase 3)

Client and dedicated server for the 26.x + 1.21.11 hook group. One source tree (`fabric/`), two
jars: `fabric-26x` (26.1–26.3, unobfuscated, Java 25) and `fabric-1.21.11` (Mojang names in
source, remapped to Intermediary by Loom, Java 21). Read from the decompiled game (Loom
`genSources`, kept local, never committed) for 1.21.11 and 26.1.2. Every hook below was checked
in both, and the gametests run on each version (see Tests).

## What runs where

| Side | Hook (class, method) | What QUICraft does there | On failure |
| --- | --- | --- | --- |
| server | `ServerConnectionListener.startTcpServerListener`, the `ServerBootstrap.childHandler` argument (`@ModifyArg`) | wraps vanilla's channel initializer: vanilla's runs first, unchanged, then the status advertiser goes in next to `splitter`/`prepender` | vanilla's initializer is returned unchanged |
| server | same method, `RETURN` | binds the QUIC listener (UDP) next to the TCP one | one WARN, no advertisement, TCP only |
| server | `ServerConnectionListener.stop`, `HEAD` | closes the QUIC listener | logged at debug |
| server | `ServerCommonPacketListenerImpl.handleCustomPayload`, `HEAD` | a `quicraft:fallback` report feeds the rate-limited hint (docs/protocol.md §6) | ignored |
| both | `DiscardedPayload.codec`, `HEAD` | for `quicraft:fallback` only, a codec that keeps the payload bytes (vanilla drops unknown payloads' bytes) | vanilla codec |
| client | `ConnectScreen$1.run` (the connector thread), the `Connection.connect` call (`@WrapOperation`) | the connect path below | vanilla's `Connection.connect` |
| client | `ServerStatusPinger.pingServer`, the `Connection.connectToServer` result | reads the advertisement off the status response as it passes (byte level, nothing changed) | nothing cached; vanilla ping unaffected |
| client | `ClientHandshakePacketListenerImpl.handleLoginFinished`, `TAIL` | after a fallback, sends one `quicraft:fallback` report (configuration phase) | no report |
| client | `DebugEntryTps.display`, `TAIL` | F3 line `QUICraft: QUIC (auto)` / `TCP (…)` under the server line | no line |
| client | `JoinMultiplayerScreen.init`, `TAIL` | "QUICraft" button (top right) opening the settings screen | no button |

Every hook body runs inside `try { Hooks.enter(…); … } catch (Throwable)`, logs once per hook and
continues down the vanilla path. The mixin configs set `required: false` and
`defaultRequire: 0`: a target missing in some future release leaves that hook out instead of
crashing the game.

### Server

- The dedicated server only (`MinecraftServer.isDedicatedServer()`); a singleplayer world opened
  to LAN stays TCP.
- QUIC runs on one loop of the game's own TCP event loop group
  (`EventLoopGroupHolder.remote(useNativeTransport)`), over a datagram channel of the same
  transport (`EpollDatagramChannel` next to `EpollSocketChannel`, etc.; `GameHost.datagramChannelsLike`).
  Each QUIC stream becomes a `QuicBridgeChannel` with vanilla's own initializer, registered on that
  loop, so the game sees an ordinary connection with the player's UDP address. All QUIC players
  share that one loop (the same trade-off as on Velocity; Phase 9).
- Advertisement at the byte level (`bridge-netty`'s `StatusAdvertising`): one handler after the
  frame decoder reads the handshake's intent; if it is "status", a second handler after the frame
  encoder rewrites the first outbound packet with id 0 (the status response) through
  `Advertisement.insertInto`, which leaves it alone if the result would exceed 32767 characters.
  No game classes involved, so it doesn't depend on `ServerStatus`'s codec, which drops unknown
  members.
- Port: `QuicPort.choose` (shared with Velocity): the game port, or with `enable-query` on the
  same port (`query.port`), the alternative port (default game port + 1).
- Files in `config/quicraft/`: `server.properties` (`enabled`, `port`, `alternative-port`),
  `quic-key.pem` and `quic-cert.pem` (the identity; keep them, clients remember the fingerprint).

### Client

The connect screen's call to `Connection.connect(address, holder, connection)` goes through
`ClientConnect`:

1. `ClientConnector.plan` (core) decides synchronously whether QUIC can play a part: not with
   `tcp-only`, without a native, or when a fresh advertisement exists but the failure cache is
   backing off. Then vanilla's own `Connection.connect` runs, untouched.
2. Otherwise core's `ClientConnector.connect` runs on the game's Netty loop: status query first if
   nothing is cached (`StatusQuery`: handshake with the host name the game would send, status
   request, response JSON), then the decision and the race (head start 250 ms). QUIC is hosted on
   the same loop over a datagram channel of the game's transport (`GameHost.connect`).
3. The winner gets exactly the pipeline vanilla's `Connection.connect` initializer builds
   (`timeout` = `ReadTimeoutHandler(30)`, `Connection.configureSerialization(…, CLIENTBOUND, …)`,
   `connection.configurePacketHandler`), checked identical in 1.21.11 and 26.1.2. A QUIC winner is
   registered after that, so `Connection.channelActive` fires as on TCP. A TCP winner of a race is
   already active: the pipeline is installed in a loop task (after Netty's own `channelActive`)
   and `Connection.channelActive` is called once by hand.
4. The screen gets a future that completes when the `Connection` is active on the winner. Its
   Cancel button cancels the future, which abandons the race and closes whatever connects later.
   The future's `channel()` is a placeholder; the screen never uses it.

Status pings from the server list refresh the advertisement cache, keyed by resolved IP and TCP
port, so a join from the list usually skips the status query.

Files in `config/quicraft/`: `client.properties` (`transport=auto|tcp-only|quic-only`),
`quic-failures.properties` (the failure cache).

The mod does not need Fabric API.

## Logging

Core logs through `java.util.logging` (logger `QUICraft`); the mod routes it into the game's log.
INFO lines per connect: `QUICraft: connected to … over QUIC`, or `over TCP; QUIC failed (reason)`,
or `over TCP; no QUIC advertised`.

## Tests

Client gametests (`fabric/src/gametest`, Fabric API's client gametest module, test-only): a real
client and an in-process dedicated server with the mod, headless under Xvfb.

| Test | Checks |
| --- | --- |
| `SettingsScreenGameTest` | multiplayer screen button opens the settings; the transport button cycles auto → tcp-only → quic-only → auto and saves |
| `QuicJoinGameTest` | direct connect (status query) joins over QUIC; scripted play session with no desync (block bursts compared block by block, 10 entities spawned and removed, client-driven walking and server teleports compared position by position, inventory slot by slot, weather, a far teleport into freshly generated chunks); F3 line; server list ping works and caches the advertisement; rejoin over QUIC from the cache |
| `FallbackGameTest` | advertisement with a dead UDP port, and one with another server's fingerprint: each joins over TCP, the server receives the fallback report, the failure cache backs off |
| `TcpOnlyGameTest` | `tcp-only` joins an advertising server over TCP; F3 says so |
| `QuicOnlyGameTest` | `quic-only` joins over QUIC; with a dead QUIC port it ends on the disconnected screen (no fallback, no hang) |
| `JoinTimingGameTest` | opt-in (`-Pquicraft.joinTimings=N`): TCP vs QUIC join timings, see docs/benchmarks.md |
| `FaultInjectionGameTest` | every hook throwing on client and server (`Hooks.setFaultInjection`): server list ping works, join over TCP, full play session |

```sh
xvfb-run -a ./gradlew :fabric-26x:runClientGameTest :fabric-1.21.11:runClientGameTest
# another game version in the 26.x range:
xvfb-run -a ./gradlew :fabric-26x:runClientGameTest -Pfabric-26x.minecraft=26.3 -Pfabric-26x.fabricApi=0.162.0+26.3
```

Results (2026-10-10): all pass on 26.1, 26.1.1, 26.1.2, 26.2, 26.3 and 1.21.11. The mod classes
compiled against each 26.x version are byte-identical, so the single jar built against 26.1.2 is
the one tested on every 26.x release.

Harness notes:

- The gametest API differs between Fabric API versions (4.x on 1.21.11, 5.x on 26.1.x, 6.x on
  26.2+); `GameTests.waitForChunks` bridges that by reflection, and the tests use `var` for the
  connection type.
- Fabric API 4.x's network synchronizer counts packets sent through `Connection.sendPacket`
  against packets received, and QUICraft's status query writes raw bytes, so the gametest run
  sets `fabric.client.gametest.disableNetworkSynchronizer` (Fabric's advice for mods that work at
  the Netty level). The tests wait in ticks.
- The `testkit` Fabric image (`testkit/docker/fabric/`) runs the same mod jar on a dedicated server
  for the loss/delay measurements in docs/benchmarks.md.
