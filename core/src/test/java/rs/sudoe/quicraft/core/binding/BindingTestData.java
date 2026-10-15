// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/** Fixed inputs and hex helpers shared by the binding tests. */
final class BindingTestData {
    /** Minecraft's 16-byte shared secret S. */
    static final String S_HEX = "000102030405060708090a0b0c0d0e0f";
    /** Exporter value E. */
    static final String E_HEX = "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f";
    /** Fingerprint F. */
    static final String F_HEX = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f";
    /** Routing digest R. */
    static final String R_HEX = "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f";

    static final int PROTOCOL_NUMBER = 775;
    static final int MODE_ONLINE = 1;
    static final int MODE_OFFLINE = 0;

    private BindingTestData() {
    }

    static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** A source that hands out a private copy of {@code value} on every call, so no machine can wipe the fixture. */
    static ExporterSource exporter(byte[] value) {
        return () -> value.clone();
    }
}