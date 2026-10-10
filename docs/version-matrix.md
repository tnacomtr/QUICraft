# Version matrix

Which game versions, loaders and Java targets each module covers. Keep it current with every
module and every new release.

## Modules

| Module | Covers | Loader / platform | Java bytecode | Status |
| --- | --- | --- | --- | --- |
| `core` | all | none (no Minecraft classes) | 8 | Phase 1 |
| `testkit` | 26.1.x (protocol 775) | none (never shipped) | 25 | Phase 0 |
| `bridge-netty42` | MC 1.21.11, 26.x; Velocity 4.2; testkit (MCProtocolLib) | none | 8 | tested on 4.2.1 |
| `bridge-netty41` | MC 1.20.1, 1.21–1.21.10 | none | 8 | tested on 4.1.82 |
| `bridge-netty40` | MC 1.8.9 | none | 8 | 1.8.9 phase |
| `velocity` | client protocols Velocity accepts | Velocity 4.x | 25 | Phase 2 |
| `fabric-26x` | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | Fabric Loader ≥ 0.19.5 | 25 | Phase 3 |
| `fabric-1.21.11` | 1.21.11 | Fabric Loader ≥ 0.19.5 | 21 | Phase 3 |

The 26.x + 1.21.11 Fabric hook group shares one source tree (`fabric/`) and builds separate jars:
1.21.11 is obfuscated (Mojang names in source, Intermediary in the jar, Loom
`fabric-loom-remap`, Java 21) and 26.1+ is not (Loom `fabric-loom`, Java 25). Checked against the
decompiled 1.21.11 and 26.1.2 (Oct 2026): every hook target and the client pipeline the mod
rebuilds are the same in both, so no split beyond the two jars (details:
docs/platforms/fabric.md). `fabric-26x` is built against 26.1.2; its classes compiled against
26.1, 26.1.1, 26.2 and 26.3 are byte-identical, and the client gametests pass on each.

Fabric API is not a dependency of the mod. The gametests use it (test-only):

| Game version | Fabric API (gametests) | Client gametest API |
| --- | --- | --- |
| 1.21.11 | 0.141.6+1.21.11 | 4.x |
| 26.1 | 0.145.1+26.1 | 5.x |
| 26.1.1 | 0.145.4+26.1.1 | 5.x |
| 26.1.2 | 0.155.3+26.1.2 | 5.1.0 |
| 26.2 | 0.161.0+26.2 | 6.x |
| 26.3 | 0.162.0+26.3 | 6.0.7 |

## Game and platform Netty versions

From Mojang's version manifest (`piston-meta`) and the platform jars, Oct 2026. Each bridge
compiles against the **oldest** Netty among its consumers.

| Consumer | Java | Netty | Bridge |
| --- | --- | --- | --- |
| MC 1.8.9 | 8 | 4.0.23 | netty40 (later) |
| MC 1.20.1 | 17 | 4.1.82 | netty41 |
| MC 1.21–1.21.3 | 21 | 4.1.97 | netty41 |
| MC 1.21.4 | 21 | 4.1.115 | netty41 |
| MC 1.21.5–1.21.10 | 21 | 4.1.118 | netty41 |
| MC 1.21.11 | 21 | 4.2.7 | netty42 |
| MC 26.1–26.1.2 | 25 | 4.2.7 | netty42 |
| MC 26.2 | 25 | 4.2.15 | netty42 |
| MC 26.3 | 25 | 4.2.16 | netty42 |
| Velocity 4.2.0 | 25 | 4.2.18 | netty42 |
| MCProtocolLib 26.1-1 (testkit) | 17+ | 4.2.1 | netty42 |

So `bridge-netty42` compiles against 4.2.1 and `bridge-netty41` against 4.1.82. The two share one
source tree (`bridge-netty/`): `AbstractChannel`'s extension points are identical in 4.1.82 and
4.2.1. Each module runs the same tests against its own Netty. 1.21.11 already
uses Netty 4.2, the same generation as 26.x, which fits the shared 26.x + 1.21.11 hook group.

## Pinned dependencies

| Dependency | Version | Notes |
| --- | --- | --- |
| Netty (incl. `netty-codec-classes-quic`, `netty-codec-native-quic`) | 4.2.19.Final | BoringSSL `d03dbc3e` (2026-05-12, after the Apache-2.0 relicense), quiche `be47c501`; QUIC classes are Java 8 bytecode; natives for linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64, windows-x86_64 |
| QUICraft's Netty QUIC build (`rs.sudoe.quicraft.netty:*`) | 4.2.19.Final-quicraft2 | The Netty, quiche and BoringSSL revisions above, plus `natives/patches/` and `natives/quiche-Cargo.lock` (docs/protocol.md §11). Classes on every platform; patched native on linux-x86_64 only (glibc ≥ 2.28; upstream's needs 2.16), upstream natives elsewhere |
| Gradle | 9.8.1 | wrapper checksum-pinned |
| Fabric Loom | 1.18.3 | build-logic; both plugin IDs from one jar |
| Fabric Loader | 0.19.5 | compile; the mod requires ≥ 0.19.5 |

## Testkit

Each MCProtocolLib release speaks exactly one protocol version, so the testkit pins one release
per tested game version. The client, Velocity and the backend must agree on the protocol; Velocity
does not translate.

| Game version | Protocol | MCProtocolLib | Paper (backend) | Velocity |
| --- | --- | --- | --- | --- |
| 26.1.2 | 775 | 26.1-1 | 26.1.2 build 74 | 4.2.0 build 30 |

Notes:

- Paper has no 26.1 build. 26.1.1 and 26.1.2 share protocol 775 with 26.1, which MCProtocolLib
  26.1-1 targets.
- MCProtocolLib has no 26.2/26.3 release yet (Oct 2026). Those versions get testkit coverage when
  it has one.
- The Fabric testkit image (`testkit/docker/fabric/`) runs Minecraft 26.1.2 with Fabric Loader
  0.19.5 (launcher from fabric-installer 1.1.2) and the `fabric-26x` jar, joined directly by the
  bench client.
- Online-mode session server override, per platform (confirm per version):
  - Velocity 4.2.0: `-Dmojang.sessionserver=<base>/session/minecraft/hasJoined`. Velocity appends
    `?username=…&serverId=…` itself (checked in `InitialLoginSessionHandler`).
  - Vanilla / Fabric 26.1.2 (checked in the Fabric testkit image): all three of
    `-Dminecraft.api.session.host`, `-Dminecraft.api.services.host` and
    `-Dminecraft.api.profiles.host`, set to the mock's base URL. With any one missing, the server
    logs "Ignoring hosts properties" and uses Mojang's.
  - Paper standalone: not used; the Phase 0 backend sits behind Velocity in offline mode.
