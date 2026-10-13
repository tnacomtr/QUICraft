# Server security and firewall guidance (draft)

**Status: draft for server owners, not yet reviewed for release.** Limits described as planned are not
active. Do not rely on any limit here until the Phase 4 gates in `docs/release.md` section 4 pass.

## 1. Ports

- **QUIC port.** By default QUIC uses the same number as the TCP game port, over UDP (for example
  `25565/udp` on a default server, per `README.md`).
- **Query collision.** If the platform's query protocol is enabled on the same UDP port, QUIC binds an
  alternative port and advertises it instead.
  - Velocity: `[query]` enabled on the game port. `alternative-port` in
    `plugins/quicraft/config.properties`; `0` means game port + 1.
  - Fabric dedicated server: `enable-query` on the game port. `alternative-port` in
    `config/quicraft/server.properties`; `0` means game port + 1.
  - If the bind fails, the server logs a warning and runs TCP-only. Startup is never failed over it
    (`docs/protocol.md` section 2).
- The server never intercepts query traffic.

## 2. Firewall rules

General firewall practice, shown as examples only. These have not been tested on any platform in this
preparation unit.

- Allow UDP on the QUIC port (or the alternative port, if the query collision applies). Keep the TCP
  game port open. Do not close TCP to make QUIC work: TCP is the compatibility path.
- Example with `ufw` (adjust the port): `ufw allow 25565/udp`. Equivalent `nftables` or `iptables` rules
  accept UDP to the chosen port.
- If a TCP-only frontend (for example TCPShield, playit.gg, or Cloudflare Spectrum) sits in front of
  the server, it cannot relay QUIC. Clients use TCP automatically (`CLAUDE.md`, Discovery point 3).

### IPv6 (standard operator guidance; not checked in this preparation unit)

- If the server is reachable over IPv6, the firewall rule for UDP on that port needs an IPv6 entry as
  well. IPv4 and IPv6 rules are separate in most firewalls.
- This preparation unit did not check whether QUIC binds IPv6 sockets, which addresses are advertised,
  or how clients choose a family. Do not assume IPv6 QUIC works until the Phase 4 test matrix covers it.
- The TCP IPv6 path is unchanged and must keep working.

## 3. Limits (planned, not active)

Per-IP and global limits on connection attempts and open connections, and a production Retry token
handler, are Phase 4 release blockers. As of this document:

- The listener still uses `NoRetryTokenHandler` (`core/.../transport/QuicServer.java:133`).
  Retry is not enforced.
- `ConnectionAdmission` is implemented and unit-tested, but not wired into the listener. It does
  not yet enforce runtime limits.
- No platform setting for these limits exists yet. No setting name is documented here.

When they ship, limits are a **mitigation**. They reduce the cost of abusive connection attempts for
honest players. They are **not** DDoS prevention.

## 4. DDoS and spoofed traffic

- UDP is cheap to send. Spoofed or real UDP can be sent in volume to an open port, and server-side
  limits cannot absorb traffic that saturates the link.
- Volumetric attacks need upstream protection at the network or hosting provider. This project does
  not provide it.
- Blocking UDP entirely is a valid choice. Clients then use TCP.
- The TCP game port has the same exposure, and the same upstream protection applies to it.

## 5. TCP fallback invariants

These must hold for every configuration (`CLAUDE.md`):

- The vanilla TCP listener is untouched.
- A QUIC failure (no advertisement, UDP blocked, handshake failure, fingerprint mismatch, unsupported
  platform) falls back to TCP silently.
- A bug in this project must never stop a player from joining over TCP. Fault-injection tests cover
  this (`docs/release.md` section 3).

## 6. Update checks: disclosure and HTTPS

- The updater (planned, not implemented; `docs/updater.md`) checks release hosts on its own schedule by
  default.
- Each check sends the server's IP, the running version, and the platform to those hosts.
- **Disclosure is required before release** in the README and on project pages. It is not present yet.
  The plugin also says so once on first start.
- Downloads use HTTPS to fixed hosts only. Planned hosts: Modrinth, GitHub, and `git.sudoe.rs`.
- Checks can be turned off with a config key (conceptual; not yet wired).
- The updater never runs the new jar in the current process.

## 7. Owner responsibilities for keys

- The updater trusts only signatures from the primary and backup keys built into the jar.
- Keep the primary key offline (owner's machine or hardware key). Keep the backup key offline on
  separate media, with a paper copy. Neither enters the repo or CI.
- If a private key leaks, follow the rotation steps in `docs/updater.md` section 4. If both keys are
  lost or leaked, updates cannot be trusted, and the owner installs the next release by hand.
- The first install is trusted by source. Download QUICraft only from the release hosts above.

## 8. Not claimed

- No specific rate, port, or connection number is recommended until the limits are tested.
- IPv6 QUIC behavior has not been tested by this project (see section 2).
- Hosting-rule compliance is not verified here (see `docs/updater.md` section 11).
