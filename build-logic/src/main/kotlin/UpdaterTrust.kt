// SPDX-License-Identifier: GPL-3.0-or-later
//
// Build constants for the updater's build metadata and trust file (docs/updater.md 12.2 and the
// build metadata section). Plain Kotlin with no Gradle API, shared by the plugins below.
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

    // Mirrors UpdateVersion.PATTERN (updater/src/main/java/rs/sudoe/quicraft/updater/UpdateVersion.java:26-28).
    // Duplicated because build-logic must not depend on the updater module. Keep the two in sync.
    private val VERSION_GRAMMAR = Regex(
        "([0-9]{1,5})\\.([0-9]{1,5})\\.([0-9]{1,5})(?:-(alpha|beta|rc)\\.([0-9]{1,5}))?",
    )

    /** True when UpdateVersion.parse would accept the text (full match). */
    fun isUpdaterVersion(text: String): Boolean = VERSION_GRAMMAR.matches(text)

    /** The exact build.json bytes (text) for a platform and version. */
    fun buildJson(platform: String, version: String): String =
        "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"$platform\",\"version\":\"$version\"}"
}
