// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link UpdaterOrigins#resolve}: the production hosts by default, the dev override is strict. */
class UpdaterOriginsTest {

    private static final String VALID = "modrinth=https://localhost:9001,github=https://localhost:9002,"
            + "sudoe=https://localhost:9003";
    private static final URI FROM = URI.create("https://github.com/tnacomtr/QUICraft/releases/download/v1/a.jar");

    private final List<String> infos = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private final UpdaterLog log = new UpdaterLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    };

    private Optional<ReleaseSetup> resolve(String value) {
        return UpdaterOrigins.resolve(value, log);
    }

    @Test
    void noPropertyMeansTheProductionHostsWithOneInfoLine() {
        ReleaseSetup setup = resolve(null).orElseThrow();
        assertEquals(List.of("github", "sudoe"),
                setup.hosts().hosts().stream().map(ReleaseHost::key).toList());
        assertEquals(List.of("https://github.com", "https://git.sudoe.rs"),
                setup.hosts().hosts().stream().map(host -> host.origin().toString()).toList());
        assertInstanceOf(ProductionReleaseEndpoints.class, setup.endpoints());
        assertEquals(List.of("github", "sudoe"), setup.redirects().keySet().stream().sorted().toList());
        assertEquals(List.of(UpdaterOrigins.PRODUCTION_IN_USE), infos);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void theGithubHostFollowsOnlyItsCdnAndTheSudoeHostOnlyItself() {
        ReleaseSetup setup = UpdaterOrigins.production();
        ReleaseHost github = setup.hosts().byKey("github").orElseThrow();
        ReleaseHost sudoe = setup.hosts().byKey("sudoe").orElseThrow();
        RedirectPolicy githubRedirects = setup.redirects().get("github");
        RedirectPolicy sudoeRedirects = setup.redirects().get("sudoe");
        assertTrue(githubRedirects.allows(github, FROM,
                URI.create("https://release-assets.githubusercontent.com/x")));
        assertFalse(githubRedirects.allows(github, FROM, URI.create("https://git.sudoe.rs/x")));
        assertTrue(sudoeRedirects.allows(sudoe, FROM, URI.create("https://git.sudoe.rs/x")));
        assertFalse(sudoeRedirects.allows(sudoe, FROM,
                URI.create("https://release-assets.githubusercontent.com/x")));
    }

    @Test
    void aValidLocalhostTripleIsAcceptedInOrderWithOneWarning() {
        ReleaseSetup setup = resolve(VALID).orElseThrow();
        assertEquals(List.of("modrinth", "github", "sudoe"),
                setup.hosts().hosts().stream().map(ReleaseHost::key).toList());
        assertInstanceOf(DevReleaseEndpoints.class, setup.endpoints());
        assertTrue(setup.redirects().isEmpty(), "the dev setup refuses every redirect");
        assertEquals(List.of(UpdaterOrigins.DEV_IN_USE), warnings);
        assertTrue(infos.isEmpty());
    }

    @Test
    void aNonLocalhostOriginIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID.replace("github=https://localhost",
                "github=https://example.com")).isPresent());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("dev release origins ignored: "));
        assertTrue(infos.isEmpty(), "an invalid override never falls back to the production hosts");
    }

    @Test
    void aMissingKeyIsIgnoredWithAWarning() {
        assertFalse(resolve("modrinth=https://localhost:9001,github=https://localhost:9002").isPresent());
        assertTrue(warnings.get(0).contains("missing"));
    }

    @Test
    void aMalformedUriIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID.replace("9001", "bad port")).isPresent());
        assertEquals(1, warnings.size());
    }

    @Test
    void anHttpOriginIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID.replace("https://localhost:9003", "http://localhost:9003")).isPresent());
        assertEquals(1, warnings.size());
    }

    @Test
    void aDuplicateKeyIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID + ",modrinth=https://localhost:9004").isPresent());
        assertEquals(1, warnings.size());
    }

    @Test
    void anUnknownKeyIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID + ",other=https://localhost:9004").isPresent());
        assertEquals(1, warnings.size());
    }
}
