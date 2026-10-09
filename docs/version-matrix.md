# Version matrix

Which game versions, loaders and Java targets each module covers. Keep it current with every
module and every new release.

## Modules

| Module | Covers | Loader / platform | Java bytecode | Status |
| --- | --- | --- | --- | --- |
| `core` | all | none (no Minecraft classes) | 8 | skeleton |
| `testkit` | 26.1.x (protocol 775) | none (never shipped) | 25 | Phase 0 |
| `bridge-netty41`, `bridge-netty42`, … | by game Netty version | none | per game | Phase 1 |
| `velocity` | client protocols Velocity accepts | Velocity 4.x | 25 | Phase 2 |
| `fabric-*` (26.x + 1.21.11 hook group) | 26.1–26.3, 1.21.11 | Fabric | 25 / 21 | Phase 3 |

The 26.x + 1.21.11 Fabric hook group shares one source set but builds separate jars: 1.21.11 is
obfuscated (Intermediary names, Java 21) and 26.1+ is not (Java 25). If the mixin targets turn out
to differ, the actual split goes here.

## Pinned dependencies

| Dependency | Version | Notes |
| --- | --- | --- |
| Netty (incl. `netty-codec-classes-quic`, `netty-codec-native-quic`) | 4.2.19.Final | BoringSSL `d03dbc3e` (2026-05-12, after the Apache-2.0 relicense), quiche `be47c501`; QUIC classes are Java 8 bytecode; natives for linux-x86_64, linux-aarch_64, osx-x86_64, osx-aarch_64, windows-x86_64 |
| Gradle | 9.8.1 | wrapper checksum-pinned |

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
- Online-mode session server override, per platform (confirm per version):
  - Velocity 4.2.0: `-Dmojang.sessionserver=<base>/session/minecraft/hasJoined`. Velocity appends
    `?username=…&serverId=…` itself (checked in `InitialLoginSessionHandler`).
  - Vanilla / Paper standalone: `-Dminecraft.api.session.host` (Paper also needs
    `minecraft.api.services.host`). Not used yet: the Phase 0 backend sits behind Velocity in
    offline mode.
