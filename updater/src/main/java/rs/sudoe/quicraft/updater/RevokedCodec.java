// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Strict codec for {@code revoked.json} (docs/updater.md 12.8). Encoding is deterministic: object
 * keys are sorted, and array order is kept exactly as the record holds it. Decoding enforces exact
 * keys, types, the cardinality caps and that each kid matches its public key.
 */
final class RevokedCodec {

    static final String TYPE = "quicraft.revoked";
    static final int MAX_BYTES = 262144;

    private static final int MAX_STRING_CHARS = 256;
    private static final int MAX_REVOKED_KEYS = 64;
    private static final int MAX_BLOCKED_ARTIFACTS = 1024;
    private static final int PUBLIC_KEY_BYTES = 32;
    private static final int SHA512_BYTES = 64;
    private static final int KID_BYTES = 8;
    private static final String INVALID_REVOKED = "invalid revoked state";
    private static final String STATE_FULL = "state full";

    private RevokedCodec() {
    }

    /**
     * Encodes the revocation state as canonical UTF-8 JSON.
     *
     * @throws IllegalStateException with message "state full" if a cardinality cap is exceeded or
     *     the encoded size is above {@link #MAX_BYTES}
     */
    static byte[] encode(StateRecords.RevokedState r) {
        Objects.requireNonNull(r, "revoked");
        if (r.revokedKeys().size() > MAX_REVOKED_KEYS
                || r.blockedArtifacts().size() > MAX_BLOCKED_ARTIFACTS) {
            throw new IllegalStateException(STATE_FULL);
        }
        List<Object> keys = new ArrayList<>(r.revokedKeys().size());
        for (UpdateDocuments.RevokedKey k : r.revokedKeys()) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("kid", k.kid());
            entry.put("pub", k.pub());
            keys.add(entry);
        }
        Map<String, Object> root = new TreeMap<>();
        root.put("type", TYPE);
        root.put("v", 1L);
        root.put("revokedKeys", keys);
        root.put("blockedArtifacts", new ArrayList<Object>(r.blockedArtifacts()));

        byte[] out = CanonicalJson.render(root).getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_BYTES) {
            throw new IllegalStateException(STATE_FULL);
        }
        return out;
    }

    /**
     * Decodes and validates {@code revoked.json} bytes.
     *
     * @throws UpdateRejectedException with a fixed message on any schema, size or cap violation
     */
    static StateRecords.RevokedState decode(byte[] bytes) throws UpdateRejectedException {
        Map<String, Object> root = StrictJson.parse(bytes, MAX_BYTES, MAX_STRING_CHARS);
        DocumentParser.requireHeader(root, TYPE, "revokedKeys", "blockedArtifacts");
        try {
            return new StateRecords.RevokedState(
                    decodeKeys(root.get("revokedKeys")),
                    decodeBlocked(root.get("blockedArtifacts")));
        } catch (IllegalArgumentException e) {
            throw new UpdateRejectedException(INVALID_REVOKED, e);
        }
    }

    private static List<UpdateDocuments.RevokedKey> decodeKeys(Object raw)
            throws UpdateRejectedException {
        List<?> list = DocumentParser.listValue(raw, 0, MAX_REVOKED_KEYS);
        List<UpdateDocuments.RevokedKey> out = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> entry = DocumentParser.objectValue(item);
            DocumentParser.requireExactKeys(entry, "kid", "pub");
            String pub = DocumentParser.hexValue(entry.get("pub"), PUBLIC_KEY_BYTES);
            String kid = DocumentParser.hexValue(entry.get("kid"), KID_BYTES);
            if (!kid.equals(Hex.kid(pub))) {
                throw DocumentParser.invalid();
            }
            out.add(new UpdateDocuments.RevokedKey(kid, pub));
        }
        return out;
    }

    private static List<String> decodeBlocked(Object raw) throws UpdateRejectedException {
        List<?> list = DocumentParser.listValue(raw, 0, MAX_BLOCKED_ARTIFACTS);
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(DocumentParser.hexValue(item, SHA512_BYTES));
        }
        return out;
    }
}
