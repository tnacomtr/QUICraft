// SPDX-License-Identifier: GPL-3.0-or-later
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Unit tests for the build-time trust policy (UpdaterTrust). The version grammar must mirror
 * UpdateVersion at runtime; the trust helpers must find every RFC 8032 TEST key the runtime would
 * refuse. Only the TEST public keys already in the repository are used; no key is generated here.
 */
class UpdaterTrustTest {

    private val devFile = File("../updater/dev-trust/updater-trust.json")

    private fun devText(): String {
        assertTrue(devFile.isFile, "the development trust file must exist at ${devFile.absolutePath}")
        return devFile.readText(Charsets.UTF_8)
    }

    // ---- version grammar (mirrors UpdateVersion.PATTERN, MAX_LENGTH and the leading-zero rule) ----

    @ParameterizedTest
    @ValueSource(
        strings = ["0.1.0", "1.2.3-alpha.1", "99999.0.0", "0.0.0-rc.99999", "1.0.0-beta.0", "0.0.0", "0.1.0-alpha.0"],
    )
    fun versionGrammarAcceptsWhatTheRuntimeAccepts(version: String) {
        assertTrue(UpdaterTrust.isUpdaterVersion(version), version)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "01.2.3",
            "1.02.0",
            "1.2.03",
            "100000.0.0",
            "0.1.0-SNAPSHOT",
            "0.1.0-alpha.01",
            "0.1.0-alpha",
            "0.1.0-dev.1",
            "1.2",
            "",
            " 0.1.0",
            "0.1.0 ",
            "0.1.0+build.1",
            "0.1.0_x",
            "v0.1.0",
            "0.1.0-alpha.1-x",
        ],
    )
    fun versionGrammarRejectsWhatTheRuntimeRejects(version: String) {
        assertFalse(UpdaterTrust.isUpdaterVersion(version), version)
    }

    @Test
    fun versionGrammarRejectsOver256Characters() {
        // Runtime MAX_LENGTH is 256; the length check runs before the grammar, so a 257-character
        // string is refused whatever it contains.
        assertFalse(UpdaterTrust.isUpdaterVersion("0.0.0" + "0".repeat(252)))
        assertFalse(UpdaterTrust.isUpdaterVersion("0".repeat(257)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.1.0", "1.2.3-alpha.1", "1.0.0+build.7", "2.0.0-rc.3"])
    fun safeVersionTextAcceptsTemplatableVersions(text: String) {
        assertTrue(UpdaterTrust.isSafeVersionText(text), text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.1.0_x", "0.1.0 ", "0.1.0/x", "0.1.0\"", "0.1.0\\", "", "0.1.0\n"])
    fun safeVersionTextRejectsOtherCharacters(text: String) {
        assertFalse(UpdaterTrust.isSafeVersionText(text), text)
    }

    // ---- trust declarations ----

    @Test
    fun trustTextWithABackslashIsRefused() {
        val text = devText() + "\\"
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.declaredPubs(text) }
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.testPubsIn(text) }
    }

    @Test
    fun developmentTrustDeclaresEveryTestKey() {
        assertEquals(UpdaterTrust.TEST_PUBS.toSet(), UpdaterTrust.testPubsIn(devText()).toSet())
    }

    @Test
    fun everyTestKeyIsFoundWhenDeclaredAsAKey() {
        for (pub in UpdaterTrust.TEST_PUBS) {
            val text = """{"keys":[{"kid":"00","pub":"$pub","roles":["release"]}]}"""
            assertEquals(listOf(pub), UpdaterTrust.testPubsIn(text), pub)
        }
    }

    @Test
    fun everyTestKeyIsFoundInUppercaseDeclaration() {
        for (pub in UpdaterTrust.TEST_PUBS) {
            val text = """{"keys":[{"kid":"00","pub":"${pub.uppercase()}","roles":["release"]}]}"""
            assertTrue(UpdaterTrust.testPubsIn(text).contains(pub), pub)
        }
    }

    @Test
    fun everyTestKeyIsFoundWhenOnlyLiterallyPresent() {
        for (pub in UpdaterTrust.TEST_PUBS) {
            val text = """{"note":"$pub","keys":[]}"""
            assertTrue(UpdaterTrust.testPubsIn(text).contains(pub), pub)
        }
    }

    @Test
    fun aNonTestKeyIsNotReported() {
        val other = "aa".repeat(32)
        assertTrue(UpdaterTrust.testPubsIn("""{"keys":[{"kid":"00","pub":"$other"}]}""").isEmpty())
    }

    @Test
    fun trustWithoutAKeysArrayIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.declaredPubs("""{"x":1}""") }
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.declaredPubs("not json") }
    }

    // ---- development trust classification ----

    @Test
    fun theDevelopmentFileIsDevelopmentTrust(@TempDir dir: File) {
        assertTrue(UpdaterTrust.isDevelopmentTrust(devFile, devFile))
        assertTrue(UpdaterTrust.isDevelopmentTrust(devFile.canonicalFile, devFile))
        // Also true for a copy that still holds a TEST key.
        val copy = File(dir, "copy.json").apply { writeText(devText()) }
        assertTrue(UpdaterTrust.isDevelopmentTrust(copy, devFile))
    }

    @Test
    fun aCopyHoldingATestKeyIsDevelopmentTrust(@TempDir dir: File) {
        val copy = File(dir, "test-key.json")
        copy.writeText("""{"keys":[{"kid":"x","pub":"${UpdaterTrust.TEST_PUBS[1]}","roles":["release"]}]}""")
        assertTrue(UpdaterTrust.isDevelopmentTrust(copy, devFile))
    }

    @Test
    fun aNonTestTrustCopyIsNotDevelopmentTrust(@TempDir dir: File) {
        val copy = File(dir, "release.json")
        copy.writeText("""{"keys":[{"kid":"x","pub":"${"aa".repeat(32)}","roles":["release"]}]}""")
        assertFalse(UpdaterTrust.isDevelopmentTrust(copy, devFile))
    }

    @Test
    fun anUndecodableTrustThrowsSoCallersFailClosed(@TempDir dir: File) {
        val broken = File(dir, "broken.json").apply { writeText("{\"keys\":[{\"pub\":\"\\u0041\"}]}") }
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.isDevelopmentTrust(broken, devFile) }
    }

    // ---- platform -> outer jar mapping ----

    @Test
    fun outerJarTasksMatchTheReleaseGuard() {
        assertEquals("shadowJar", UpdaterTrust.outerJarTask("velocity"))
        assertEquals("jar", UpdaterTrust.outerJarTask("fabric-26x"))
        assertEquals("remapJar", UpdaterTrust.outerJarTask("fabric-1.21.11"))
        assertEquals(setOf("velocity", "fabric-26x", "fabric-1.21.11"), UpdaterTrust.PLATFORMS)
    }

    @Test
    fun anUnknownPlatformHasNoOuterJar() {
        assertThrows(IllegalArgumentException::class.java) { UpdaterTrust.outerJarTask("paper") }
    }
}
