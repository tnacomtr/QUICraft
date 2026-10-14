// SPDX-License-Identifier: GPL-3.0-or-later
//
// Build constants for the updater's build metadata and trust file (docs/updater.md 12.2 and the
// build metadata section). Shared by the plugins below; uses only groovy-json (part of Gradle's API).
import groovy.json.JsonSlurper
import java.io.File

object UpdaterTrust {
    /** The development trust file, relative to the repository root. Never released. */
    const val DEV_TRUST_PATH = "updater/dev-trust/updater-trust.json"

    /** Public keys of the RFC 8032 TEST 1 and TEST 2 vectors (updater/src/test TestKeys). */
    val TEST_PUBS = listOf(
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
    )

    /** Mirrors DocumentParser.PLATFORMS. */
    val PLATFORMS = setOf("velocity", "fabric-26x", "fabric-1.21.11")

    const val BUILD_ENTRY = "META-INF/quicraft/build.json"
    const val TRUST_ENTRY = "META-INF/quicraft/updater-trust.json"

    // Mirrors UpdateVersion.PATTERN (updater/src/main/java/rs/sudoe/quicraft/updater/UpdateVersion.java,
    // PATTERN at lines 26-28), the 256-character cap (MAX_LENGTH, line 17), and the leading-zero rule
    // (component(), lines 84-85). Each numeric component is 0 or 1 to 5 digits with no leading zero,
    // which is exactly the range 0 to MAX_COMPONENT (99999). Duplicated because build-logic must not
    // depend on the updater module. SYNC DUTY: change both together.
    private const val MAX_VERSION_LENGTH = 256
    private val VERSION_GRAMMAR = Regex(
        "(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})" +
            "(?:-(alpha|beta|rc)\\.(0|[1-9][0-9]{0,4}))?",
    )

    private val SAFE_VERSION_TEXT = Regex("[0-9A-Za-z.+-]+")

    /**
     * True when the text holds only characters a version may be templated with. Stricter than the
     * grammar on purpose: the version is written verbatim into build.json, so nothing else may pass.
     */
    fun isSafeVersionText(text: String): Boolean = SAFE_VERSION_TEXT.matches(text)

    /** True when UpdateVersion.parse would accept the text (length cap, then full grammar match). */
    fun isUpdaterVersion(text: String): Boolean =
        text.length <= MAX_VERSION_LENGTH && VERSION_GRAMMAR.matches(text)

    /**
     * Public keys (lowercase) declared by a trust document, parsed rather than substring-matched.
     * The runtime decodes JSON \u escapes (StrictJson), so a raw text search for a TEST key can be
     * bypassed by spelling it with escapes. The real trust file never needs escapes, so a backslash
     * is refused outright. Throws IllegalArgumentException when the text is not a trust document.
     */
    fun declaredPubs(text: String): List<String> {
        require(!text.contains('\\')) { "trust file contains a backslash; the real trust file needs no escapes" }
        val root = JsonSlurper().parseText(text) as? Map<*, *>
            ?: throw IllegalArgumentException("trust file is not a JSON object")
        val keys = root["keys"] as? List<*> ?: throw IllegalArgumentException("trust file has no keys array")
        return keys.map { key ->
            val pub = (key as? Map<*, *>)?.get("pub") as? String
                ?: throw IllegalArgumentException("trust file has a key without a pub string")
            pub.lowercase()
        }
    }

    /** The RFC 8032 TEST public keys that the trust text declares (decoded, and also literally). */
    fun testPubsIn(text: String): List<String> {
        val decoded = declaredPubs(text).filter { it in TEST_PUBS }
        val literal = TEST_PUBS.filter { text.lowercase().contains(it) }
        return (decoded + literal).distinct()
    }

    /**
     * True when the trust file is the development file (same canonical path as [devFile]) or
     * declares an RFC 8032 TEST key. Throws when the file cannot be decoded (callers fail closed).
     */
    fun isDevelopmentTrust(file: File, devFile: File): Boolean =
        file.canonicalPath == devFile.canonicalPath ||
            testPubsIn(file.readText(Charsets.UTF_8)).isNotEmpty()

    /** The exact build.json bytes (text) for a platform and version. */
    fun buildJson(platform: String, version: String): String =
        "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"$platform\",\"version\":\"$version\"}"
}
