// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fixtures for the release-tool tests: the RFC 8032 section 7.1 TEST 1 and TEST 2 throwaway vectors
 * (the same published seeds the updater tests already use), a trust file built from them, and
 * fixture jars.
 *
 * <p>No key is generated and no private key is stored: these seeds are the published RFC 8032 test
 * vectors, already committed in the updater's tests, and are never usable for a release.
 */
final class ReleaseFixture {

    /** RFC 8032 section 7.1 TEST 1 seed and public key (throwaway, published). */
    static final String SEED_1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";

    static final String PUB_1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String KID_1 = "21fe31dfa154a261";

    /** RFC 8032 section 7.1 TEST 2 seed and public key (throwaway, published). */
    static final String SEED_2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";

    static final String PUB_2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    static final String KID_2 = "39f713d0a644253f";

    /** A revocation candidate key from the updater's parser tests: kid published with its pub. */
    static final String PUB_REVOKED =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";

    static final String KID_REVOKED = "dac073e0123bdea5";

    /** Well-formed kid that is in no test trust file. */
    static final String KID_UNKNOWN = "0123456789abcdef";

    static final String ISSUED = "2026-11-01T12:00:00Z";
    static final String NEXT_DAY = "2026-11-02T12:00:00Z";

    static final String SHA512_A = "cd".repeat(64);
    static final String SHA512_B = "ab".repeat(64);

    /** Test 1 holds release only; test 2 holds release and revocation. */
    static final String TRUST_JSON = "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":1"
            + ",\"built\":\"2026-10-01T00:00:00Z\",\"revocationsSeqFloor\":0"
            + ",\"manifestSeqFloor\":0,\"keys\":["
            + "{\"kid\":\"" + KID_1 + "\",\"pub\":\"" + PUB_1 + "\",\"roles\":[\"release\"]},"
            + "{\"kid\":\"" + KID_2 + "\",\"pub\":\"" + PUB_2
            + "\",\"roles\":[\"release\",\"revocation\"]}]}";

    static final String BUILD_JSON = "{\"type\":\"quicraft.build\",\"v\":1"
            + ",\"platform\":\"fabric-26x\",\"version\":\"0.1.0-alpha.1\"}";

    private static final String PKCS8_PREFIX = "302e020100300506032b657004220420";

    private ReleaseFixture() {
    }

    /** The result of one in-process {@link ReleaseTool#run}. */
    record Result(int code, String out, String err) {

        boolean ok() {
            return code == ReleaseTool.EXIT_OK;
        }
    }

    /** Runs the tool in-process, capturing both streams. */
    static Result run(String... argv) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            code = ReleaseTool.run(argv, out, err);
        }
        return new Result(code, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    /** Writes a fixture trust file and returns its path. */
    static Path trustFile(Path dir, String name) throws IOException {
        return write(dir, name, utf8(TRUST_JSON));
    }

    /** A compact {@code *.sig} envelope, built here rather than by the tool, for verify tests. */
    static byte[] signatureEnvelope(String payloadSha256, String... entries) {
        return utf8("{\"type\":\"quicraft.sig\",\"v\":1,\"payloadSha256\":\"" + payloadSha256
                + "\",\"signatures\":[" + String.join(",", entries) + "]}");
    }

    /** One signature entry with an already-hex signature. */
    static String signatureEntry(String kid, String sigHex) {
        return "{\"kid\":\"" + kid + "\",\"sig\":\"" + sigHex + "\"}";
    }

    /** One signature entry signed by the given RFC 8032 seed. */
    static String signatureEntry(String kid, String seedHex, byte[] message)
            throws GeneralSecurityException {
        return signatureEntry(kid, hex(sign(seedHex, message)));
    }

    /** The Ed25519 signature of {@code message} by the given seed (Java provider, no OpenSSL). */
    static byte[] sign(String seedHex, byte[] message) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8(seedHex)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    /** A fixture jar with the build metadata, the given trust file bytes and a payload entry. */
    static Path jar(Path dir, String name, String buildJson, byte[] trustBytes) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "META-INF/quicraft/build.json", utf8(buildJson));
            put(zip, "META-INF/quicraft/updater-trust.json", trustBytes);
            put(zip, "payload.bin", utf8("quicraft release-tool test payload"));
        }
        return file;
    }

    static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(dir);
        Files.write(file, bytes);
        return file;
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] pkcs8(String seedHex) {
        String full = PKCS8_PREFIX + seedHex;
        byte[] out = new byte[full.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(full.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
