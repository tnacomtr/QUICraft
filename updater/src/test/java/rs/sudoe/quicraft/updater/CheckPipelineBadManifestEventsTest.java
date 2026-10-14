// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;

/** {@link CheckResult#badManifests()}: one typed event per host whose manifest fails verification. */
class CheckPipelineBadManifestEventsTest {

    @TempDir
    Path tmp;

    private CheckPipeline pipeline(ScriptedFetcher fetcher) throws Exception {
        StateStore store = new StateStore(PipelineFixtures.privateDir(tmp.resolve("upd")));
        store.initialiseIfAbsent(new StateRecords.StateDelta(PipelineFixtures.seedState(),
                StateRecords.RevokedState.empty()), true);
        return new CheckPipeline(store, PipelineFixtures.collector(fetcher), PipelineFixtures.HOSTS,
                PipelineFixtures.identity(), PipelineFixtures.CLOCK);
    }

    /**
     * The standard manifest with its sequence changed and signed with the wrong key: the envelope matches
     * the document, so the failure is a bad signature, not a torn pair.
     */
    private static byte[] alteredManifest() {
        String text = PipelineFixtures.text(PipelineFixtures.manifest());
        return SignedFixtures.bytes(text.replace("\"seq\":42", "\"seq\":43"));
    }

    private static byte[] wrongKeySignature(byte[] document) throws Exception {
        return SignedFixtures.envelopeFor(document,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_2, document));
    }

    @Test
    void aRejectedManifestIsReportedOncePerHostWithItsDocumentDigest() throws Exception {
        byte[] bad = alteredManifest();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        for (ReleaseHost host : PipelineFixtures.HOSTS.hosts()) {
            fetcher.answer(PipelineFixtures.ENDPOINTS.manifest(host), bad);
            fetcher.answer(PipelineFixtures.ENDPOINTS.manifestSignature(host), wrongKeySignature(bad));
        }

        CheckResult result = pipeline(fetcher).run(CheckMode.REPORT, UpdateVersion.parse("0.3.0"),
                new MutableCancelToken());

        String digest = Hex.sha256(bad);
        assertEquals(List.of(new BadManifest("modrinth", digest), new BadManifest("github", digest),
                new BadManifest("sudoe", digest)), result.badManifests());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("manifest document rejected")));
    }

    @Test
    void aValidManifestProducesNoBadManifestEvents() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        CheckResult result = pipeline(fetcher).run(CheckMode.REPORT, UpdateVersion.parse("0.3.0"),
                new MutableCancelToken());

        assertTrue(result.badManifests().isEmpty(), result.badManifests().toString());
    }
}
