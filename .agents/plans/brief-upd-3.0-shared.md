# Brief upd-3.0: Wave 3 shared records and interfaces

GOAL: create the shared types listed in `.agents/plans/2026-10-13-updater-runtime.md`, section "Wave 3 to 5: frozen at Wave 2.5", paragraph "Shared (unit 3.0, first)", EXCEPT the lock items (StateStore/TransactionLock are another unit). Types only plus their validation; no fetching, no file I/O.
SCOPE: new files in `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir. No existing file edits.
BRANCH/WORKTREE: `agent/phase4-upd-shared` in `/home/tna/QUICraft/.worktrees/phase4-upd-shared`. Run `./gradlew` and `git` only from there with relative paths.
READ FIRST: that plan section; `UpdateDocuments.java`, `UpdateVersion.java`, `StateRecords.java` (style: SPDX header, javadoc, compact-constructor validation, defensive copies).

TYPES (exact names; one file each unless trivially small):
1. `CancelToken` interface `boolean isCancelled();` and `MutableCancelToken implements CancelToken` (volatile boolean, `cancel()`).
2. `ReleaseHost(String key, URI origin)` record: key must be one of "modrinth", "github", "sudoe"; origin must be absolute `https`, have a host, no userinfo, path empty or "/", no query, no fragment. Normalize in the compact constructor: lowercase scheme and host, drop port 443 (keep any other explicit port), path "". Invalid -> IllegalArgumentException with a fixed message (never echo the URI).
   `ReleaseHosts(List<ReleaseHost> hosts)` record: List.copyOf; 1..3 hosts; reject duplicate keys and duplicate normalized origins; method `Optional<ReleaseHost> byKey(String)`.
3. `ReleaseEndpoints` interface with the five methods in the plan (`revocations`, `revocationsSignature`, `manifest`, `manifestSignature`, `artifact(ReleaseHost, UpdateDocuments.ReleaseEntry)`).
4. `RedirectPolicy` interface `boolean allows(ReleaseHost host, URI from, URI to);` plus `static RedirectPolicy denyAll()` and constant `int MAX_HOPS = 3`. Javadoc: HTTPS on every hop is enforced by the fetcher, not the policy; a redirect never changes host identity.
5. `FetchRequest(ReleaseHost host, URI uri, long maxBytes, Duration connectTimeout, Duration idleReadTimeout, Duration overallDeadline, RedirectPolicy redirects)`: non-null; uri absolute https; maxBytes 1..(1L<<31); durations positive.
6. `FetchOutcome` enum: OK, TRANSPORT_ERROR, TIMEOUT, OVERSIZE, HTTP_STATUS, REDIRECT_REFUSED, CANCELLED.
   `FetchException extends Exception` with `FetchOutcome outcome()` (never OK; constructor rejects OK), fixed messages.
   `FetchedBytes` final class: copies bytes in, `bytes()` returns a copy, `size()`.
7. `SourceFetcher` interface: `FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException;` and `long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel) throws FetchException;` with javadoc from the plan (cancel between chunks; OVERSIZE on byte maxBytes+1 without writing it; caller owns destination).
8. `DocumentPair` final class (copy in/out of both byte arrays; equals via Arrays.equals; toString without bytes). `HostEvidence(ReleaseHost host, FetchOutcome outcome, DocumentPair pair)`: pair non-null iff outcome OK. `HostCollection(List<HostEvidence> responses)`: List.copyOf; at most one entry per host key.
9. `RuntimeIdentity(String platform, String target, UpdateVersion runningVersion, UpdateDocuments.Trust runningTrust, String liveSha512AtInit)`: platform in "velocity", "fabric-26x", "fabric-1.21.11"; target non-empty; liveSha512AtInit 128 lowercase hex.
10. `ArtifactIdentity(String platform, UpdateVersion version, long size, String sha512)`: size > 0, sha512 128 hex, platform as above.
11. `ManifestEvidence` final class: two byte arrays copied in/out, equals via Arrays.equals, toString without bytes.
12. `InstallCapability` enum: VERIFIED_STAGING_ONLY, ATOMIC_REPLACEMENT (javadoc from the plan).
13. `InitBarrier` interface `boolean readyForStaging();`. `HealthSignal` interface `CompletionStage<Boolean> awaitHealthy(Duration timeout);`.

TESTS (`SharedTypesTest`, or one per type): every validation rule; ReleaseHost normalization (`HTTPS://Example.ORG:443/` -> `https://example.org`), http rejected, userinfo/query/fragment/path rejected, unknown key rejected; ReleaseHosts duplicate key and duplicate normalized origin (`https://a.example` vs `https://A.example:443/`) rejected, 0 and 4 hosts rejected; FetchException rejects OK; HostEvidence pair/outcome consistency; byte-array copy semantics for FetchedBytes, DocumentPair, ManifestEvidence; RuntimeIdentity/ArtifactIdentity validation.
Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit with trailer (your own model name in Co-Authored-By):
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9
CONSTRAINTS: JDK only. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
