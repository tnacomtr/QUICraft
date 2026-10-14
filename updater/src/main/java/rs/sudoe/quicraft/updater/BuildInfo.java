// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * The build metadata of one of the project's own jars ({@code META-INF/quicraft/build.json}).
 * Structure only: {@link DocumentParser#parseBuild} produces it and the artifact verifier compares it
 * with the release entry.
 */
public record BuildInfo(String platform, UpdateVersion version) {

    public BuildInfo {
        SharedChecks.requirePlatform(platform);
        Objects.requireNonNull(version, "version");
    }
}
