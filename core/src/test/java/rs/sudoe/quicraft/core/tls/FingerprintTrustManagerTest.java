// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;

class FingerprintTrustManagerTest {
    @Test
    void mismatchIsRecordedForItsOwnConnectionOnly() throws Exception {
        ServerIdentity expected = ServerIdentity.generate();
        ServerIdentity other = ServerIdentity.generate();
        FingerprintTrustManager trust = new FingerprintTrustManager(expected.fingerprint());
        SSLEngine first = SSLContext.getDefault().createSSLEngine();
        SSLEngine second = SSLContext.getDefault().createSSLEngine();

        FingerprintMismatchException e = assertThrows(FingerprintMismatchException.class,
                () -> trust.checkServerTrusted(new X509Certificate[] {other.certificate()}, "EC", first));
        assertSame(e, trust.mismatch(first));

        trust.checkServerTrusted(new X509Certificate[] {expected.certificate()}, "EC", second);
        assertNull(trust.mismatch(second), "a later connection must not inherit the mismatch");
        assertEquals(2, trust.checks());
    }
}
