// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The validator of docs/protocol.md 13.6 and 13.7: every rejection rule has a failing input here,
 * plus the golden digest of the shipped example resource.
 *
 * <p>The example resource is the spec's synthetic schema example (13.7), reflowed onto one line and
 * without a trailing newline, so digest R covers exactly those bytes. It is a test fixture, not a
 * shipped resource: no released Minecraft version has that mapping. This fixture is also the base
 * of the rejection cases below.
 */
class RoutingTableLoaderTest {

    private static final String CONTROL_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":10,"
                    + "\"lane\":\"control\",\"dependency\":\"fence\"}";
    private static final String REALTIME_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11,"
                    + "\"lane\":\"realtime\",\"dependency\":\"lane\"}";
    private static final String WORLD_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":12,"
                    + "\"lane\":\"world\",\"dependency\":\"lane\"}";
    /** The shipped example resource, byte for byte. */
    private static final String EXAMPLE = "{\"schema\":1,\"protocol\":12345,\"default\":\"control\",\"routes\":["
            + CONTROL_ENTRY + "," + REALTIME_ENTRY + "," + WORLD_ENTRY + "]}";
    /** R for {@link #EXAMPLE}: SHA-256 over those exact bytes (13.7). */
    private static final String EXAMPLE_DIGEST =
            "d70cd561c56f396461639b43822493b1deb75953bc4f9c335f86ef37373cff7a";

    private static RoutingTableException reject(String json) {
        return assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(json));
    }

    private static String entry(String state, Direction direction, int packetId, Lane lane) {
        return "{\"state\":\"" + state + "\",\"direction\":\"" + direction.wireName()
                + "\",\"packet_id\":" + packetId + ",\"lane\":\"" + lane.wireName()
                + "\",\"dependency\":\"" + (lane.isControl() ? "fence" : "lane") + "\"}";
    }

    private static byte[] readResource(String path) throws IOException {
        InputStream in = RoutingTableLoaderTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, "missing test resource " + path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------- the resource and digest R (13.7)

    @Test
    void exampleResourceHasExactlyTheExpectedBytes() throws IOException {
        byte[] bytes = readResource(RoutingTableLoader.resourcePath(12345));
        assertEquals(EXAMPLE, new String(bytes, StandardCharsets.UTF_8),
                "the shipped resource must stay byte-identical to this fixture");
        assertEquals(345, bytes.length, "digest R covers exactly these 345 bytes");
    }

    @Test
    void goldenDigestForTheExampleResource() throws IOException {
        RoutingTable table = RoutingTableLoader.loadResource(12345);
        assertEquals(EXAMPLE_DIGEST, table.digestHex(),
                "R changed: 13.7 defines R as SHA-256 over the exact resource bytes");
        assertEquals(EXAMPLE_DIGEST, hex(table.digest()));
        assertEquals(12345, table.protocolNumber());
    }

    @Test
    void differentBytesGiveDifferentDigests() {
        RoutingTable original = RoutingTableLoader.parse(EXAMPLE);
        RoutingTable trailingWhitespace = RoutingTableLoader.parse(EXAMPLE + " ");
        RoutingTable changedId = RoutingTableLoader.parse(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":13"));

        assertEquals(EXAMPLE_DIGEST, original.digestHex());
        assertTrue(!EXAMPLE_DIGEST.equals(trailingWhitespace.digestHex()),
                "13.7: whitespace is part of R, there is no runtime normalization");
        assertTrue(!original.digestHex().equals(changedId.digestHex()),
                "two tables with different bytes must give different digests");
        assertEquals(Lane.WORLD, changedId.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 13));
    }

    @Test
    void missingResourceIsAnIoFailure() {
        assertThrows(IOException.class, () -> RoutingTableLoader.loadResource(
                "rs/sudoe/quicraft/core/routing/protocol-1.json", Collections.<RouteKey>emptySet()));
    }

    @Test
    void rejectsAResourceWhoseProtocolDoesNotMatchTheRequestedNumber() throws IOException {
        // 13.7: one resource per protocol number. The test fixture named protocol-763.json declares
        // protocol 764, so loading it as 763 must fail rather than return a table for 764.
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.loadResource(763));
        assertTrue(e.getMessage().contains("13.7"), e.getMessage());
        assertTrue(e.getMessage().contains("declares protocol 764"), e.getMessage());
        assertTrue(e.getMessage().contains("requested 763"), e.getMessage());

        // The same fixture passes validation when no number is requested, so only the name/number
        // mismatch rejects it.
        assertEquals(764, RoutingTableLoader.loadResource(
                RoutingTableLoader.resourcePath(763), Collections.<RouteKey>emptySet())
                .protocolNumber());

        // The shipped resource declares the number it is named for, so it still loads.
        assertEquals(12345, RoutingTableLoader.loadResource(12345).protocolNumber());
    }

    // ------------------------------------------------------------------------- schema and shape

    @Test
    void rejectsUnsupportedSchema() {
        assertTrue(reject(EXAMPLE.replace("\"schema\":1", "\"schema\":2")).getMessage().contains("schema"));
    }

    @Test
    void rejectsMissingSchema() {
        reject(EXAMPLE.replace("\"schema\":1,", ""));
    }

    @Test
    void rejectsRootThatIsNotAnObject() {
        assertTrue(reject("[]").getMessage().contains("JSON object"));
        reject("7");
        reject("\"table\"");
    }

    @Test
    void rejectsUnknownRootField() {
        assertTrue(reject(EXAMPLE.replace("\"default\":", "\"extra\":1,\"default\":"))
                .getMessage().contains("unknown field"));
    }

    @Test
    void rejectsUnknownEntryField() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY, CONTROL_ENTRY.replace("}", ",\"note\":true}")))
                .getMessage().contains("unknown field"));
    }

    @Test
    void rejectsRoutesThatAreNotAnArray() {
        String notAnArray = "{\"schema\":1,\"protocol\":12345,\"default\":\"control\",\"routes\":{}}";
        assertTrue(reject(notAnArray).getMessage().contains("routes must be an array"));
        reject("{\"schema\":1,\"protocol\":12345,\"default\":\"control\"}");
    }

    @Test
    void rejectsMalformedJson() {
        assertTrue(reject("{\"schema\":1,").getMessage().contains("malformed JSON"));
    }

    @Test
    void rejectsInputThatIsNotValidUtf8() {
        byte[] invalid = {'{', (byte) 0xff, (byte) 0xfe, '}'};
        assertTrue(assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(invalid)).getMessage().contains("UTF-8"));
    }

    // --------------------------------------------------------------------------- field values

    @Test
    void rejectsDuplicateRoute() {
        String duplicate = EXAMPLE.replace(WORLD_ENTRY, WORLD_ENTRY + "," + entry(
                "play", Direction.CLIENTBOUND, 12, Lane.CONTROL));
        assertTrue(reject(duplicate).getMessage().contains("duplicate"));
    }

    @Test
    void rejectsDuplicateRootField() {
        // 13.7: reject duplicates. Last-wins would leave a valid schema here (1), so only a parser
        // that rejects the repeated member name catches this.
        String duplicate = EXAMPLE.replace("\"schema\":1,", "\"schema\":9,\"schema\":1,");
        assertTrue(reject(duplicate).getMessage().contains("duplicate member name"));
    }

    @Test
    void rejectsDuplicateEntryField() {
        // 13.7: reject duplicates at every object level. Last-wins would make this a valid CONTROL
        // entry, so the rejection is the repeated member name, not the lane.
        String duplicate = EXAMPLE.replace(CONTROL_ENTRY, CONTROL_ENTRY.replace(
                "\"lane\":\"control\"", "\"lane\":\"world\",\"lane\":\"control\""));
        assertTrue(reject(duplicate).getMessage().contains("duplicate member name"));
    }

    @Test
    void rejectsUnknownLane() {
        assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\"", "\"lane\":\"fast\"")))
                .getMessage().contains("unknown lane"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\"", "\"lane\":\"CONTROL\"")));
    }

    @Test
    void rejectsUnknownState() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"state\":\"play\"", "\"state\":\"spawn\"")))
                .getMessage().contains("unknown state"));
    }

    @Test
    void rejectsUnknownDirection() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"direction\":\"clientbound\"", "\"direction\":\"both\"")))
                .getMessage().contains("unknown direction"));
    }

    @Test
    void rejectsUnknownDependency() {
        assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"dependency\":\"lane\"", "\"dependency\":\"barrier\"")))
                .getMessage().contains("unknown dependency"));
    }

    @Test
    void rejectsPacketIdOutOfRange() {
        assertTrue(reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":-1"))
                .getMessage().contains("out of range"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":2147483648"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":1.5"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":\"12\""));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":null"));
    }

    @Test
    void rejectsProtocolNumberOutOfRange() {
        assertTrue(reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":0"))
                .getMessage().contains("protocol number out of range"));
        reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":-1"));
        reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":\"12345\""));
    }

    @Test
    void rejectsDefaultThatIsNotControl() {
        assertTrue(reject(EXAMPLE.replace("\"default\":\"control\"", "\"default\":\"world\""))
                .getMessage().contains("default must be"));
    }

    @Test
    void rejectsMissingEntryField() {
        reject(EXAMPLE.replace(CONTROL_ENTRY, "{\"state\":\"play\",\"direction\":\"clientbound\","
                + "\"lane\":\"control\",\"dependency\":\"fence\"}"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\",", "")));
    }

    @Test
    void rejectsDependencyThatDoesNotMatchTheLane() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"dependency\":\"fence\"", "\"dependency\":\"lane\"")))
                .getMessage().contains("requires dependency"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"dependency\":\"lane\"", "\"dependency\":\"fence\"")));
    }

    // --------------------------------------------------- 13.5 packets that MUST use CONTROL

    @Test
    void rejectsAuxiliaryLaneInAStateOtherThanPlay() {
        // 13.5: state transitions, acknowledgements, registry/dimension and compression changes,
        // disconnects and unknown/custom packets MUST use CONTROL; 13.3 opens the auxiliary lanes
        // only after activation, which is at the end of configuration.
        for (String state : new String[] {"handshaking", "status", "login", "configuration"}) {
            assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                    entry(state, Direction.CLIENTBOUND, 11, Lane.REALTIME)))
                    .getMessage().contains("must use CONTROL"), "state " + state);
            reject(EXAMPLE.replace(WORLD_ENTRY, entry(state, Direction.CLIENTBOUND, 12, Lane.WORLD)));
        }
    }

    @Test
    void acceptsControlInAnyState() {
        RoutingTable table = RoutingTableLoader.parse(EXAMPLE.replace(REALTIME_ENTRY,
                entry("configuration", Direction.CLIENTBOUND, 11, Lane.CONTROL)));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.CONFIGURATION, Direction.CLIENTBOUND, 11));
    }

    @Test
    void rejectsAPacketKnownToMustUseControlRoutedElsewhere() {
        Set<RouteKey> mandatory = new HashSet<>();
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));

        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(EXAMPLE, mandatory));
        assertTrue(e.getMessage().contains("13.5"));
        assertTrue(e.getMessage().contains("must use CONTROL"));

        // The same table is valid once that packet is not known to be control-only.
        assertEquals(Lane.REALTIME, RoutingTableLoader.parse(EXAMPLE, Collections.<RouteKey>emptySet())
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
    }

    @Test
    void mandatoryControlIsMatchedByExactKey() {
        Set<RouteKey> mandatory = new HashSet<>();
        // Another protocol number, direction, state or id: none of these is this table's key.
        mandatory.add(new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.SERVERBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.CONFIGURATION, Direction.CLIENTBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 14));
        RoutingTableLoader.parse(EXAMPLE, mandatory);

        // Declaring an entry that is CONTROL anyway mandatory is accepted.
        Set<RouteKey> controlEntry = new HashSet<>();
        controlEntry.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
        assertEquals(Lane.CONTROL, RoutingTableLoader.parse(EXAMPLE, controlEntry)
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
    }

    @Test
    void negativePacketIdIsARoutingTableException() {
        // 13.7: a packet id is nonnegative, and the routing package reports an invalid entry with
        // its own exception type (still an IllegalArgumentException, so callers catching that work).
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(
                        EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":-1")));
        assertTrue(e.getMessage().contains("out of range"), e.getMessage());
    }

    // ------------------------------------------------- 13.5 must-use-CONTROL set of a protocol

    @Test
    void loadResourceEnforcesTheMandatoryControlSetOfThatProtocol() throws IOException {
        // protocol-9000.json routes packet 11 to REALTIME and 9000's own set (13.5) lists that key,
        // so loading by protocol number must apply the rule and reject the table.
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.loadResource(9000));
        assertTrue(e.getMessage().contains("13.5"), e.getMessage());
        assertTrue(e.getMessage().contains("must use CONTROL"), e.getMessage());

        // The same bytes are valid when the caller explicitly passes no mandatory keys, which shows
        // the rejection came from the protocol's own set and not from the caller.
        assertEquals(Lane.REALTIME, RoutingTableLoader.loadResource(
                RoutingTableLoader.resourcePath(9000), Collections.<RouteKey>emptySet())
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
    }

    @Test
    void loadResourceRefusesAProtocolWithoutAMandatoryControlSet() {
        // protocol-9001.json is a valid table, but 13.5's set for 9001 is not on the classpath. An
        // assumed empty set would silently stop enforcing the rule, so the load must fail instead.
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.loadResource(9001));
        assertTrue(e.getMessage().contains("13.5"), e.getMessage());
        assertTrue(e.getMessage().contains(RoutingTableLoader.mandatoryControlResourcePath(9001)),
                e.getMessage());
    }

    @Test
    void theMandatoryControlSetIsValidated() {
        String set = "{\"schema\":1,\"protocol\":12345,\"mandatory_control\":["
                + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11}]}";
        assertEquals(Collections.singleton(
                        new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 11)),
                RoutingTableLoader.parseMandatoryControlSet(
                        set.getBytes(StandardCharsets.UTF_8), 12345, "test"));

        // An explicit empty set is legal, which is what the synthetic example ships; assuming one is
        // what the loader must never do.
        assertTrue(RoutingTableLoader.parseMandatoryControlSet(
                "{\"schema\":1,\"protocol\":12345,\"mandatory_control\":[]}"
                        .getBytes(StandardCharsets.UTF_8), 12345, "test").isEmpty());

        for (String[] bad : new String[][] {
            // Another protocol's set must never be applied to this table.
            {"{\"schema\":1,\"protocol\":763,\"mandatory_control\":[]}", "13.5"},
            {"{\"schema\":2,\"protocol\":12345,\"mandatory_control\":[]}", "13.5"},
            {"{\"protocol\":12345,\"mandatory_control\":[]}", "13.5"},
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":{}}", "13.5"},
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":[7]}", "13.5"},
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":[{\"state\":\"play\"}]}", "13.5"},
            // A key's packet id is 13.7's plain nonnegative integer.
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":["
                    + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":1e2}]}", "13.7"},
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":["
                    + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11},"
                    + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11}]}", "13.5"},
            {"{\"schema\":1,\"protocol\":12345,\"mandatory_control\":[],\"extra\":1}", "13.5"},
        }) {
            RoutingTableException e = assertThrows(RoutingTableException.class,
                    () -> RoutingTableLoader.parseMandatoryControlSet(
                            bad[0].getBytes(StandardCharsets.UTF_8), 12345, "test"), bad[0]);
            assertTrue(e.getMessage().contains(bad[1]), bad[0] + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- the fixture does not ship

    @Test
    void theSyntheticExampleIsATestFixtureOnly() {
        // 13.7's example is synthetic, so a shipped jar must not be able to select it: the file
        // lives in core/src/test/resources, never in core/src/main/resources.
        String relative = RoutingTableLoader.resourcePath(12345);
        for (String mainResources : new String[] {
            "src/main/resources/", "core/src/main/resources/", "build/resources/main/",
        }) {
            assertFalse(new File(mainResources + relative).exists(),
                    mainResources + relative + " must not be on a production classpath");
        }
        assertTrue(new File("src/test/resources/" + relative).exists()
                        || new File("core/src/test/resources/" + relative).exists(),
                relative + " must live in test resources");
    }

    // ---------------------------------------------------------- bounded diagnostics (13.7)

    @Test
    void hugeExponentsAreRejectedWithAShortMessage() {
        // packet_id 1e1000000000 has scale -1e9 and 1e-1000000000 has scale +1e9: rendering either
        // with toPlainString would build a ~1e9-character string. The load must fail fast and the
        // message must stay short.
        for (String huge : new String[] {
            "1e1000000000", "1E1000000000", "-1e1000000000", "1e-1000000000", "1.5e-1000000000",
        }) {
            RoutingTableException e = reject(EXAMPLE.replace("\"packet_id\":12",
                    "\"packet_id\":" + huge));
            assertTrue(e.getMessage().contains("13.7"), huge + ": " + e.getMessage());
            assertTrue(e.getMessage().contains("packet_id"), huge + ": " + e.getMessage());
            assertTrue(e.getMessage().length() <= 160, huge + " made a " + e.getMessage().length()
                    + "-character message: " + e.getMessage());
        }
    }

    @Test
    void aLongOutOfRangeIntegerIsTruncatedInTheMessage() {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            digits.append('9');
        }
        RoutingTableException e = reject(EXAMPLE.replace("\"packet_id\":12",
                "\"packet_id\":" + digits));
        assertTrue(e.getMessage().contains("out of range"), e.getMessage());
        assertTrue(e.getMessage().contains("..."), e.getMessage());
        assertTrue(e.getMessage().length() <= 160, e.getMessage());
    }

    @Test
    void aLongUnknownValueIsTruncatedInTheMessage() {
        StringBuilder lane = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            lane.append('x');
        }
        RoutingTableException e = reject(EXAMPLE.replace("\"lane\":\"realtime\"",
                "\"lane\":\"" + lane + "\""));
        assertTrue(e.getMessage().contains("unknown lane"), e.getMessage());
        assertTrue(e.getMessage().length() <= 160, e.getMessage());
    }

    // ------------------------------------------- 13.7 integer fields are plain JSON integers

    @Test
    void integerFieldsMustBePlainIntegers() {
        // A BigDecimal would read 1e2 as 100, 1e0 as 1, 1.0 as 1 and -0 as 0, so the lexeme decides.
        for (String notPlain : new String[] {"1e2", "1E2", "1e0", "1.0", "0.0", "-0"}) {
            RoutingTableException e = reject(EXAMPLE.replace("\"packet_id\":12",
                    "\"packet_id\":" + notPlain));
            assertTrue(e.getMessage().contains("13.7"), notPlain + ": " + e.getMessage());
            assertTrue(e.getMessage().contains("plain integer"), notPlain + ": " + e.getMessage());
        }
        assertTrue(reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":1e2"))
                .getMessage().contains("plain integer"));
        assertTrue(reject(EXAMPLE.replace("\"schema\":1", "\"schema\":1e0"))
                .getMessage().contains("plain integer"));

        // The plain spelling of the same value still loads.
        assertEquals(Lane.WORLD, RoutingTableLoader.parse(
                EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":100"))
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 100));
    }

    // ------------------------------- the strict parser's unicode escapes, through the resource

    @Test
    void acceptsAFourHexDigitUnicodeEscape() {
        // 13.7: the resource is JSON, so the escape backslash-u 0077 is 'w'.
        RoutingTable table = RoutingTableLoader.parse(EXAMPLE.replace("\"lane\":\"world\"",
                "\"lane\":\"\\u0077orld\""));
        assertEquals(Lane.WORLD, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 12));
    }

    @Test
    void rejectsUnicodeEscapesThatAreNotFourHexDigits() {
        // A backslash-u escape is exactly four hex digits. A leading sign is not a hex digit, so
        // "worl" plus the escape "backslash-u + 064" used to be "world" (Integer.parseInt accepts a
        // sign); a shorter or non-hex escape is not padded from what follows the quote either.
        for (String escape : new String[] {"\\u+064", "\\u-064", "\\u12g4", "\\u00"}) {
            RoutingTableException e = reject(EXAMPLE.replace("\"lane\":\"world\"",
                    "\"lane\":\"worl" + escape + "\""));
            assertTrue(e.getMessage().contains("malformed JSON"), escape + ": " + e.getMessage());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    @Test
    void aResourceAtTheSizeLimitParsesAndOneByteMoreIsRejectedBeforeDecoding() throws IOException {
        byte[] table = ("{\"schema\":1,\"protocol\":12345,\"default\":\"control\",\"routes\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] atLimit = new byte[RoutingTableLoader.MAX_RESOURCE_BYTES];
        java.util.Arrays.fill(atLimit, (byte) ' ');
        System.arraycopy(table, 0, atLimit, 0, table.length);
        assertEquals(12345, RoutingTableLoader.parse(atLimit).protocolNumber());

        byte[] over = java.util.Arrays.copyOf(atLimit, atLimit.length + 1);
        over[over.length - 1] = ' ';
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(over));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());

        byte[] set = "{\"schema\":1,\"protocol\":12345,\"mandatory_control\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] setAtLimit = new byte[RoutingTableLoader.MAX_RESOURCE_BYTES];
        java.util.Arrays.fill(setAtLimit, (byte) ' ');
        System.arraycopy(set, 0, setAtLimit, 0, set.length);
        assertTrue(RoutingTableLoader.parseMandatoryControlSet(setAtLimit, 12345, "test").isEmpty());
        byte[] setOver = java.util.Arrays.copyOf(setAtLimit, setAtLimit.length + 1);
        setOver[setOver.length - 1] = ' ';
        RoutingTableException setError = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parseMandatoryControlSet(setOver, 12345, "test"));
        assertTrue(setError.getMessage().contains("larger than"), setError.getMessage());
    }

    @Test
    void readingStopsAsSoonAsAResourceExceedsTheSizeLimit() throws IOException {
        final long[] served = {0};
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                served[0]++;
                return ' ';
            }
        };
        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.readAll(endless));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        assertTrue(served[0] <= RoutingTableLoader.MAX_RESOURCE_BYTES + 8192L, "read " + served[0]);
    }
}
