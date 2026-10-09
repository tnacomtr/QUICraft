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
  characters. Still to check: whether Velocity's encoder applies its own, different length limit.

## Query port

- `ConnectionManager.queryBind(String hostname, int port)` binds the GameSpy4 query listener
  when `[query] enabled = true`; the port defaults to 25577, the default game port. Per
  docs/protocol.md §2 (user: separate port), QUIC then binds the configured alternative port
  (default game port + 1) and advertises that.

## Build

- Compile against `velocity-api` (MIT) and the Velocity proxy jar for internals (GPL-3.0), the
  proxy jar compile-only, fetched by version and checked by sha256 like the testkit images.
