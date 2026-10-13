// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Strict codec for {@code state.json} (docs/updater.md 12.8). Encoding is deterministic: keys are
 * sorted at every level. Decoding enforces exact keys, types and the cardinality caps of the schema.
 */
final class StateCodec {

    static final String TYPE = "quicraft.updater-state";
    static final int MAX_BYTES = 131072;

    private static final int MAX_STRING_CHARS = 4096;
    private static final int MAX_SEQ_BY_KID = 32;
    private static final int MAX_WITHDRAWN = 256;
    private static final int MAX_WARNED_BAD = 32;
    private static final int MAX_TORN_HOSTS = 3;
    private static final long MAX_SEQ = 9007199254740991L;
    private static final int SHA256_BYTES = 32;
    private static final int SHA512_BYTES = 64;
    private static final int KID_BYTES = 8;
    private static final String INVALID_STATE = "invalid state";
    private static final String STATE_FULL = "state full";

    private StateCodec() {
    }

    /**
     * Encodes the state as canonical UTF-8 JSON.
     *
     * @throws IllegalStateException with message "state full" if a cardinality cap is exceeded or
     *     the encoded size is above {@link #MAX_BYTES}
     * @throws IllegalArgumentException if a timestamp has sub-second precision
     */
    static byte[] encode(StateRecords.UpdaterState s) {
        Objects.requireNonNull(s, "state");
        if (s.seqByKid().size() > MAX_SEQ_BY_KID
                || s.withdrawn().size() > MAX_WITHDRAWN
                || s.warnedBad().size() > MAX_WARNED_BAD) {
            throw new IllegalStateException(STATE_FULL);
        }
        StateRecords.CurrentRevocations rev = s.revocations();

        Map<String, Object> revocations = new TreeMap<>();
        revocations.put("seq", rev.seq());
        revocations.put("sha256", rev.sha256());
        revocations.put("issued", rev.seed() ? "" : CanonicalJson.timestamp(rev.issued()));
        revocations.put("expires", rev.seed() ? "" : CanonicalJson.timestamp(rev.expires()));
        revocations.put("minManifestSeq", rev.minManifestSeq());

        Map<String, Object> seqByKid = new TreeMap<>();
        for (Map.Entry<String, StateRecords.KidSeq> e : s.seqByKid().entrySet()) {
            StateRecords.KidSeq k = e.getValue();
            Map<String, Object> entry = new TreeMap<>();
            entry.put("seq", k.seq());
            entry.put("sha256", k.sha256());
            entry.put("issued", CanonicalJson.timestamp(k.issued()));
            seqByKid.put(e.getKey(), entry);
        }

        List<Object> withdrawn = new ArrayList<>(s.withdrawn().size());
        for (StateRecords.WithdrawnEntry w : s.withdrawn()) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("sha512", w.sha512());
            entry.put("version", w.version().toString());
            entry.put("kid", w.kid());
            withdrawn.add(entry);
        }

        Map<String, Object> tornSince = new TreeMap<>();
        for (Map.Entry<String, Instant> e : s.tornSince().entrySet()) {
            tornSince.put(e.getKey(), CanonicalJson.timestamp(e.getValue()));
        }

        Map<String, Object> root = new TreeMap<>();
        root.put("type", TYPE);
        root.put("v", 1L);
        root.put("epoch", (long) s.epoch());
        root.put("keySetFingerprint", s.keySetFingerprint());
        root.put("revocationKey", s.revocationKey());
        root.put("revocations", revocations);
        root.put("seqByKid", seqByKid);
        root.put("withdrawn", withdrawn);
        root.put("lastAnnounced", s.lastAnnounced());
        root.put("warnedBad", new ArrayList<Object>(s.warnedBad()));
        root.put("tornSince", tornSince);

        byte[] out = CanonicalJson.render(root).getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_BYTES) {
            throw new IllegalStateException(STATE_FULL);
        }
        return out;
    }

    /**
     * Decodes and validates {@code state.json} bytes.
     *
     * @throws UpdateRejectedException with a fixed message on any schema, size or cap violation
     */
    static StateRecords.UpdaterState decode(byte[] bytes) throws UpdateRejectedException {
        Map<String, Object> root = StrictJson.parse(bytes, MAX_BYTES, MAX_STRING_CHARS);
        DocumentParser.requireHeader(root, TYPE, "epoch", "keySetFingerprint", "revocationKey",
                "revocations", "seqByKid", "withdrawn", "lastAnnounced", "warnedBad", "tornSince");
        try {
            return new StateRecords.UpdaterState(
                    (int) DocumentParser.uintValue(root.get("epoch"), 1, Integer.MAX_VALUE),
                    DocumentParser.hexValue(root.get("keySetFingerprint"), SHA256_BYTES),
                    DocumentParser.hexValue(root.get("revocationKey"), SHA256_BYTES),
                    decodeRevocations(root.get("revocations")),
                    decodeSeqByKid(root.get("seqByKid")),
                    decodeWithdrawn(root.get("withdrawn")),
                    DocumentParser.stringValue(root.get("lastAnnounced")),
                    decodeWarnedBad(root.get("warnedBad")),
                    decodeTornSince(root.get("tornSince")));
        } catch (IllegalArgumentException e) {
            throw new UpdateRejectedException(INVALID_STATE, e);
        }
    }

    private static StateRecords.CurrentRevocations decodeRevocations(Object raw)
            throws UpdateRejectedException {
        Map<String, Object> o = DocumentParser.objectValue(raw);
        DocumentParser.requireExactKeys(o, "seq", "sha256", "issued", "expires", "minManifestSeq");
        String sha256 = DocumentParser.stringValue(o.get("sha256"));
        if (!sha256.isEmpty()) {
            DocumentParser.hexValue(sha256, SHA256_BYTES);
        }
        return new StateRecords.CurrentRevocations(
                DocumentParser.uintValue(o.get("seq"), 0, MAX_SEQ),
                sha256,
                optionalTimestamp(o.get("issued")),
                optionalTimestamp(o.get("expires")),
                DocumentParser.uintValue(o.get("minManifestSeq"), 0, MAX_SEQ));
    }

    private static Map<String, StateRecords.KidSeq> decodeSeqByKid(Object raw)
            throws UpdateRejectedException {
        Map<String, Object> o = DocumentParser.objectValue(raw);
        if (o.size() > MAX_SEQ_BY_KID) {
            throw DocumentParser.invalid();
        }
        Map<String, StateRecords.KidSeq> out = new HashMap<>();
        for (Map.Entry<String, Object> e : o.entrySet()) {
            DocumentParser.hexValue(e.getKey(), KID_BYTES);
            Map<String, Object> entry = DocumentParser.objectValue(e.getValue());
            DocumentParser.requireExactKeys(entry, "seq", "sha256", "issued");
            out.put(e.getKey(), new StateRecords.KidSeq(
                    DocumentParser.uintValue(entry.get("seq"), 0, MAX_SEQ),
                    DocumentParser.hexValue(entry.get("sha256"), SHA256_BYTES),
                    DocumentParser.timestampValue(entry.get("issued"))));
        }
        return out;
    }

    private static List<StateRecords.WithdrawnEntry> decodeWithdrawn(Object raw)
            throws UpdateRejectedException {
        List<?> list = DocumentParser.listValue(raw, 0, MAX_WITHDRAWN);
        List<StateRecords.WithdrawnEntry> out = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> entry = DocumentParser.objectValue(item);
            DocumentParser.requireExactKeys(entry, "sha512", "version", "kid");
            out.add(new StateRecords.WithdrawnEntry(
                    DocumentParser.hexValue(entry.get("sha512"), SHA512_BYTES),
                    UpdateVersion.parse(DocumentParser.stringValue(entry.get("version"))),
                    DocumentParser.hexValue(entry.get("kid"), KID_BYTES)));
        }
        return out;
    }

    private static List<String> decodeWarnedBad(Object raw) throws UpdateRejectedException {
        List<?> list = DocumentParser.listValue(raw, 0, MAX_WARNED_BAD);
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(DocumentParser.hexValue(item, SHA256_BYTES));
        }
        return out;
    }

    private static Map<String, Instant> decodeTornSince(Object raw) throws UpdateRejectedException {
        Map<String, Object> o = DocumentParser.objectValue(raw);
        if (o.size() > MAX_TORN_HOSTS) {
            throw DocumentParser.invalid();
        }
        Map<String, Instant> out = new HashMap<>();
        for (Map.Entry<String, Object> e : o.entrySet()) {
            out.put(e.getKey(), DocumentParser.timestampValue(e.getValue()));
        }
        return out;
    }

    /** The empty string means "absent" (seed case); anything else must be a timestamp. */
    private static Instant optionalTimestamp(Object raw) throws UpdateRejectedException {
        if ("".equals(raw)) {
            return null;
        }
        return DocumentParser.timestampValue(raw);
    }
}
