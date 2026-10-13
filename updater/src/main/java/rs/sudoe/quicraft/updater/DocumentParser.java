// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict schema parsers for the updater documents (docs/updater.md 12.2 and 12.3).
 *
 * <p>A successful parse proves only structure, types, encodings, bounds and uniqueness. It does not
 * prove authenticity, freshness, sequence order, lifetime or persistence; those are separate
 * policy layers. Every failure throws {@link UpdateRejectedException} with a fixed message and never
 * echoes document contents.
 */
public final class DocumentParser {

    private static final String TYPE_TRUST = "quicraft.trust";
    private static final String TYPE_SIGNATURES = "quicraft.sig";
    private static final String TYPE_RELEASE = "quicraft.release";
    private static final String TYPE_REVOCATIONS = "quicraft.revocations";

    private static final int TRUST_MAX_BYTES = 8192;
    private static final int SIGNATURES_MAX_BYTES = 4096;
    private static final int RELEASE_MAX_BYTES = 65536;
    private static final int REVOCATIONS_MAX_BYTES = 65536;
    private static final int MAX_STRING_CHARS = 256;

    private static final long MAX_SEQ = 9007199254740991L;
    private static final long MAX_EPOCH = Integer.MAX_VALUE;
    private static final long MAX_RELEASE_SIZE = 67108864L;

    private static final int MIN_TRUST_KEYS = 1;
    private static final int MAX_TRUST_KEYS = 4;
    private static final int MAX_SIGNATURES = 2;
    private static final int MAX_RELEASES = 16;
    private static final int MAX_TARGETS = 64;
    private static final int MAX_TARGET_CHARS = 32;
    private static final int MAX_WITHDRAWN = 64;
    private static final int MAX_REVOKED_KEYS = 16;
    private static final int MAX_BLOCKED_ARTIFACTS = 256;

    private static final int KID_BYTES = 8;
    private static final int PUBLIC_KEY_BYTES = 32;
    private static final int SIGNATURE_BYTES = 64;
    private static final int SHA256_BYTES = 32;
    private static final int SHA512_BYTES = 64;

    private static final String ROLE_RELEASE = "release";
    private static final String ROLE_REVOCATION = "revocation";

    private static final Set<String> PLATFORMS = Set.of("velocity", "fabric-26x", "fabric-1.21.11");

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,124}\\.jar");
    private static final Pattern TAG = Pattern.compile("v[0-9A-Za-z.-]{1,40}");
    private static final Pattern TIMESTAMP_SHAPE =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");
    static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final String INVALID = "invalid updater document";

    private DocumentParser() {
    }

    /**
     * Parses a trust file ({@code updater-trust.json}).
     *
     * @throws UpdateRejectedException on any structural violation
     */
    public static UpdateDocuments.Trust parseTrust(byte[] bytes) throws UpdateRejectedException {
        Map<String, Object> root = parseRoot(bytes, TRUST_MAX_BYTES);
        requireHeader(root, TYPE_TRUST, "epoch", "built", "revocationsSeqFloor",
                "manifestSeqFloor", "keys");

        long epoch = uintValue(root.get("epoch"), 1, MAX_EPOCH);
        Instant built = timestampValue(root.get("built"));
        long revocationsSeqFloor = uintValue(root.get("revocationsSeqFloor"), 0, MAX_SEQ);
        long manifestSeqFloor = uintValue(root.get("manifestSeqFloor"), 0, MAX_SEQ);

        List<?> rawKeys = listValue(root.get("keys"), MIN_TRUST_KEYS, MAX_TRUST_KEYS);
        List<UpdateDocuments.TrustedKey> keys = new ArrayList<>(rawKeys.size());
        Set<String> kids = new HashSet<>();
        Set<String> pubs = new HashSet<>();
        int revocationKeys = 0;
        for (Object rawKey : rawKeys) {
            Map<String, Object> key = objectValue(rawKey);
            requireExactKeys(key, "kid", "pub", "roles");
            String pub = hexValue(key.get("pub"), PUBLIC_KEY_BYTES);
            if (!Ed25519Points.isCanonicalPrimeOrderCandidate(Hex.decode(pub, PUBLIC_KEY_BYTES))) {
                throw invalid();
            }
            String kid = stringValue(key.get("kid"));
            if (!kid.equals(Hex.kid(pub))) {
                throw invalid();
            }
            List<String> roles = rolesValue(key.get("roles"));
            if (roles.contains(ROLE_REVOCATION)) {
                // The backup key that signs revocations must also hold the release role.
                if (!roles.contains(ROLE_RELEASE)) {
                    throw invalid();
                }
                revocationKeys++;
            }
            if (!kids.add(kid) || !pubs.add(pub)) {
                throw invalid();
            }
            keys.add(new UpdateDocuments.TrustedKey(kid, pub, roles));
        }
        if (revocationKeys != 1) {
            throw invalid();
        }
        return new UpdateDocuments.Trust(
                (int) epoch, built, revocationsSeqFloor, manifestSeqFloor, keys);
    }

    /**
     * Parses a detached signature envelope ({@code *.sig}). Signer kids are checked for shape and
     * uniqueness only; whether they are trusted is decided by the verifier.
     *
     * @throws UpdateRejectedException on any structural violation
     */
    public static UpdateDocuments.SignatureEnvelope parseSignatures(byte[] bytes)
            throws UpdateRejectedException {
        Map<String, Object> root = parseRoot(bytes, SIGNATURES_MAX_BYTES);
        requireHeader(root, TYPE_SIGNATURES, "payloadSha256", "signatures");

        String payloadSha256 = hexValue(root.get("payloadSha256"), SHA256_BYTES);
        List<?> rawEntries = listValue(root.get("signatures"), 1, MAX_SIGNATURES);
        List<UpdateDocuments.SignatureEntry> entries = new ArrayList<>(rawEntries.size());
        Set<String> kids = new HashSet<>();
        for (Object rawEntry : rawEntries) {
            Map<String, Object> entry = objectValue(rawEntry);
            requireExactKeys(entry, "kid", "sig");
            String kid = hexValue(entry.get("kid"), KID_BYTES);
            String sig = hexValue(entry.get("sig"), SIGNATURE_BYTES);
            if (!kids.add(kid)) {
                throw invalid();
            }
            entries.add(new UpdateDocuments.SignatureEntry(kid, sig));
        }
        return new UpdateDocuments.SignatureEnvelope(payloadSha256, entries);
    }

    /**
     * Parses a release manifest ({@code release.v1.json}).
     *
     * @throws UpdateRejectedException on any structural violation
     */
    public static UpdateDocuments.ReleaseManifest parseRelease(byte[] bytes)
            throws UpdateRejectedException {
        Map<String, Object> root = parseRoot(bytes, RELEASE_MAX_BYTES);
        requireHeader(root, TYPE_RELEASE, "seq", "issued", "expires", "minRevocationsSeq",
                "releases", "withdrawn");

        long seq = uintValue(root.get("seq"), 0, MAX_SEQ);
        Instant issued = timestampValue(root.get("issued"));
        Instant expires = timestampValue(root.get("expires"));
        long minRevocationsSeq = uintValue(root.get("minRevocationsSeq"), 0, MAX_SEQ);

        List<?> rawReleases = listValue(root.get("releases"), 0, MAX_RELEASES);
        List<UpdateDocuments.ReleaseEntry> releases = new ArrayList<>(rawReleases.size());
        Set<String> platforms = new HashSet<>();
        for (Object rawRelease : rawReleases) {
            UpdateDocuments.ReleaseEntry release = releaseValue(rawRelease);
            if (!platforms.add(release.platform())) {
                throw invalid();
            }
            releases.add(release);
        }

        List<?> rawWithdrawn = listValue(root.get("withdrawn"), 0, MAX_WITHDRAWN);
        List<UpdateDocuments.Withdrawal> withdrawn = new ArrayList<>(rawWithdrawn.size());
        Set<List<Object>> platformVersions = new HashSet<>();
        Set<String> withdrawnHashes = new HashSet<>();
        for (Object rawWithdrawal : rawWithdrawn) {
            Map<String, Object> entry = objectValue(rawWithdrawal);
            requireExactKeys(entry, "platform", "version", "sha512");
            String platform = platformValue(entry.get("platform"));
            UpdateVersion version = versionValue(entry.get("version"));
            String sha512 = hexValue(entry.get("sha512"), SHA512_BYTES);
            if (!platformVersions.add(List.of(platform, version))
                    || !withdrawnHashes.add(sha512)) {
                throw invalid();
            }
            withdrawn.add(new UpdateDocuments.Withdrawal(platform, version, sha512));
        }

        return new UpdateDocuments.ReleaseManifest(
                seq, issued, expires, minRevocationsSeq, releases, withdrawn);
    }

    /**
     * Parses a revocations document ({@code revocations.v1.json}).
     *
     * @throws UpdateRejectedException on any structural violation
     */
    public static UpdateDocuments.Revocations parseRevocations(byte[] bytes)
            throws UpdateRejectedException {
        Map<String, Object> root = parseRoot(bytes, REVOCATIONS_MAX_BYTES);
        requireHeader(root, TYPE_REVOCATIONS, "seq", "issued", "expires", "minManifestSeq",
                "revokedKeys", "blockedArtifacts");

        long seq = uintValue(root.get("seq"), 0, MAX_SEQ);
        Instant issued = timestampValue(root.get("issued"));
        Instant expires = timestampValue(root.get("expires"));
        long minManifestSeq = uintValue(root.get("minManifestSeq"), 0, MAX_SEQ);

        List<?> rawRevoked = listValue(root.get("revokedKeys"), 0, MAX_REVOKED_KEYS);
        List<UpdateDocuments.RevokedKey> revokedKeys = new ArrayList<>(rawRevoked.size());
        Set<String> kids = new HashSet<>();
        Set<String> pubs = new HashSet<>();
        for (Object rawKey : rawRevoked) {
            Map<String, Object> key = objectValue(rawKey);
            requireExactKeys(key, "kid", "pub");
            String pub = hexValue(key.get("pub"), PUBLIC_KEY_BYTES);
            String kid = stringValue(key.get("kid"));
            if (!kid.equals(Hex.kid(pub))) {
                throw invalid();
            }
            if (!kids.add(kid) || !pubs.add(pub)) {
                throw invalid();
            }
            revokedKeys.add(new UpdateDocuments.RevokedKey(kid, pub));
        }

        List<?> rawBlocked = listValue(root.get("blockedArtifacts"), 0, MAX_BLOCKED_ARTIFACTS);
        List<String> blockedArtifacts = new ArrayList<>(rawBlocked.size());
        Set<String> blockedHashes = new HashSet<>();
        for (Object rawArtifact : rawBlocked) {
            Map<String, Object> entry = objectValue(rawArtifact);
            requireExactKeys(entry, "sha512");
            String sha512 = hexValue(entry.get("sha512"), SHA512_BYTES);
            if (!blockedHashes.add(sha512)) {
                throw invalid();
            }
            blockedArtifacts.add(sha512);
        }

        return new UpdateDocuments.Revocations(
                seq, issued, expires, minManifestSeq, revokedKeys, blockedArtifacts);
    }

    private static UpdateDocuments.ReleaseEntry releaseValue(Object raw)
            throws UpdateRejectedException {
        Map<String, Object> release = objectValue(raw);
        requireExactKeys(release, "platform", "version", "targets", "file", "size", "sha512",
                "tag");
        String platform = platformValue(release.get("platform"));
        UpdateVersion version = versionValue(release.get("version"));
        List<String> targets = targetsValue(release.get("targets"));
        String file = stringValue(release.get("file"));
        if (!FILE_NAME.matcher(file).matches()) {
            throw invalid();
        }
        long size = uintValue(release.get("size"), 1, MAX_RELEASE_SIZE);
        String sha512 = hexValue(release.get("sha512"), SHA512_BYTES);
        String tag = stringValue(release.get("tag"));
        if (!TAG.matcher(tag).matches()) {
            throw invalid();
        }
        return new UpdateDocuments.ReleaseEntry(
                platform, version, targets, file, size, sha512, tag);
    }

    private static List<String> targetsValue(Object raw) throws UpdateRejectedException {
        List<?> items = listValue(raw, 1, MAX_TARGETS);
        List<String> targets = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        for (Object item : items) {
            String target = stringValue(item);
            // Exact literal values: no charset rule and no wildcard or range interpretation.
            if (target.isEmpty() || target.length() > MAX_TARGET_CHARS || !seen.add(target)) {
                throw invalid();
            }
            targets.add(target);
        }
        return targets;
    }

    private static List<String> rolesValue(Object raw) throws UpdateRejectedException {
        List<?> items = listValue(raw, 1, 2);
        List<String> roles = new ArrayList<>(items.size());
        for (Object item : items) {
            String role = stringValue(item);
            if (!ROLE_RELEASE.equals(role) && !ROLE_REVOCATION.equals(role)) {
                throw invalid();
            }
            if (roles.contains(role)) {
                throw invalid();
            }
            roles.add(role);
        }
        return roles;
    }

    private static String platformValue(Object raw) throws UpdateRejectedException {
        String platform = stringValue(raw);
        if (!PLATFORMS.contains(platform)) {
            throw invalid();
        }
        return platform;
    }

    private static UpdateVersion versionValue(Object raw) throws UpdateRejectedException {
        return UpdateVersion.parse(stringValue(raw));
    }

    private static Map<String, Object> parseRoot(byte[] bytes, int maxBytes)
            throws UpdateRejectedException {
        return StrictJson.parse(bytes, maxBytes, MAX_STRING_CHARS);
    }

    /** Requires the exact key set, including {@code type} and {@code v}, and checks both values. */
    static void requireHeader(Map<String, Object> root, String type, String... fields)
            throws UpdateRejectedException {
        Set<String> expected = new HashSet<>();
        expected.add("type");
        expected.add("v");
        for (String field : fields) {
            expected.add(field);
        }
        requireExactKeys(root, expected);
        if (!type.equals(root.get("type"))) {
            throw invalid();
        }
        if (uintValue(root.get("v"), 1, 1) != 1) {
            throw invalid();
        }
    }

    static void requireExactKeys(Map<String, Object> object, String... fields)
            throws UpdateRejectedException {
        requireExactKeys(object, Set.of(fields));
    }

    /** Rejects unknown keys, missing keys and any null value at this level. */
    static void requireExactKeys(Map<String, Object> object, Set<String> expected)
            throws UpdateRejectedException {
        if (!object.keySet().equals(expected)) {
            throw invalid();
        }
        for (Object value : object.values()) {
            if (value == null) {
                throw invalid();
            }
        }
    }

    static Map<String, Object> objectValue(Object raw) throws UpdateRejectedException {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) map;
        return object;
    }

    static List<?> listValue(Object raw, int min, int max)
            throws UpdateRejectedException {
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw invalid();
        }
        return list;
    }

    static String stringValue(Object raw) throws UpdateRejectedException {
        if (!(raw instanceof String text)) {
            throw invalid();
        }
        return text;
    }

    /** Strict lowercase hex of exactly {@code byteLength} bytes. */
    static String hexValue(Object raw, int byteLength) throws UpdateRejectedException {
        String text = stringValue(raw);
        Hex.decode(text, byteLength);
        return text;
    }

    /** An integer JSON value in {@code min..max}. Booleans and nulls never qualify. */
    static long uintValue(Object raw, long min, long max)
            throws UpdateRejectedException {
        if (!(raw instanceof Long value) || value < min || value > max) {
            throw invalid();
        }
        return value;
    }

    /**
     * A calendar-valid UTC timestamp in exactly {@code YYYY-MM-DDTHH:MM:SSZ} form. Leap seconds
     * and {@code 24:00} are rejected, not normalised.
     */
    static Instant timestampValue(Object raw) throws UpdateRejectedException {
        String text = stringValue(raw);
        if (text.length() != 20 || !TIMESTAMP_SHAPE.matcher(text).matches()) {
            throw invalid();
        }
        try {
            return LocalDateTime.parse(text, TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    static UpdateRejectedException invalid() {
        return new UpdateRejectedException(INVALID);
    }
}
