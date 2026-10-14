# Brief 2.1b: harden listener admission after the security and peer reviews

Same worktree and branch as 2.1: `agent/phase4-listener-admission`. The orchestrator approves
changing the reviewed `ConnectionAdmission` contract where the fixes below need it. Update its
javadoc and `ConnectionAdmissionTest` to the new contract, and keep everything else in that test
intact.

FIXES (from the security review; file:line refers to your current branch):

1. **[high] One IP drains the global budget.** In `ConnectionAdmission.allowAttempt`:
   - Check per-IP tracking and the per-IP limit first. Only an attempt the per-IP check allows is
     charged to the global budget. Per-IP and tracking refusals no longer consume a global
     attempt.
   - Token-validated attempts (`validateToken`) must not be starvable by a token-less flood. Give
     them a separate global/per-IP attempt budget, for example a second ConnectionAdmission
     instance used only for attempts, while open-connection leases stay on one instance. Pick the
     simplest correct shape.
   - The IngressGuard gets the per-IP-first ordering for free.
2. **[high] Spoofed half-open handshakes fill maxOpen.**
   - Add a handshake deadline for EVERY server connection: close it if the handshake isn't
     complete 10 s after creation. Reuse the confirmMillis style, but keep the two separate. A test
     may shorten it through a package-private static.
   - In ADAPTIVE mode, also force Retry while unconfirmed (pending-handshake) leases are at or
     above 25% of maxOpen.
   - Track the pending count exactly: increment it on lease, decrement it on handshake success or
     close, exactly once each.
3. **[medium] Retry amplification.** In IngressGuard, drop (release once) any datagram whose first
   packet is a long-header INITIAL and whose datagram length is under 1200 bytes (RFC 9000 §14.1).
   This happens before the budget check and spends no budget. Fix protocol.md §9's "smaller than
   the client's Initial" wording to stay true.
4. **[medium] IPv6 /64.** Every per-IP limit (ingress, attempts, open leases) groups IPv6 by /64.
   - Use one package-private helper that maps an InetAddress to its limit key: IPv4 unchanged, IPv6
     masked to /64. IPv4-mapped IPv6 counts as IPv4.
   - Leases release the same key they acquired.
5. **[low] Cross-field check.** If maxTrackedIps < maxOpen + globalAttemptsPerSecond, log one
   WARNING at build/bind. Don't change the values.
6. **[low] Token replay with a new DCID:** don't change it. Document it in the
   AdmissionTokenHandler javadoc as accepted: bounded by the per-IP limits, and the source is
   proven.

FROM THE PEER REVIEW:

7. In the `EarlyDataTest` Retry rejoin test (~:381), assert `second.early.sent()` is true, and
   assert no early server release when Retry discards the flight.

TESTS: add a loopback or unit test for each of 1-4:
- (1) a single IP over its per-IP limit leaves the global budget for another IP, and validated
  attempts still pass while token-less attempts are exhausted.
- (2) a connection whose handshake never completes is closed and its lease released after the
  (shortened) deadline. Also: ADAPTIVE forces Retry once pending leases reach the threshold.
- (3) a 1199-byte Initial is dropped and released; a 1200-byte one passes.
- (4) two IPv6 addresses in one /64 share a per-IP limit; different /64s don't.

Then `./gradlew :core:test` and `./gradlew :core:check` in this worktree, both green. Commit in
small commits.

ALSO: another agent is writing unit tests on branch `agent/phase4-admission-matrix`, branched from
your b7a088c. Don't touch that branch. List in your report every behaviour change those tests
would need to follow.

REPORT: the standard format.
- A table: finding → fix → test (file:line).
- Test counts.
- CONCERNS.
- Behaviour changes affecting the matrix branch.
- BRANCH + COMMITS.
