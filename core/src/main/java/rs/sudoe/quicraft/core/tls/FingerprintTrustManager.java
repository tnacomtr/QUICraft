// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Client-side trust: the server's leaf certificate must match the advertised fingerprint.
 * Names, dates and chains are not checked (docs/protocol.md §7).
 *
 * <p>One instance serves every connection to servers with that fingerprint (the TLS context and
 * its session cache are shared, docs/protocol.md §8), so a mismatch is recorded per engine.
 */
public final class FingerprintTrustManager extends X509ExtendedTrustManager {
    private final Fingerprint expected;
    private final Map<SSLEngine, FingerprintMismatchException> mismatches =
            Collections.synchronizedMap(new WeakHashMap<SSLEngine, FingerprintMismatchException>());
    /** Mismatch from a check without an engine. */
    private volatile FingerprintMismatchException mismatch;
    private final AtomicInteger checks = new AtomicInteger();

    public FingerprintTrustManager(Fingerprint expected) {
        this.expected = expected;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        try {
            checkServerTrusted(chain, authType);
        } catch (FingerprintMismatchException e) {
            if (engine != null) {
                mismatches.put(engine, e);
            }
            throw e;
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checks.incrementAndGet();
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

    /** As {@link #mismatch()}, for the connection that used {@code engine}. */
    public FingerprintMismatchException mismatch(SSLEngine engine) {
        return mismatches.get(engine);
    }

    /** How many certificate checks ran; a resumed session runs none. */
    public int checks() {
        return checks.get();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        checkClientTrusted(chain, authType);
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
