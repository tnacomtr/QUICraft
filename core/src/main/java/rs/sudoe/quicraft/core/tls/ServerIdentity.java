// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * The server's QUIC identity: an EC P-256 key and a self-signed X.509 v3 certificate
 * (docs/protocol.md §7). Clients trust it only by the fingerprint in the advertisement, so names
 * and dates carry no meaning; the certificate never expires in practice.
 *
 * <p>Encoded here rather than through Netty's {@code SelfSignedCertificate}, which needs
 * BouncyCastle, JDK-internal classes or the {@code keytool} binary.
 */
public final class ServerIdentity {
    static final String KEY_FILE = "quic-key.pem";
    static final String CERT_FILE = "quic-cert.pem";

    // ecdsa-with-SHA256 (RFC 5758) and id-at-commonName.
    private static final byte[] ECDSA_SHA256 = Der.sequence(Der.oid(1, 2, 840, 10045, 4, 3, 2));
    private static final byte[] COMMON_NAME = Der.oid(2, 5, 4, 3);
    /** RFC 5280 §4.1.2.5: 99991231235959Z means "no well-defined expiration date". */
    private static final Instant NO_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final Fingerprint fingerprint;

    private ServerIdentity(PrivateKey privateKey, X509Certificate certificate) throws GeneralSecurityException {
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = Fingerprint.of(certificate);
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    /** Loads the identity from {@code dir}, or generates and saves a new one if there is none. */
    public static ServerIdentity loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        Path keyFile = dir.resolve(KEY_FILE);
        Path certFile = dir.resolve(CERT_FILE);
        if (Files.isRegularFile(keyFile) && Files.isRegularFile(certFile)) {
            return load(keyFile, certFile);
        }
        ServerIdentity identity = generate();
        identity.save(dir);
        return identity;
    }

    public static ServerIdentity generate() throws GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair keys = generator.generateKeyPair();

        byte[] serial = new byte[16];
        random.nextBytes(serial);
        byte[] name = Der.sequence(Der.set(Der.sequence(COMMON_NAME, Der.utf8String("QUICraft"))));
        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // v3
                Der.integer(new BigInteger(1, serial)),
                ECDSA_SHA256,
                name,
                Der.sequence(Der.time(Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)),
                        Der.time(NO_EXPIRY)),
                name,
                keys.getPublic().getEncoded()); // already a DER SubjectPublicKeyInfo

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate(), random);
        signer.update(tbs);
        byte[] der = Der.sequence(tbs, ECDSA_SHA256, Der.bitString(signer.sign()));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        certificate.verify(keys.getPublic());
        return new ServerIdentity(keys.getPrivate(), certificate);
    }

    void save(Path dir) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        writeAtomically(dir.resolve(KEY_FILE), pem("PRIVATE KEY", privateKey.getEncoded()));
        writeAtomically(dir.resolve(CERT_FILE), pem("CERTIFICATE", certificate.getEncoded()));
    }

    private static ServerIdentity load(Path keyFile, Path certFile) throws IOException, GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(unpem(keyFile, "PRIVATE KEY")));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(unpem(certFile, "CERTIFICATE")));
        return new ServerIdentity(key, cert);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] unpem(Path file, String type) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            throw new IOException("no " + type + " in " + file);
        }
        return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.US_ASCII));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
