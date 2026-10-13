// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable release version: {@code MAJOR.MINOR.PATCH} with an optional {@code -alpha.N},
 * {@code -beta.N} or {@code -rc.N} suffix.
 *
 * <p>Each number is 0..99999 without leading zeros. Ordering: numeric triple first, then a stable
 * release above {@code rc}, above {@code beta}, above {@code alpha}, then the suffix number. Build
 * metadata, whitespace and any other text are rejected.
 */
public final class UpdateVersion implements Comparable<UpdateVersion> {
    /** Maximum accepted input length in characters. */
    static final int MAX_LENGTH = 256;
    /** Maximum value of any numeric component. */
    static final int MAX_COMPONENT = 99_999;

    private static final int CHANNEL_ALPHA = 0;
    private static final int CHANNEL_BETA = 1;
    private static final int CHANNEL_RC = 2;
    private static final int CHANNEL_STABLE = 3;

    private static final Pattern PATTERN = Pattern.compile(
            "([0-9]{1,5})\\.([0-9]{1,5})\\.([0-9]{1,5})"
                    + "(?:-(alpha|beta|rc)\\.([0-9]{1,5}))?");

    private final int major;
    private final int minor;
    private final int patch;
    private final int channel;
    private final int preNumber;

    private UpdateVersion(int major, int minor, int patch, int channel, int preNumber) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.channel = channel;
        this.preNumber = preNumber;
    }

    /**
     * Parses a version string.
     *
     * @param text the version text; must not be null
     * @return the parsed version
     * @throws UpdateRejectedException on any grammar or range violation, with a fixed message
     */
    public static UpdateVersion parse(String text) throws UpdateRejectedException {
        if (text == null) {
            throw new UpdateRejectedException("version is null");
        }
        if (text.length() > MAX_LENGTH) {
            throw new UpdateRejectedException("version exceeds length limit");
        }
        Matcher m = PATTERN.matcher(text);
        if (!m.matches()) {
            throw new UpdateRejectedException("invalid version");
        }
        int major = component(m.group(1));
        int minor = component(m.group(2));
        int patch = component(m.group(3));
        String label = m.group(4);
        if (label == null) {
            return new UpdateVersion(major, minor, patch, CHANNEL_STABLE, 0);
        }
        int channel;
        switch (label) {
            case "alpha":
                channel = CHANNEL_ALPHA;
                break;
            case "beta":
                channel = CHANNEL_BETA;
                break;
            default:
                channel = CHANNEL_RC;
                break;
        }
        return new UpdateVersion(major, minor, patch, channel, component(m.group(5)));
    }

    private static int component(String digits) throws UpdateRejectedException {
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw new UpdateRejectedException("version component has a leading zero");
        }
        int value = Integer.parseInt(digits);
        if (value > MAX_COMPONENT) {
            throw new UpdateRejectedException("version component exceeds limit");
        }
        return value;
    }

    @Override
    public int compareTo(UpdateVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(channel, other.channel);
        if (c != 0) {
            return c;
        }
        return Integer.compare(preNumber, other.preNumber);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UpdateVersion)) {
            return false;
        }
        UpdateVersion other = (UpdateVersion) obj;
        return major == other.major
                && minor == other.minor
                && patch == other.patch
                && channel == other.channel
                && preNumber == other.preNumber;
    }

    @Override
    public int hashCode() {
        int h = major;
        h = 31 * h + minor;
        h = 31 * h + patch;
        h = 31 * h + channel;
        h = 31 * h + preNumber;
        return h;
    }

    /** Returns the canonical text, which equals the accepted input. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        switch (channel) {
            case CHANNEL_ALPHA:
                sb.append("-alpha.").append(preNumber);
                break;
            case CHANNEL_BETA:
                sb.append("-beta.").append(preNumber);
                break;
            case CHANNEL_RC:
                sb.append("-rc.").append(preNumber);
                break;
            default:
                break;
        }
        return sb.toString();
    }
}
