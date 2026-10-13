// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Strict codec for {@code journal.json} (docs/updater.md 12.8). Encoding is deterministic (keys
 * sorted at every level, via {@link CanonicalJson}). Decoding enforces the exact key set, types,
 * ranges and cross-field rules; every decode failure is one fixed {@link UpdateRejectedException}.
 */
final class JournalCodec {

    static final String TYPE = "quicraft.journal";
    static final int MAX_BYTES = 8192;

    private static final int MAX_STRING_CHARS = 4096;
    private static final long MAX_SEQ = 9007199254740991L;
    private static final String INVALID = "invalid journal";

    private JournalCodec() {
    }

    /**
     * Encodes the record as canonical UTF-8 JSON.
     *
     * @throws IllegalArgumentException if the encoded form exceeds {@link #MAX_BYTES}
     */
    static byte[] encode(JournalRecord r) {
        Objects.requireNonNull(r, "record");
        Map<String, Object> live = new TreeMap<>();
        live.put("path", r.live().path());
        live.put("size", r.live().size());
        live.put("sha512", r.live().sha512());
        live.put("version", r.live().version().toString());

        Map<String, Object> staged = new TreeMap<>();
        staged.put("size", r.staged().size());
        staged.put("sha512", r.staged().sha512());
        staged.put("version", r.staged().version().toString());

        Map<String, Object> root = new TreeMap<>();
        root.put("type", TYPE);
        root.put("v", 1L);
        root.put("txid", r.txid());
        root.put("phase", r.phase().wire());
        root.put("platform", r.platform());
        root.put("manifestSeq", r.manifestSeq());
        root.put("live", live);
        root.put("staged", staged);
        root.put("backupSha512", r.backupSha512());
        root.put("created", CanonicalJson.timestamp(r.created()));
        root.put("bootAttempts", (long) r.bootAttempts());

        byte[] out = CanonicalJson.render(root).getBytes(StandardCharsets.UTF_8);
        if (out.length > MAX_BYTES) {
            throw new IllegalArgumentException("journal too large");
        }
        return out;
    }

    /**
     * Decodes and validates {@code journal.json} bytes.
     *
     * @throws UpdateRejectedException with a fixed message on any schema, size or range violation
     */
    static JournalRecord decode(byte[] bytes) throws UpdateRejectedException {
        Map<String, Object> root = StrictJson.parse(bytes, MAX_BYTES, MAX_STRING_CHARS);
        DocumentParser.requireHeader(root, TYPE, "txid", "phase", "platform", "manifestSeq",
                "live", "staged", "backupSha512", "created", "bootAttempts");
        try {
            return new JournalRecord(
                    DocumentParser.hexValue(root.get("txid"), 16),
                    JournalPhase.fromWire(DocumentParser.stringValue(root.get("phase"))),
                    DocumentParser.stringValue(root.get("platform")),
                    DocumentParser.uintValue(root.get("manifestSeq"), 0, MAX_SEQ),
                    decodeLive(root.get("live")),
                    decodeStaged(root.get("staged")),
                    DocumentParser.hexValue(root.get("backupSha512"), 64),
                    DocumentParser.timestampValue(root.get("created")),
                    (int) DocumentParser.uintValue(root.get("bootAttempts"), 0,
                            JournalRecord.MAX_BOOT_ATTEMPTS));
        } catch (IllegalArgumentException e) {
            throw new UpdateRejectedException(INVALID, e);
        }
    }

    private static JournalLive decodeLive(Object raw) throws UpdateRejectedException {
        Map<String, Object> o = DocumentParser.objectValue(raw);
        DocumentParser.requireExactKeys(o, "path", "size", "sha512", "version");
        try {
            return new JournalLive(
                    DocumentParser.stringValue(o.get("path")),
                    DocumentParser.uintValue(o.get("size"), 0, Long.MAX_VALUE),
                    DocumentParser.hexValue(o.get("sha512"), 64),
                    UpdateVersion.parse(DocumentParser.stringValue(o.get("version"))));
        } catch (IllegalArgumentException e) {
            throw new UpdateRejectedException(INVALID, e);
        }
    }

    private static JournalStaged decodeStaged(Object raw) throws UpdateRejectedException {
        Map<String, Object> o = DocumentParser.objectValue(raw);
        DocumentParser.requireExactKeys(o, "size", "sha512", "version");
        try {
            return new JournalStaged(
                    DocumentParser.uintValue(o.get("size"), 0, Long.MAX_VALUE),
                    DocumentParser.hexValue(o.get("sha512"), 64),
                    UpdateVersion.parse(DocumentParser.stringValue(o.get("version"))));
        } catch (IllegalArgumentException e) {
            throw new UpdateRejectedException(INVALID, e);
        }
    }
}
