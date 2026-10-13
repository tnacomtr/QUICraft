# Server security and firewall guidance (draft)

**Status: draft for server owners, not yet reviewed for release.** The connection limits and Retry in
section 3 are wired into both listener binds, pending review on branch
`agent/phase4-listener-admission`. Do not rely on any limit here until the Phase 4 gates in
`docs/release.md` section 4 pass.

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

- Allow UDP on the QUIC port. That is the same number as the TCP game port (default `25565/udp`), or
  the configured alternative port when the query collision moves QUIC (default: game port + 1). Keep
  the TCP game port open. Do not close TCP to make QUIC work: TCP is the compatibility path.
- Add the rule for **both IPv4 and IPv6** if the server is reachable over both; most firewalls keep
  the two rule sets separate (see IPv6 below).
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

## 3. Connection limits and Retry

The listener enforces per-IP and global limits on QUIC connection attempts and open connections,
plus an address-validation Retry. Every setting is a JVM system property, read once at listener
start, for example `-Dquicraft.admission.maxOpenPerIp=64`. They are wired into both listener binds,
pending review on branch `agent/phase4-listener-admission`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `quicraft.admission.retry` | `adaptive` | Retry policy: `adaptive`, `always` or `never` (below). |
| `quicraft.admission.globalAttemptsPerSecond` | 200 | New connection attempts per second, all IPs. |
| `quicraft.admission.perIpAttemptsPerSecond` | 20 | New connection attempts per second, per IP (see the attempt budget below). |
| `quicraft.admission.retryThresholdPerSecond` | 50 | All-IP attempt rate at which `adaptive` starts Retry. |
| `quicraft.admission.maxOpen` | 2048 | Open QUIC connections (pending plus established), all IPs. |
| `quicraft.admission.maxOpenPerIp` | 32 | Open QUIC connections (pending plus established), per IP. |
| `quicraft.admission.maxTrackedIps` | 8192 | IPs tracked at once for the per-IP counters. |
| `quicraft.admission.ingressInitialsPerSecond` | 4000 | Initial datagrams per second, all IPs (packet guard). |
| `quicraft.admission.ingressInitialsPerIpPerSecond` | 200 | Initial datagrams per second, per IP (packet guard). |

- **`retry=adaptive` (default).** The server sends a QUIC Retry (address validation, which costs the
  client one extra round trip and its 0-RTT early data) only once connection attempts in the current
  second reach `retryThresholdPerSecond`, or when an attempt is over a limit. `retryThresholdPerSecond`
  is clamped to `globalAttemptsPerSecond`.
- **`retry=always`.** Every new connection gets a Retry. Use this under attack: the server validates
  the client's address before spending connection state on it.
- **`retry=never`.** No load-based Retry. Attempts over a limit still get a Retry and are then
  dropped.
- **Ingress guard.** `ingressInitialsPerSecond` and `ingressInitialsPerIpPerSecond` drop excess QUIC
  Initial datagrams before any QUIC processing. This is a cheap packet-level guard; other packets
  (established connections) are never counted.
- **Attempt budget.** One join under Retry can spend up to 3 connection attempts. So the default
  `perIpAttemptsPerSecond=20` allows roughly 6-10 joins per second per IP while Retry is active.
- **Many players behind one IP** (shared NAT: schools, LAN parties, carrier NAT): raise
  `maxOpenPerIp` and `perIpAttemptsPerSecond` so one address does not starve the others. Raise
  `maxOpen` too if the whole server, not one IP, is the limit.
- **Invalid or out-of-range values** fall back to the default with one WARNING in the log.
- A refused connection (over any attempt or open-connection limit) gets at most a Retry and is then
  silently dropped, before any QUIC connection exists. A QUICraft client falls back to TCP after its
  short head start (`docs/protocol.md` section 5). **TCP joins are never limited by these settings.**
- **Retry tokens:** HMAC-SHA256, bound to the client IP and port, 30 s lifetime. The key is random
  per listener start, so a restart invalidates outstanding tokens (harmless: the client retries or
  falls back to TCP).

These limits are a **mitigation**, not DDoS protection. They reduce the cost of abusive connection
attempts for honest players. They are **not** DDoS prevention (section 4).

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

- The section 3 settings are wired pending review (branch `agent/phase4-listener-admission`) and are
  not active in any release. Their defaults have not been load-tested in this project.
- IPv6 QUIC behavior has not been tested by this project (see section 2).
- Hosting-rule compliance is not verified here (see `docs/updater.md` section 11).
