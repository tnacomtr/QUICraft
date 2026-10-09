// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/**
 * Client-side trust: the server's leaf certificate must match the advertised fingerprint.
 * Names, dates and chains are not checked (docs/protocol.md §7).
 */
public final class FingerprintTrustManager implements X509TrustManager {
    private final Fingerprint expected;
    private volatile FingerprintMismatchException mismatch;

    public FingerprintTrustManager(Fingerprint expected) {
        this.expected = expected;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("server sent no certificate");
        }
        byte[] der;
        try {
            der = chain[0].getEncoded();
        } catch (CertificateEncodingException e) {
            throw new CertificateException("unreadable server certificate", e);
        }
        if (!expected.matches(der)) {
            FingerprintMismatchException e = new FingerprintMismatchException(expected, Fingerprint.of(der));
            mismatch = e;
            throw e;
        }
    }

    /**
     * The mismatch this trust manager rejected, if any. BoringSSL reports a rejection only as a
     * generic CERTIFICATE_VERIFY_FAILED, so the client asks here for the real reason.
     */
    public FingerprintMismatchException mismatch() {
        return mismatch;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("QUICraft clients do not present certificates");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /** The server's certificate is not the advertised one. */
    public static final class FingerprintMismatchException extends CertificateException {
        private static final long serialVersionUID = 1L;

        FingerprintMismatchException(Fingerprint expected, Fingerprint actual) {
            super("certificate fingerprint mismatch: advertised " + expected + ", got " + actual);
        }
    }
}
