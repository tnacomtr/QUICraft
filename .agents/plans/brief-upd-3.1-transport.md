# Brief upd-3.1: HTTPS transport (HttpSourceFetcher)

GOAL: implement `SourceFetcher` (from unit 3.0) as `HttpSourceFetcher` per plan `.agents/plans/2026-10-13-updater-runtime.md` section "Wave 3 to 5: frozen", items Shared + 3.1.
SCOPE: new files in `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir.
BRANCH/WORKTREE: `agent/phase4-upd-transport` in `/home/tna/QUICraft/.worktrees/phase4-upd-transport` (contains the 3.0 types). `./gradlew` and `git` from there only, relative paths.
READ FIRST: the plan section; `SourceFetcher.java`, `FetchRequest.java`, `FetchOutcome.java`, `FetchException.java`, `RedirectPolicy.java`, `CancelToken.java`, `ReleaseHost.java`.

IMPLEMENTATION:
1. `HttpSourceFetcher implements SourceFetcher`. Constructor `HttpSourceFetcher(HttpClient client)` plus static `create()` building `HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(...)` (use the request's connect timeout: build the client per request OR take the max; simplest: build one client per fetch with the request's connect timeout). Package-private constructor taking a `Clock`-like `LongSupplier nanoTime` for deadline tests.
2. Per request loop, hop = 0..MAX_HOPS: refuse a non-`https` URI with REDIRECT_REFUSED (first hop: TRANSPORT_ERROR is wrong - use REDIRECT_REFUSED only for redirect targets; for the initial URI throw IllegalArgumentException since FetchRequest already validated it). Send GET with `HttpRequest.newBuilder(uri).timeout(remaining overall deadline)`, header `Accept-Encoding: identity`, response body as `HttpResponse.BodyHandlers.ofInputStream()`.
   - Status 301/302/303/307/308 with a `Location` header: resolve against the current URI; target must be https and `request.redirects().allows(host, current, target)`; hop count > 3 or refused -> FetchException(REDIRECT_REFUSED). Close the body.
   - Status 200: read the stream in 16 KiB chunks; before each chunk check `cancel.isCancelled()` -> CANCELLED; track idle time: use a single-thread `ScheduledExecutorService` watchdog or read with the overall deadline only — REQUIRED behaviours: overall deadline exceeded -> TIMEOUT; more than `maxBytes` -> OVERSIZE (stop reading at maxBytes + 1, never write/keep that extra byte). For `download`, write chunks to the destination channel; for `fetch`, accumulate in a ByteArrayOutputStream capped at maxBytes.
   - Any other status -> HTTP_STATUS. `HttpTimeoutException` -> TIMEOUT. Other IOException -> TRANSPORT_ERROR. InterruptedException -> restore interrupt flag, CANCELLED.
   - Content-Length larger than maxBytes -> OVERSIZE before reading.
   Idle-read timeout: implement with a watchdog that closes the InputStream if no bytes arrive for `idleReadTimeout`; the resulting IOException maps to TIMEOUT (track a flag set by the watchdog).
3. No logging of URIs with query strings; FetchException messages are fixed.

TESTS (`HttpSourceFetcherTest`): use the JDK's `com.sun.net.httpserver.HttpsServer` on 127.0.0.1 with a throwaway self-signed certificate GENERATED IN THE TEST at runtime (keytool is not allowed; generate with the JDK: there is no public cert-generation API, so instead use plain `HttpServer` (http) and test the https-only rule separately): structure the fetcher so the scheme check is in a package-private method and tests construct it with a package-private flag `allowPlainHttpForTests` that ONLY the test constructor sets. Cover: 200 small body; Content-Length over cap -> OVERSIZE; chunked body over cap -> OVERSIZE with exactly maxBytes received and nothing more written to the destination; redirect denied by default policy -> REDIRECT_REFUSED; redirect allowed by a test policy followed; 4 redirects -> REDIRECT_REFUSED; redirect to an `http:` target refused even when the policy allows (with the test flag set for the initial hop only — the flag must not relax redirect targets); 404 -> HTTP_STATUS; server that stalls -> TIMEOUT within the idle timeout; overall deadline shorter than a slow trickling body -> TIMEOUT; cancel token set mid-body -> CANCELLED; download writes exactly the body bytes.
Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green; no test may contact anything except 127.0.0.1.
Commit, trailer (your own model in Co-Authored-By):
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9
CONSTRAINTS: JDK only (java.net.http, com.sun.net.httpserver in tests). No external network. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
