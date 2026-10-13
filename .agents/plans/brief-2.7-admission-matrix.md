GOAL: Write the full unit-test matrix for the new listener admission code (test4cheap rules: tests must compile, pass, and be PROVEN able to fail).
SCOPE: create ONLY these test files in this worktree (branch agent/phase4-admission-matrix), and commit there:
- core/src/test/java/rs/sudoe/quicraft/core/transport/IngressGuardTest.java
- core/src/test/java/rs/sudoe/quicraft/core/transport/AdmissionTokenHandlerTest.java
- core/src/test/java/rs/sudoe/quicraft/core/transport/TransportConfigAdmissionTest.java
Never edit production code (core/src/main/**). Never edit existing tests.
READ FIRST: core/src/main/java/rs/sudoe/quicraft/core/transport/{IngressGuard,AdmissionTokenHandler,ConnectionAdmission,HmacRetryTokenHandler,TransportConfig,QuicServer}.java; existing tests HmacRetryTokenHandlerTest.java and ConnectionAdmissionTest.java in the same test package (copy their style, JUnit 5, Java 8 test source level: no var/records/List.of). Netty's EmbeddedChannel is available (io.netty.channel.embedded.EmbeddedChannel).
CASES:
IngressGuardTest (use EmbeddedChannel with the guard; build real QUIC long-header Initial packet bytes by hand: first byte 0xC0 | (type<<4) with type 0 = Initial, version 0x00000001 (4 bytes BE), DCID len + DCID (8 bytes), SCID len + SCID, token length varint 0, length varint, then padding to >= 1200 bytes; a Handshake packet = type 2; a short-header packet = first byte 0x40; garbage = 10 random bytes):
 1. Initial within budget is forwarded unchanged: same object, readerIndex/writerIndex unchanged, refCnt 1.
 2. Over per-IP budget (guard built with ConnectionAdmission(1000, 2, 1, 1, 1000, 100) and a fixed clock): third Initial from same IP is dropped and released exactly once (refCnt 0), nothing forwarded; an Initial from another IP is still forwarded.
 3. Over global budget: same, global=2.
 4. Handshake, short-header and garbage datagrams are always forwarded, even when the budget is exhausted, and never counted (prove: exhaust budget with Initials, then send 100 short-header packets, all forwarded).
 5. Non-DatagramPacket messages (e.g. a String) are forwarded.
 6. Window rollover: after advancing the clock 1 s, the IP is admitted again.
 7. Fail-open: a guard whose ConnectionAdmission throws (subclassing is impossible: final class; instead use a DatagramPacket with sender whose getAddress() is null via InetSocketAddress.createUnresolved) forwards the packet.
AdmissionTokenHandlerTest (call writeToken/validateToken directly with Unpooled buffers; DCID 8 bytes; address 192.0.2.1:5000 and 192.0.2.2:5000; ConnectionAdmission with an injected clock via its 7-arg constructor):
 8. ADAPTIVE, below retryThreshold: writeToken returns false, out unchanged; retries counter 0.
 9. ADAPTIVE at/above threshold: writeToken returns true and writes a token; counter increments.
 10. ALWAYS: true on the first attempt. NEVER: false when within limits; but true when the per-IP attempt limit is exceeded (forced Retry on denial).
 11. Open-slot full (hold maxOpenPerIp leases via admission.tryAcquire in the test): writeToken forces Retry (true) in every mode; validateToken of a valid token returns -1; after closing a held lease, validateToken of a fresh valid token returns the offset (41).
 12. validateToken: valid token within limits returns 41; tampered token returns -1 and spends NO attempt (prove via allowAttempt budget afterwards); valid token over attempt limit returns -1.
 13. The probe in openSlotFree leaves no lease behind: after 100 writeToken calls within limits, admission.tryAcquire still succeeds maxOpenPerIp times for that IP.
 14. maxTokenLength equals 61.
TransportConfigAdmissionTest:
 15. Defaults: 200, 20, 50, 2048, 32, 8192, 4000, 200, ADAPTIVE (read via the package-private fields/getters the class exposes).
 16. Builder: non-positive values fall back to defaults; retryThreshold > globalAttempts is clamped to globalAttempts.
 17. System-property parsing is static-at-class-load, so do NOT test properties by setting System properties at runtime unless the class exposes a parse helper; if it does, test invalid/out-of-range/valid strings through it; otherwise skip 17 and say so.
RUN: ./gradlew :core:test --tests '*IngressGuardTest' --tests '*AdmissionTokenHandlerTest' --tests '*TransportConfigAdmissionTest' then ./gradlew :core:test (all). Both green.
FAILURE PROOF (mandatory, never edit core/src/main in place): for 3 mutations, copy the worktree's IngressGuard.java or AdmissionTokenHandler.java to /tmp/claude-1000/-home-tna-QUICraft/83a7b533-1a96-4729-8a83-9dfc38acde4b/scratchpad/mut/, apply the mutation in the WORKTREE file, run the relevant test class, record the failing test names, then restore the file with `git checkout -- <file>` and confirm `git status` shows only your new test files. Mutations: (m1) IngressGuard forwards instead of releasing when over budget; (m2) AdmissionTokenHandler.validateToken skips the openSlotFree check; (m3) writeToken ignores a denied allowAttempt.
CONSTRAINTS: SPDX header `// SPDX-License-Identifier: GPL-3.0-or-later` on each new file. No new dependencies. No Thread.sleep-based timing; use injected clocks. Never push or merge. Gradle only in this worktree.
DONE WHEN: all cases (or documented skips) pass, mutation proof recorded, one commit "test(core): admission integration unit matrix".
REPORT: RESULT, test counts per class from the XML reports, mutation table (mutation -> failing tests), skips with reasons, BRANCH + COMMIT. Max 30 lines.
