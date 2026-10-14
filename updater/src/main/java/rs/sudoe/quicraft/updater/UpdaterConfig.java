// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Updater settings from a {@code .properties} file (docs/updater.md section 6). Loading only reads the
 * file: it never creates or rewrites it. A missing file gives the defaults silently. A file that cannot
 * be read or parsed gives the defaults with one WARN and no values echoed. An unrecognised
 * {@code update.check-interval} value fails closed to {@link CheckInterval#OFF} with one WARN that names
 * the key; the interval values are matched case-insensitively. An unrecognised {@code update.install}
 * value fails safe to {@link InstallMode#STAGE_ONLY} with one WARN that names the key; those values are
 * matched case-insensitively too. Other unknown booleans keep their default with one WARN that names the
 * key.
 */
public record UpdaterConfig(CheckInterval checkInterval, boolean checkSilent, boolean logBadSignatures,
        InstallMode installMode) {

    static final String KEY_INTERVAL = "update.check-interval";
    static final String KEY_SILENT = "update.check-silent";
    static final String KEY_BAD_SIGNATURES = "update.log-bad-signatures";
    static final String KEY_INSTALL = "update.install";

    /**
     * What {@code update} does with the verified jar. {@link #SWAP} is the default: the platform decides
     * whether the swap is available (see {@link InstallCapabilities}). {@link #STAGE_ONLY} is the owner
     * opt-out: the jar is staged and verified but never installed, on every platform.
     */
    public enum InstallMode {
        SWAP,
        STAGE_ONLY
    }

    /** The scheduled check period. */
    public enum CheckInterval {
        HOURLY(Duration.ofHours(1)),
        DAILY(Duration.ofDays(1)),
        OFF(null);

        private final Duration period;

        CheckInterval(Duration period) {
            this.period = period;
        }

        /** @return the period, or {@code null} for {@link #OFF} */
        public Duration period() {
            return period;
        }
    }

    /** The defaults: hourly, silent, bad signatures logged, swap where the platform allows it. */
    public static UpdaterConfig defaults() {
        return new UpdaterConfig(CheckInterval.HOURLY, true, true, InstallMode.SWAP);
    }

    public UpdaterConfig {
        Objects.requireNonNull(checkInterval, "checkInterval");
    }

    /**
     * Loads the file; warnings go nowhere.
     *
     * @param file the config file, possibly absent
     * @return the parsed settings, or the defaults
     */
    public static UpdaterConfig load(Path file) {
        return load(file, warning -> { });
    }

    /**
     * Loads the file.
     *
     * @param file the config file, possibly absent
     * @param warnings receives one fixed line per problem, naming the key or the file only
     * @return the parsed settings, or the defaults
     */
    public static UpdaterConfig load(Path file, Consumer<String> warnings) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(warnings, "warnings");
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (NoSuchFileException e) {
            return defaults();
        } catch (IOException | IllegalArgumentException e) {
            warnings.accept("updater config: the file could not be read or parsed; using the defaults");
            return defaults();
        }
        CheckInterval interval = parseInterval(props.getProperty(KEY_INTERVAL), warnings);
        boolean silent = parseBoolean(props.getProperty(KEY_SILENT), true, KEY_SILENT, warnings);
        boolean badSignatures = parseBoolean(props.getProperty(KEY_BAD_SIGNATURES), true, KEY_BAD_SIGNATURES,
                warnings);
        InstallMode install = parseInstall(props.getProperty(KEY_INSTALL), warnings);
        return new UpdaterConfig(interval, silent, badSignatures, install);
    }

    private static CheckInterval parseInterval(String raw, Consumer<String> warnings) {
        if (raw == null) {
            return CheckInterval.HOURLY;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "hourly":
                return CheckInterval.HOURLY;
            case "daily":
                return CheckInterval.DAILY;
            case "off":
                return CheckInterval.OFF;
            default:
                warnings.accept("updater config: unknown value for " + KEY_INTERVAL + "; checks are off");
                return CheckInterval.OFF;
        }
    }

    private static InstallMode parseInstall(String raw, Consumer<String> warnings) {
        if (raw == null) {
            return InstallMode.SWAP;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "swap":
                return InstallMode.SWAP;
            case "stage-only":
                return InstallMode.STAGE_ONLY;
            default:
                warnings.accept("updater config: unknown value for " + KEY_INSTALL
                        + "; the jar is staged, not installed");
                return InstallMode.STAGE_ONLY;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback, String key, Consumer<String> warnings) {
        if (raw == null) {
            return fallback;
        }
        switch (raw.trim()) {
            case "true":
                return true;
            case "false":
                return false;
            default:
                warnings.accept("updater config: unknown value for " + key + "; using the default");
                return fallback;
        }
    }
}
