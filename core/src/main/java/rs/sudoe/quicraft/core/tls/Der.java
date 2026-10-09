// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Just enough DER (X.690) to encode one self-signed X.509 certificate. */
final class Der {
    private Der() {}

    static byte[] sequence(byte[]... items) {
        return tagged(0x30, concat(items));
    }

    static byte[] set(byte[]... items) {
        return tagged(0x31, concat(items));
    }

    static byte[] integer(BigInteger value) {
        return tagged(0x02, value.toByteArray());
    }

    static byte[] bitString(byte[] bytes) {
        byte[] content = new byte[bytes.length + 1]; // leading byte: 0 unused bits
        System.arraycopy(bytes, 0, content, 1, bytes.length);
        return tagged(0x03, content);
    }

    static byte[] utf8String(String s) {
        return tagged(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    /** UTCTime or GeneralizedTime as RFC 5280 requires: UTCTime through 2049. */
    static byte[] time(java.time.Instant instant) {
        java.time.ZonedDateTime t = instant.atZone(java.time.ZoneOffset.UTC);
        if (t.getYear() < 2050) {
            return tagged(0x17, String.format("%02d%02d%02d%02d%02d%02dZ", t.getYear() % 100, t.getMonthValue(),
                    t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond()).getBytes(StandardCharsets.US_ASCII));
        }
        return tagged(0x18, String.format("%04d%02d%02d%02d%02d%02dZ", t.getYear(), t.getMonthValue(),
                t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond()).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int arc = arcs[i];
            int shift = 28;
            while (shift > 0 && (arc >>> shift) == 0) {
                shift -= 7;
            }
            for (; shift > 0; shift -= 7) {
                out.write(0x80 | ((arc >>> shift) & 0x7F));
            }
            out.write(arc & 0x7F);
        }
        return tagged(0x06, out.toByteArray());
    }

    /** Context-specific, constructed, explicit tag {@code [n]}. */
    static byte[] explicit(int n, byte[] content) {
        return tagged(0xA0 | n, content);
    }

    static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = len > 0xFFFFFF ? 4 : len > 0xFFFF ? 3 : len > 0xFF ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(len >>> (8 * i));
            }
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
