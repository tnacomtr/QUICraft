// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link UpdaterOrigins#resolve}: production has none, the dev override is strict. */
class UpdaterOriginsTest {

    private static final String VALID = "modrinth=https://localhost:9001,github=https://localhost:9002,"
            + "sudoe=https://localhost:9003";

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

    private Optional<ReleaseHosts> resolve(String value) {
        return UpdaterOrigins.resolve(value, log);
    }

    @Test
    void noPropertyMeansNoOriginsAndOneInfoLine() {
        assertFalse(resolve(null).isPresent());
        assertEquals(List.of(UpdaterOrigins.NOT_CONFIGURED), infos);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aValidLocalhostTripleIsAcceptedInOrderWithOneWarning() {
        ReleaseHosts hosts = resolve(VALID).orElseThrow();
        assertEquals(List.of("modrinth", "github", "sudoe"),
                hosts.hosts().stream().map(ReleaseHost::key).toList());
        assertEquals(List.of(UpdaterOrigins.DEV_IN_USE), warnings);
        assertTrue(infos.isEmpty());
    }

    @Test
    void aNonLocalhostOriginIsIgnoredWithAWarning() {
        assertFalse(resolve(VALID.replace("github=https://localhost",
                "github=https://example.com")).isPresent());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("dev release origins ignored: "));
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
