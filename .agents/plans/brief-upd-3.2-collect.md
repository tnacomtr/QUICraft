# Brief upd-3.2: metadata collection (all hosts, revocations then manifests)

GOAL: plan `.agents/plans/2026-10-13-updater-runtime.md` section "Wave 3 to 5: frozen", item 3.2; spec docs/updater.md 12.6 items 1-2 and 12.12 R1-R2/M1-M2 (fetch caps).
SCOPE: new files in `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir.
BRANCH/WORKTREE: `agent/phase4-upd-collect` in `/home/tna/QUICraft/.worktrees/phase4-upd-collect`. `./gradlew`/`git` from there only.
READ FIRST: plan section; `SourceFetcher.java` (its javadoc is the contract), `FetchRequest.java`, `FetchOutcome.java`, `FetchException.java`, `ReleaseHosts.java`, `ReleaseHost.java`, `ReleaseEndpoints.java`, `RedirectPolicy.java`, `HostEvidence.java`, `HostCollection.java`, `DocumentPair.java`, `CancelToken.java`; DocumentParser constants for document caps (grep MAX_BYTES / size caps for revocations 262144? and release manifest; use the parser's own caps; `.sig` cap 4096).

API: `final class MetadataCollector` with constructor `MetadataCollector(SourceFetcher fetcher, ReleaseEndpoints endpoints, Map<String, RedirectPolicy> redirectsByHostKey /* missing key = denyAll */, FetchTimeouts timeouts)` where `FetchTimeouts(Duration connect, Duration idle, Duration overall)` record (validated against FetchRequest limits; `static FetchTimeouts defaults()` = 10 s, 15 s, 60 s).
- `HostCollection collectRevocations(ReleaseHosts hosts, CancelToken cancel)` and `HostCollection collectManifests(ReleaseHosts hosts, CancelToken cancel)`.
- For every configured host, in configured order, sequentially: fetch the document (cap = parser cap for that document type), then the `.sig` (cap 4096). Both OK -> HostEvidence(host, OK, pair). Otherwise HostEvidence(host, <first failing outcome>, null). A failure on one host never stops the others. If cancel is set, stop and mark every not-yet-fetched host CANCELLED. Never retry (retries would not add votes anyway; keep it simple and bounded).
- No logging of URIs. No parsing here (acceptance does that).
TESTS with a scripted fake SourceFetcher (map URI -> bytes or FetchException; records calls): all three hosts OK; one host failing on the document; one failing on the .sig; cancel after the first host; caps passed in FetchRequest match the document type; redirect policy per host key passed through (default denyAll); timeouts defaults; ordering (revocations collection fetches only revocation URIs).
Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit, own-model trailer:
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9
CONSTRAINTS: JDK only. No real network. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
