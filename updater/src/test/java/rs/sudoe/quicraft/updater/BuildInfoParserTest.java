// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Strict parsing of the build metadata resource ({@code META-INF/quicraft/build.json}). */
class BuildInfoParserTest {

    private static String build(String body) {
        return "{\"type\":\"quicraft.build\",\"v\":1," + body + "}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void rejects(String json) {
        assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseBuild(bytes(json)));
    }

    @Test
    void parsesValidBuildMetadata() throws Exception {
        BuildInfo info = DocumentParser.parseBuild(
                bytes(build("\"platform\":\"fabric-1.21.11\",\"version\":\"0.4.0-rc.2\"")));
        assertEquals("fabric-1.21.11", info.platform());
        assertEquals(UpdateVersion.parse("0.4.0-rc.2"), info.version());
    }

    @Test
    void rejectsUnknownKey() {
        rejects(build("\"platform\":\"velocity\",\"version\":\"0.4.0\",\"extra\":\"x\""));
    }

    @Test
    void rejectsMissingKey() {
        rejects(build("\"platform\":\"velocity\""));
    }

    @Test
    void rejectsWrongType() {
        rejects("{\"type\":\"quicraft.build\",\"v\":2,\"platform\":\"velocity\",\"version\":\"0.4.0\"}");
        rejects("{\"type\":\"quicraft.trust\",\"v\":1,\"platform\":\"velocity\",\"version\":\"0.4.0\"}");
    }

    @Test
    void rejectsNonStringVersion() {
        rejects(build("\"platform\":\"velocity\",\"version\":4"));
    }

    @Test
    void rejectsBadVersion() {
        rejects(build("\"platform\":\"velocity\",\"version\":\"not-a-version\""));
    }

    @Test
    void rejectsUnknownPlatform() {
        rejects(build("\"platform\":\"paper\",\"version\":\"0.4.0\""));
    }

    @Test
    void rejectsNullValue() {
        rejects(build("\"platform\":null,\"version\":\"0.4.0\""));
    }

    @Test
    void rejectsDuplicateKey() {
        rejects(build("\"platform\":\"velocity\",\"platform\":\"velocity\",\"version\":\"0.4.0\""));
    }

    @Test
    void rejectsOversizedDocument() {
        rejects(build("\"platform\":\"velocity\",\"version\":\"0.4.0\"") + " ".repeat(1025));
    }
}
