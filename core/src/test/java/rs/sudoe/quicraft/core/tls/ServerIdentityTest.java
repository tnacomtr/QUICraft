// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerIdentityTest {
    @Test
    void generatesAValidSelfSignedP256Certificate() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        X509Certificate cert = identity.certificate();
        cert.verify(cert.getPublicKey());
        cert.checkValidity(new Date());
        assertEquals(3, cert.getVersion());
        assertEquals("SHA256withECDSA", cert.getSigAlgName());
        assertEquals("EC", cert.getPublicKey().getAlgorithm());
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertTrue(cert.getNotAfter().getTime() > Date.from(java.time.Instant.parse("9999-01-01T00:00:00Z")).getTime());
        assertEquals(Fingerprint.of(cert.getEncoded()), identity.fingerprint());
    }

    @Test
    void persistsAndReloadsTheSameIdentity(@TempDir Path dir) throws Exception {
        ServerIdentity first = ServerIdentity.loadOrCreate(dir);
        assertTrue(Files.isRegularFile(dir.resolve(ServerIdentity.KEY_FILE)));
        assertTrue(Files.isRegularFile(dir.resolve(ServerIdentity.CERT_FILE)));
        ServerIdentity again = ServerIdentity.loadOrCreate(dir);
        assertEquals(first.fingerprint(), again.fingerprint());
        assertArrayEquals(first.privateKey().getEncoded(), again.privateKey().getEncoded());
    }

    @Test
    void deletingTheFilesRotatesTheFingerprint(@TempDir Path dir) throws Exception {
        ServerIdentity first = ServerIdentity.loadOrCreate(dir);
        Files.delete(dir.resolve(ServerIdentity.CERT_FILE));
        assertNotEquals(first.fingerprint(), ServerIdentity.loadOrCreate(dir).fingerprint());
    }

    @Test
    void fingerprintRoundTripsAndRejectsMalformedText() throws Exception {
        Fingerprint fp = ServerIdentity.generate().fingerprint();
        assertEquals(fp, Fingerprint.parse(fp.toString()));
        assertTrue(fp.toString().matches("sha256:[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse("sha256:ABC"));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(fp.toString().toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse("sha1:" + fp.toString().substring(7)));
    }
}
