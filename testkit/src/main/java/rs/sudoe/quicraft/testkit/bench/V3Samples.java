// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Read-only validator for one {@code /3} sample document, as the future real-Fabric emitter will
 * write it. It proves structure, typing and internal consistency only: every value in the file is
 * self-reported by whoever produced it, and nothing here observes a live measurement.
 *
 * <p>Envelope contract for that emitter, documented here so no other name is improvised:
 * {@code clientType} is the string {@code fabric}, {@code netemProfile} is one of
 * {@code clean loss delay delayloss reorder jitter}, {@code profile} is exactly
 * {@code <netemProfile>-quic-3}, and {@code runs} is a nonempty array. Other envelope fields are
 * allowed. Every row must carry every {@link RunResult} record component except the optional
 * {@code error}, {@code net} and {@code quic}. Every row, warmups included, must pass
 * {@link NegotiatedTransportCheck}, at least one row must be measured, and all rows must share one
 * build commit, routing table identity, digest, schema and stream count.
 *
 * <p>Limits: at most 8 MiB of strict UTF-8, at most 64 levels of nesting, strict JSON syntax with
 * no duplicate keys at any depth and no trailing tokens. Missing {@code --in} is a usage error
 * (2); a valid document returns 0; an unreadable, oversized, malformed or inconsistent document
 * returns 1. The command only reads: it never writes a file, opens a socket or starts a game.
 */
public final class V3Samples {
    /** Largest document this validator reads. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** Deepest value allowed, counting the root value as level 1. */
    private static final int MAX_DEPTH = 64;

    /** Longest accepted JSON number literal, checked before {@link BigDecimal} parsing. */
    private static final int MAX_NUMBER_LITERAL_LENGTH = 128;

    /** Most characters an invalid-document detail may emit, excluding prefix and newline. */
    private static final int MAX_DETAIL_LENGTH = 512;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** Absolute difference allowed between the fourth phase and the join time, in milliseconds. */
    private static final double PHASE_JOIN_TOLERANCE = 1e-6;

    private static final String SUCCESS = "/3 samples structurally valid and internally consistent;"
            + " provenance self-reported, live measurement not verified.";

    private static final Set<String> NETEM_PROFILES =
            Set.of("clean", "loss", "delay", "delayloss", "reorder", "jitter", "slow");

    /** Row fields the validator never requires and only type-checks when present. */
    private static final Set<String> OPTIONAL_COMPONENTS = Set.of("error", "net", "quic");

    /**
     * Every {@link RunResult} record component except the optional three, taken from the record
     * itself so a future component cannot be silently skipped.
     */
    private static final List<String> REQUIRED_COMPONENTS = requiredComponents();

    private static final Gson GSON = new Gson();

    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private static final CharsetDecoder UTF8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    private V3Samples() {}

    /**
     * Validates the file named by {@code --in}. Returns 0 for a valid document, 1 for an
     * unreadable, oversized or invalid one, and 2 when {@code --in} is missing or unusable.
     */
    public static int run(Args args) {
        String spec = args == null ? null : args.string("in", null);
        if (spec == null || spec.isBlank() || "true".equals(spec)) {
            System.err.println("v3-samples: missing --in <file>; usage: v3-samples --in FILE");
            return 2;
        }
        Path path;
        try {
            path = Path.of(spec);
        } catch (InvalidPathException e) {
            System.err.println("v3-samples: invalid --in path: " + spec);
            return 2;
        }

        JsonElement root;
        try {
            root = parse(path);
        } catch (InvalidDocument e) {
            return invalid(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return invalid("not valid JSON: " + e.getMessage());
        }

        try {
            Summary summary = validate(root);
            System.out.println(SUCCESS);
            System.out.println("rows: " + summary.rows() + " (measured " + summary.measured()
                    + ", warmup " + summary.warmup() + ")");
            return 0;
        } catch (InvalidDocument e) {
            return invalid(e.getMessage());
        }
    }

    private static int invalid(String detail) {
        System.err.println("v3-samples: " + sanitize(detail));
        return 1;
    }

    /**
     * Reads and strictly parses one document: at most {@link #MAX_BYTES} bytes, strict UTF-8,
     * strict JSON syntax with no duplicate keys, at most {@link #MAX_DEPTH} levels and no trailing
     * tokens. Shared with {@link ArmSamples} so every arm applies the same parser and limits;
     * reading never writes.
     */
    static JsonElement parse(Path path) throws IOException, InvalidDocument {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw new InvalidDocument("cannot read " + path + ": " + e.getMessage());
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidDocument("input is larger than " + MAX_BYTES + " bytes");
        }

        String text;
        try {
            text = UTF8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidDocument("input is not valid UTF-8");
        }
        return readDocument(text);
    }

    /**
     * Makes a rejection detail safe for stderr: LF, CR, TAB and every other ISO control character
     * (including DEL and C1) become printable escapes, and the result is capped at
     * {@link #MAX_DETAIL_LENGTH} emitted characters. Truncation happens between whole tokens, so
     * an escape is never cut, and reserves room for the marker inside the cap.
     */
    static String sanitize(String detail) {
        if (detail == null) {
            return "invalid document";
        }
        StringBuilder out = new StringBuilder(Math.min(detail.length(), MAX_DETAIL_LENGTH));
        int[] tokenStarts = new int[MAX_DETAIL_LENGTH + 1];
        int tokens = 0;
        for (int i = 0; i < detail.length(); i++) {
            int start = out.length();
            switch (detail.charAt(i)) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    char c = detail.charAt(i);
                    if (Character.isISOControl(c)) {
                        out.append("\\u")
                                .append(HEX_DIGITS[(c >> 12) & 0xF])
                                .append(HEX_DIGITS[(c >> 8) & 0xF])
                                .append(HEX_DIGITS[(c >> 4) & 0xF])
                                .append(HEX_DIGITS[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
            if (out.length() > MAX_DETAIL_LENGTH) {
                out.setLength(start);
                while (out.length() > MAX_DETAIL_LENGTH - 3) {
                    out.setLength(tokenStarts[--tokens]);
                }
                return out.append("...").toString();
            }
            tokenStarts[tokens++] = start;
        }
        return out.toString();
    }

    private static List<String> requiredComponents() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : RunResult.class.getRecordComponents()) {
            if (!OPTIONAL_COMPONENTS.contains(component.getName())) {
                names.add(component.getName());
            }
        }
        return List.copyOf(new LinkedHashSet<>(names));
    }

    /** Counts of a valid document, for the success line only; never a performance value. */
    record Summary(int rows, int measured, int warmup) {}

    private static JsonElement readDocument(String text) throws IOException, InvalidDocument {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        try {
            JsonElement root = readValue(reader, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidDocument("trailing content after the root value");
            }
            return root;
        } finally {
            reader.close();
        }
    }

    /**
     * Builds the tree by hand so strict typing, duplicate-key rejection and the depth bound apply
     * to unknown extra fields too. Numbers keep their exact decimal text.
     */
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException, InvalidDocument {
        if (depth > MAX_DEPTH) {
            throw new InvalidDocument("nesting exceeds " + MAX_DEPTH + " levels");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT: {
                reader.beginObject();
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) {
                        throw new InvalidDocument("duplicate key " + quote(name));
                    }
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    array.add(readValue(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return readNumber(reader);
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new InvalidDocument("unexpected JSON token " + token);
        }
    }

    private static JsonElement readNumber(JsonReader reader) throws IOException, InvalidDocument {
        String literal = reader.nextString();
        if (literal.length() > MAX_NUMBER_LITERAL_LENGTH) {
            throw new InvalidDocument(
                    "number literal exceeds " + MAX_NUMBER_LITERAL_LENGTH + " characters");
        }
        try {
            return new JsonPrimitive(new BigDecimal(literal));
        } catch (NumberFormatException e) {
            throw new InvalidDocument("number " + quote(literal) + " is not a decimal number");
        }
    }

    /**
     * Validates one complete {@code /3} document: envelope, every row's shape and transport
     * evidence, and the cross-row identity rules.
     */
    static Summary validate(JsonElement rootElement) throws InvalidDocument {
        JsonArray runs = validateEnvelope(rootElement, "-quic-3");
        List<RunResult> results = validateRows(runs, V3Samples::validateRow);
        requireSharedIdentity(results);
        int warmups = 0;
        for (RunResult result : results) {
            if (result.warmup()) {
                warmups++;
            }
        }
        return new Summary(results.size(), results.size() - warmups, warmups);
    }

    /**
     * Validates the envelope every merged samples document shares, returning its nonempty row
     * array. {@code profileSuffix} is the suffix the profile must end with: {@code -quic-3} here,
     * {@code -tcp} or {@code -quic-2} for {@link ArmSamples}.
     */
    static JsonArray validateEnvelope(JsonElement rootElement, String profileSuffix)
            throws InvalidDocument {
        JsonObject root = asObject(rootElement, "root");
        String clientType = stringField(root, "clientType", "root");
        if (!"fabric".equals(clientType)) {
            throw new InvalidDocument("root.clientType must be \"fabric\", not " + quote(clientType));
        }
        String netemProfile = stringField(root, "netemProfile", "root");
        if (!NETEM_PROFILES.contains(netemProfile)) {
            throw new InvalidDocument("root.netemProfile is not a known netem profile: "
                    + quote(netemProfile));
        }
        String profile = stringField(root, "profile", "root");
        String expectedProfile = netemProfile + profileSuffix;
        if (!expectedProfile.equals(profile)) {
            throw new InvalidDocument("root.profile must be " + quote(expectedProfile) + ", not "
                    + quote(profile));
        }
        JsonArray runs = asArray(field(root, "runs", "root"), "root.runs");
        if (runs.isEmpty()) {
            throw new InvalidDocument("root.runs must hold at least one row");
        }
        return runs;
    }

    /** A row validator returning the parsed result after its own transport-specific gates. */
    @FunctionalInterface
    interface RowValidator {
        RunResult validate(JsonObject row, String what) throws InvalidDocument;
    }

    /**
     * Validates every row with {@code validator}, rejects duplicate run indexes and a document
     * holding only warmup rows.
     */
    static List<RunResult> validateRows(JsonArray runs, RowValidator validator)
            throws InvalidDocument {
        List<RunResult> results = new ArrayList<>();
        Set<Integer> runIndexes = new HashSet<>();
        int warmups = 0;
        for (int i = 0; i < runs.size(); i++) {
            String what = "runs[" + i + "]";
            RunResult result = validator.validate(asObject(runs.get(i), what), what);
            if (!runIndexes.add(result.run())) {
                throw new InvalidDocument(what + " repeats run index " + result.run());
            }
            if (result.warmup()) {
                warmups++;
            }
            results.add(result);
        }
        if (warmups == results.size()) {
            throw new InvalidDocument("every row is a warmup; at least one measured row is required");
        }
        return results;
    }

    /**
     * Requires every row to share the first row's build commit, routing table identity, digest,
     * schema and stream count.
     */
    static void requireSharedIdentity(List<RunResult> results) throws InvalidDocument {
        RunResult first = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            RunResult other = results.get(i);
            if (!Objects.equals(first.buildCommit(), other.buildCommit())
                    || !Objects.equals(first.routingTableId(), other.routingTableId())
                    || !Objects.equals(first.routingTableDigest(), other.routingTableDigest())
                    || first.routingTableSchema() != other.routingTableSchema()
                    || first.streamCount() != other.streamCount()) {
                throw new InvalidDocument("runs[" + i + "] does not share the first row's build"
                        + " commit, routing table identity, digest, schema and stream count");
            }
        }
    }

    /** Applies the {@code /3} transport gates to an already shape-checked row. */
    private static RunResult validateRow(JsonObject row, String what) throws InvalidDocument {
        validateRowShape(row, what);
        String transport = stringField(row, "transportVersion", what);
        if (!TransportVersion.QUIC_3.name().equals(transport)) {
            throw new InvalidDocument(what + ".transportVersion must be "
                    + TransportVersion.QUIC_3.name() + ", not " + quote(transport));
        }
        RunResult result = convertRow(row, what);
        NegotiatedTransportCheck.Result check =
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, result);
        if (!check.verified()) {
            throw new InvalidDocument(what + " is not verified /3 evidence: " + check.detail());
        }
        return result;
    }

    /**
     * Every transport-independent row rule of the {@code /3} validator: required components,
     * status flags, four finite nondecreasing phases, metric bounds, optional fields and their
     * types. Shared with {@link ArmSamples}; no transport identity or provenance value is judged
     * here.
     */
    static void validateRowShape(JsonObject row, String what) throws InvalidDocument {
        for (String name : REQUIRED_COMPONENTS) {
            field(row, name, what);
        }

        int run = intField(row, "run", what);
        if (run < 0) {
            throw new InvalidDocument(what + ".run must not be negative");
        }
        booleanField(row, "warmup", what);
        if (!booleanField(row, "ok", what)) {
            throw new InvalidDocument(what + ".ok must be true");
        }
        if (booleanField(row, "disconnected", what)) {
            throw new InvalidDocument(what + ".disconnected must be false");
        }
        if (booleanField(row, "zeroRtt", what)) {
            throw new InvalidDocument(what + ".zeroRtt must be false");
        }
        if (!booleanField(row, "negotiatedTransportVerified", what)) {
            throw new InvalidDocument(what + ".negotiatedTransportVerified must be true");
        }

        double[] phases = phases(row, what);
        double join = finiteNonNegative(numberField(row, "joinMs", what), what + ".joinMs");
        finiteNonNegative(numberField(row, "chunkLoadMs", what), what + ".chunkLoadMs");
        if (Math.abs(phases[3] - join) > PHASE_JOIN_TOLERANCE) {
            throw new InvalidDocument(what + ".phasesMs[3] must equal joinMs within 1e-6 ms");
        }

        int chunks = intField(row, "chunks", what);
        if (chunks <= 0) {
            throw new InvalidDocument(what + ".chunks must be positive");
        }
        BigDecimal rttMedian = numberField(row, "rttMedianMs", what);
        finiteNonNegative(rttMedian, what + ".rttMedianMs");
        BigDecimal rttP95 = numberField(row, "rttP95Ms", what);
        finiteNonNegative(rttP95, what + ".rttP95Ms");
        if (rttP95.compareTo(rttMedian) < 0) {
            throw new InvalidDocument(what + ".rttP95Ms must not be below rttMedianMs");
        }
        int rttSamples = intField(row, "rttSamples", what);
        if (rttSamples <= 0) {
            throw new InvalidDocument(what + ".rttSamples must be positive");
        }
        int rttLost = intField(row, "rttLost", what);
        if (rttLost < 0) {
            throw new InvalidDocument(what + ".rttLost must not be negative");
        }

        intField(row, "schemaVersion", what);
        intField(row, "streamCount", what);
        intField(row, "routingTableSchema", what);
        stringField(row, "buildCommit", what);
        stringField(row, "routingTableId", what);
        stringField(row, "routingTableDigest", what);
        stringField(row, "negotiatedAlpn", what);
        stringField(row, "transportVersion", what);
        optionalError(row, what);
        optionalLongMap(row, "net", what);
        optionalLongMap(row, "quic", what);
    }

    /** Converts an already shape-checked row with the shared Gson. */
    static RunResult convertRow(JsonObject row, String what) throws InvalidDocument {
        RunResult result;
        try {
            result = GSON.fromJson(row, RunResult.class);
        } catch (RuntimeException e) {
            throw new InvalidDocument(what + " does not convert to a RunResult: " + e.getMessage());
        }
        if (result == null) {
            throw new InvalidDocument(what + " converted to null");
        }
        return result;
    }

    private static double[] phases(JsonObject row, String what) throws InvalidDocument {
        JsonArray array = asArray(field(row, "phasesMs", what), what + ".phasesMs");
        if (array.size() != 4) {
            throw new InvalidDocument(what + ".phasesMs must hold exactly four phases");
        }
        BigDecimal[] exact = new BigDecimal[4];
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            String label = what + ".phasesMs[" + i + "]";
            exact[i] = numberOf(array.get(i), label);
            values[i] = finiteNonNegative(exact[i], label);
            if (i > 0 && exact[i].compareTo(exact[i - 1]) < 0) {
                throw new InvalidDocument(what + ".phasesMs must not decrease");
            }
        }
        return values;
    }

    private static void optionalError(JsonObject row, String what) throws InvalidDocument {
        JsonElement value = row.get("error");
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().isEmpty()) {
            throw new InvalidDocument(what + ".error must be null or an empty string");
        }
    }

    private static void optionalLongMap(JsonObject row, String name, String what) throws InvalidDocument {
        JsonElement value = row.get(name);
        if (value == null || value.isJsonNull()) {
            return;
        }
        JsonObject map = asObject(value, what + "." + name);
        for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
            String label = what + "." + name + "." + entry.getKey();
            exactLong(numberOf(entry.getValue(), label), label);
        }
    }

    static JsonObject asObject(JsonElement element, String what) throws InvalidDocument {
        if (element == null || !element.isJsonObject()) {
            throw new InvalidDocument(what + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray asArray(JsonElement element, String what) throws InvalidDocument {
        if (element == null || !element.isJsonArray()) {
            throw new InvalidDocument(what + " must be a JSON array");
        }
        return element.getAsJsonArray();
    }

    private static JsonElement field(JsonObject object, String name, String what) throws InvalidDocument {
        JsonElement value = object.get(name);
        if (value == null) {
            throw new InvalidDocument(what + " is missing " + name);
        }
        return value;
    }

    static String stringField(JsonObject object, String name, String what) throws InvalidDocument {
        JsonElement value = field(object, name, what);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new InvalidDocument(what + "." + name + " must be a JSON string");
        }
        return value.getAsString();
    }

    private static boolean booleanField(JsonObject object, String name, String what) throws InvalidDocument {
        JsonElement value = field(object, name, what);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new InvalidDocument(what + "." + name + " must be a JSON boolean");
        }
        return value.getAsBoolean();
    }

    private static BigDecimal numberField(JsonObject object, String name, String what) throws InvalidDocument {
        return numberOf(field(object, name, what), what + "." + name);
    }

    private static BigDecimal numberOf(JsonElement element, String what) throws InvalidDocument {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new InvalidDocument(what + " must be a JSON number");
        }
        return element.getAsJsonPrimitive().getAsBigDecimal();
    }

    private static int intField(JsonObject object, String name, String what) throws InvalidDocument {
        return exactInt(numberField(object, name, what), what + "." + name);
    }

    /**
     * Bounds are compared as decimals before any exact conversion, so an arbitrarily large or
     * small exponent is rejected cheaply instead of being expanded.
     */
    private static int exactInt(BigDecimal value, String what) throws InvalidDocument {
        if (value.signum() == 0) {
            return 0;
        }
        if (value.compareTo(INT_MIN) < 0 || value.compareTo(INT_MAX) > 0) {
            throw new InvalidDocument(what + " is outside the int range");
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new InvalidDocument(what + " is not an integer");
        }
        return value.intValueExact();
    }

    private static long exactLong(BigDecimal value, String what) throws InvalidDocument {
        if (value.signum() == 0) {
            return 0;
        }
        if (value.compareTo(LONG_MIN) < 0 || value.compareTo(LONG_MAX) > 0) {
            throw new InvalidDocument(what + " is outside the long range");
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new InvalidDocument(what + " is not an integer");
        }
        return value.longValueExact();
    }

    private static double finiteNonNegative(BigDecimal value, String what) throws InvalidDocument {
        if (value.signum() < 0) {
            throw new InvalidDocument(what + " must not be negative");
        }
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new InvalidDocument(what + " is not a finite number");
        }
        return converted;
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        String shown = value.length() <= 60 ? value : value.substring(0, 60) + "...";
        return "\"" + shown + "\"";
    }

    /** A document-level rejection with a message safe to print without a stack trace. */
    static final class InvalidDocument extends Exception {
        InvalidDocument(String message) {
            super(message);
        }
    }
}
