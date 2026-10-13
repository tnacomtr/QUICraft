// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/** The bytes of one successful fetch. The array is copied in and copied out. */
public final class FetchedBytes {
    private final byte[] bytes;

    /**
     * @param bytes the fetched bytes; copied
     */
    public FetchedBytes(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /** @return a copy of the fetched bytes */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** @return the number of fetched bytes */
    public int size() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return "FetchedBytes[size=" + bytes.length + "]";
    }
}
