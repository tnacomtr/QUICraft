// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;

/**
 * Everything the platform adapter supplies to {@link UpdaterRuntime#start}. Adapters stay thin: they
 * find the live jar, map logging and admin notices, and say whether the update directory existed
 * before anything created it.
 *
 * <p>The facade never uses a classloader {@code getResource} to read its own metadata. It reads
 * {@code META-INF/quicraft/build.json} and {@code META-INF/quicraft/updater-trust.json} from the
 * discovered live jar through {@link OwnJarResources}, because a loader can return another mod's copy.
 */
public interface UpdaterPlatform {

    /** @return {@code velocity}, {@code fabric-26x} or {@code fabric-1.21.11} */
    String platform();

    /** @return the game target this jar was built for, for example {@code 26.1} */
    String gameTarget();

    /** @return the update directory (created by the store, never by the adapter) */
    Path updateDir();

    /** @return the platform's own config file, used only in the first-start disclosure text */
    Path configFile();

    /**
     * Must be checked before anything creates the update directory.
     *
     * @return true when the update directory already existed when the server started
     */
    boolean updateDirExistedBeforeStart();

    /** @return the finder of the running server's own jar */
    LiveJarDiscovery discovery();

    /** @return the health verdict source for a boot-pending generation */
    HealthSignal health();

    /** @return the platform log sink */
    UpdaterLog log();

    /**
     * Tells the online admins that a new version is available. The adapter also delivers it to admins
     * who join later. Called once per version.
     *
     * @param message the notice text
     */
    void notifyAdmins(String message);
}
