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
- `Settings`: `enabled`, `port` (0 = game port), `alternative-port` (0 = game port + 1, used
  when `[query]` is on the same UDP port).
