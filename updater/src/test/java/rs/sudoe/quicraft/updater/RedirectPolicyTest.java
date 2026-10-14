// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The allowlist redirect rule (docs/updater.md 12.15): same origin plus the named CDN hosts, over
 * HTTPS, no userinfo, no fragment, no other port, no IP literal, no other configured host.
 */
class RedirectPolicyTest {

    private static final ReleaseHost GITHUB = new ReleaseHost("github", URI.create("https://github.com"));
    private static final ReleaseHost SUDOE = new ReleaseHost("sudoe", URI.create("https://git.sudoe.rs"));
    private static final URI FROM = URI.create(
            "https://github.com/tnacomtr/QUICraft/releases/download/v0.1.0-alpha.1/quicraft.jar");

    private static final RedirectPolicy GITHUB_POLICY = RedirectPolicy.allowlist(Set.of(
            "release-assets.githubusercontent.com", "objects.githubusercontent.com"));
    private static final RedirectPolicy SUDOE_POLICY = RedirectPolicy.allowlist(Set.of());

    private static boolean githubAllows(String target) {
        return GITHUB_POLICY.allows(GITHUB, FROM, URI.create(target));
    }

    @Test
    void theHostsOwnOriginAndTheTwoGithubCdnNamesAreFollowed() {
        assertTrue(githubAllows("https://github.com/tnacomtr/QUICraft/releases/download/v1/b.jar"));
        assertTrue(githubAllows(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1/2"));
        assertTrue(githubAllows("https://objects.githubusercontent.com/x?sig=abc"));
        assertTrue(SUDOE_POLICY.allows(SUDOE, FROM, URI.create("https://git.sudoe.rs/tna/QUICraft/x")));
    }

    @Test
    void aHostIsComparedLowercaseAndTheDefaultPortIsTheOnlyPort() {
        assertTrue(githubAllows("https://GitHub.com:443/x"));
        assertFalse(githubAllows("https://github.com:8443/x"));
        assertFalse(githubAllows("https://release-assets.githubusercontent.com:80/x"));
    }

    @Test
    void onlyHttpsAbsoluteTargetsWithoutUserinfoOrFragmentAreFollowed() {
        assertFalse(githubAllows("http://github.com/x"));
        assertFalse(githubAllows("https://user@github.com/x"));
        assertFalse(githubAllows("https://github.com/x#frag"));
        assertFalse(GITHUB_POLICY.allows(GITHUB, FROM, URI.create("/x")));
        assertFalse(GITHUB_POLICY.allows(GITHUB, FROM, URI.create("mailto:someone@example.com")));
    }

    @Test
    void anIpLiteralIsNeverFollowed() {
        assertFalse(githubAllows("https://192.168.0.1/x"));
        assertFalse(githubAllows("https://8.8.8.8/x"));
        assertFalse(githubAllows("https://[::1]/x"));
        assertFalse(githubAllows("https://[2001:db8::1]:443/x"));
    }

    @Test
    void anotherConfiguredHostIsNeverReachableFromEitherSide() {
        assertFalse(GITHUB_POLICY.allows(GITHUB, FROM, URI.create("https://git.sudoe.rs/x")));
        assertFalse(SUDOE_POLICY.allows(SUDOE, FROM, URI.create("https://github.com/x")));
        assertFalse(SUDOE_POLICY.allows(SUDOE, FROM,
                URI.create("https://release-assets.githubusercontent.com/x")));
    }

    @Test
    void aLookAlikeNameIsNotTheAllowlistedName() {
        assertFalse(githubAllows("https://evil-release-assets.githubusercontent.com/x"));
        assertFalse(githubAllows("https://release-assets.githubusercontent.com.evil.com/x"));
        assertFalse(githubAllows("https://release-assets.githubusercontent.com./x"));
        assertFalse(githubAllows("https://notgithub.com/x"));
    }
}
