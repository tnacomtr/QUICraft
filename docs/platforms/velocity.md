# Velocity integration notes (Phase 2)

Design notes from reading Velocity 4.2.0 build 30 (GPL-3.0, Netty 4.2.18). Everything here
hooks Velocity internals, so it is re-checked against every supported Velocity release before
each release of the plugin.

## Accepting QUIC players

- `VelocityServer.getCm()` → `ConnectionManager` has a public final field
  `serverChannelInitializer` of type `ServerChannelInitializerHolder`
  (`Supplier<ChannelInitializer<Channel>>` plus `set(...)`). Other plugins use the same holder to
  inject channels.
- `ServerChannelInitializer` is a `ChannelInitializer<Channel>`, so it accepts any channel type.
  It builds the whole vanilla pipeline: `legacy-ping-decoder`, `frame-decoder`, `read-timeout`,
  `legacy-ping-encoder`, `frame-encoder`, `minecraft-decoder`, `minecraft-encoder`, then a
  `MinecraftConnection` with a `HandshakeSessionHandler` under `handler`.
- Reaching it: `VelocityServer` holds the manager in a **private** field `cm` (no getter in
  4.2.0), so the plugin needs one reflective read from the injected `ProxyServer` (cast to
  `VelocityServer`). Guarded: if anything is missing, log one WARN, don't start the QUIC listener,
  and leave Velocity as it is (TCP only).
- Event loop: `ConnectionManager` exposes only its boss group. QUIC players get a dedicated
  group made with Velocity's own `TransportType.createEventLoopGroup(Type.WORKER)`, so their
  pipelines and backend connections don't share the single accept thread.
- Plan: for each accepted QUIC stream, wrap it in `QuicBridgeChannel` (bridge-netty42, since
  Velocity bundles Netty 4.2.18), add `holder.get()` to its pipeline, and register it on Velocity's
  worker group. Velocity then treats it like any TCP connection. The remote address is the
  player's UDP `InetSocketAddress`, so IP bans and forwarding keep working.

## Advertisement in the ping response

- `StatusResponsePacket` carries the status JSON as an already-serialized `CharSequence`. That
  avoids the `ServerPing` API, which has no slot for custom top-level members.
- Plan: wrap the holder's initializer so that after Velocity's own setup it adds an outbound
  handler right after `minecraft-encoder`. Outbound writes travel tail → head, so the handler
  sees `StatusResponsePacket` objects before encoding and replaces one with
  `new StatusResponsePacket(advertisement.insertInto(json))`. Applied to TCP channels too: that's
  where server-list pings arrive.
- Fail safe: any exception passes the original packet through unchanged.
  `Advertisement.insertInto` already leaves the response alone if it would exceed 32767
  characters. That guard is load-bearing here: `StatusResponsePacket.encode` calls
  `ProtocolUtils.writeString(buf, CharSequence)` with no length check, so Velocity would send an
  over-cap response and vanilla clients would fail to ping. The Phase 2 gate test (maximum-size
  MOTD plus favicon) covers this.

## 0-RTT logins (docs/protocol.md §8)

- Velocity registers a player in its `LoginEvent` stage, before sending Login Success and long
  before Login Acknowledged (`AuthSessionHandler`, read in 4.2.0): in offline mode a login started
  by 0-RTT data holds the name right away. If the client then abandons that QUIC attempt (TCP won
  the race), its TCP login would be refused with "already connected to this proxy" until the
  abandoned connection closes, up to the server's 10 s confirmation deadline.
- So the plugin subscribes to `PreLoginEvent` (public API, highest priority), which fires for every
  login before Velocity registers anything: it calls core's `closeUnconfirmedEarly(remote
  address, username)`, which closes unconfirmed early QUIC connections from the same IP with that
  name in their Login Start (never the login's own connection), and returns an `EventTask` that
  resumes once they are closed and `proxy.getPlayer(name)` is empty again (at most 1 s, then 2 s
  overall). Logs one INFO line per closed login. Any failure lets the login continue unchanged.
- Measured (testkit `SCENARIO=early-fallback`, docs/benchmarks.md): offline and online mode,
  4 of 4 TCP joins after an abandoned 0-RTT attempt succeeded, with the abandoned login closed
  each time.

## Query port

- `ConnectionManager.queryBind(String hostname, int port)` binds the GameSpy4 query listener
  when `[query] enabled = true`; the port defaults to 25577, the default game port. Per
  docs/protocol.md §2 (user: separate port), QUIC then binds the configured alternative port
  (default game port + 1) and advertises that.

## Build

- Compile against `velocity-api` (MIT) and the Velocity proxy jar for internals (GPL-3.0), the
  proxy jar compile-only, fetched by version and checked by sha256 like the testkit images
  (`VerifiedDownload`). velocity-api 4.2.0 requires Java 25, so the plugin targets 25.
- The POMs of `velocity-api` and `velocity-brigadier` declare no license. Both are MIT (Velocity's
  `api/LICENSE`, PaperMC/velocity-brigadier; checked Oct 2026) and allowlisted by exact version.
  `aopalliance` (public domain, via Guice) is excluded: not needed to compile.
- The plugin jar bundles the shaded core (natives, licence and notice files included) and
  bridge-netty42 as they are. Nothing of Velocity's is bundled.

## Implementation (velocity/)

- `QuicraftVelocity`: on `ProxyInitializeEvent` (which Velocity fires before binding its
  listeners) reads `config.properties`, checks the native, reads `cm`, binds the QUIC listener
  and only then wraps the initializer holder. Any failure: one WARN, TCP untouched.
- `QuicListener`: `QuicBridgeChannel` + `holder.get()` + `DropProxyProtocol` per stream. With
  Velocity's `haproxy-protocol` on, the initializer adds an `HAProxyMessageDecoder`; QUIC
  carries no PROXY header (the UDP source is the player), so it is removed on QUIC channels.
- `AdvertisingInitializer` / `StatusAdvertiser`: as planned above. The advertiser is withdrawn
  when the listener closes; the wrapper stays in the holder (another plugin may have wrapped it
  since) but passes everything through.
- Velocity logs one WARN at startup, "The server channel initializer has been replaced by
  rs.sudoe.quicraft.velocity.QuicraftVelocity.start": `ServerChannelInitializerHolder.set` is
  deprecated as internal and logs its caller. Expected; the README tells server owners.
- Smoke test against the real Velocity 4.2.0-30 jar (2026-10-10, loopback): TCP ping carries the
  advertisement; a status ping over QUIC runs through Velocity's own pipeline on its native
  worker loop and returns the same JSON; the identity persists across restarts; with the UDP
  port taken, the plugin logs one WARN and Velocity runs TCP-only.
- Phase 2 gate (2026-10-10): QUIC and TCP joins through the plugin in the Docker testkit, and the
  status-length cap against the real jar; numbers in docs/benchmarks.md.
- `Settings`: `enabled`, `port` (0 = game port), `alternative-port` (0 = game port + 1, used
  when `[query]` is on the same UDP port).
