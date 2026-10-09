// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SHA-256 of a certificate's DER encoding, written {@code sha256:<64 lowercase hex>} in the
 * advertisement (docs/protocol.md §1, §7).
 */
public final class Fingerprint {
    private static final String PREFIX = "sha256:";
    private static final Pattern FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");

    private final byte[] digest;

    private Fingerprint(byte[] digest) {
        this.digest = digest;
    }

    public static Fingerprint of(X509Certificate certificate) throws CertificateEncodingException {
        return of(certificate.getEncoded());
    }

    public static Fingerprint of(byte[] der) {
        try {
            return new Fingerprint(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Parses {@code sha256:<64 lowercase hex>}; throws on anything else. */
    public static Fingerprint parse(String text) {
        if (text == null || !FORMAT.matcher(text).matches()) {
            throw new IllegalArgumentException("not a sha256 fingerprint: " + text);
        }
        byte[] digest = new byte[32];
        for (int i = 0; i < 32; i++) {
            digest[i] = (byte) Integer.parseInt(text.substring(PREFIX.length() + 2 * i, PREFIX.length() + 2 * i + 2), 16);
        }
        return new Fingerprint(digest);
    }

    /** Constant-time comparison against a certificate's DER encoding. */
    public boolean matches(byte[] der) {
        return MessageDigest.isEqual(digest, of(der).digest);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Fingerprint && MessageDigest.isEqual(digest, ((Fingerprint) o).digest);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + 64).append(PREFIX);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
