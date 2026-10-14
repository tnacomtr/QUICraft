// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.charset.StandardCharsets;

/**
 * A development build: the running jar's build metadata carries a {@code SNAPSHOT} version, which the
 * release parser has no channel for. The facade reports this as {@link UpdaterRuntime#REASON_DEV_BUILD}
 * instead of the generic discovery refusal, so a development jar says why the updater is off.
 *
 * <p>The message is fixed and carries no path, like every other updater refusal.
 */
public final class DevelopmentBuildException extends RuntimeException {

    /** The fixed message. */
    static final String MESSAGE = "development build";

    public DevelopmentBuildException() {
        super(MESSAGE);
    }

    /**
     * The one development-build test, used by both {@link OwnJarVersion} and the facade.
     *
     * @param buildJson the raw {@code META-INF/quicraft/build.json} bytes
     * @return true when the bytes contain {@code SNAPSHOT}
     */
    static boolean looksLikeDevelopmentBuild(byte[] buildJson) {
        return new String(buildJson, StandardCharsets.UTF_8).contains("SNAPSHOT");
    }
}
