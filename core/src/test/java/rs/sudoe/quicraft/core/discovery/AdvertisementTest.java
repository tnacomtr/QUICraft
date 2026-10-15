// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class AdvertisementTest {
    private static final String VANILLA = "{\"version\":{\"name\":\"26.1.2\",\"protocol\":775},"
            + "\"enforcesSecureChat\":false,\"description\":{\"text\":\"A \\\"quoted\\\" MOTD \\u00e9 }\"},"
            + "\"players\":{\"max\":20,\"online\":1,\"sample\":[{\"name\":\"bench0\",\"id\":\"0e41b182-b541-31fe-a149-652683577de6\"}]}}";

    private static Fingerprint fp;

    @BeforeAll
    static void setUp() throws Exception {
        fp = ServerIdentity.generate().fingerprint();
    }

    @Test
    void insertsAndExtractsTheSameAdvertisement() {
        Advertisement ad = Advertisement.v1(25565, fp);
        String status = ad.insertInto(VANILLA);
        assertEquals(Optional.of(ad), Advertisement.extract(status));
        // Every vanilla member survives untouched.
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) Json.parse(VANILLA);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) Json.parse(status);
        after.remove("quicraft:quic");
        assertEquals(before.toString(), after.toString());
    }

    @Test
    void memberHasTheDocumentedShape() {
        assertEquals("{\"v\":1,\"port\":25577,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}",
                Advertisement.v1(25577, fp).toJson());
    }

    @Test
    void worksOnAnEmptyObject() {
        assertTrue(Advertisement.extract(Advertisement.v1(1, fp).insertInto("{}")).isPresent());
    }

    @Test
    void leavesTheResponseAloneWhenItWouldExceedTheCap() {
        Advertisement ad = Advertisement.v1(25565, fp);
        int memberLength = ad.insertInto("{\"a\":1}").length() - "{\"a\":1}".length();
        // A response that fits exactly with the member, and one a single character longer.
        String fits = padded(Advertisement.STATUS_MAX_LENGTH - memberLength);
        String tooBig = padded(Advertisement.STATUS_MAX_LENGTH - memberLength + 1);
        assertEquals(Advertisement.STATUS_MAX_LENGTH, ad.insertInto(fits).length());
        assertSame(tooBig, ad.insertInto(tooBig));
    }

    @Test
    void leavesNonObjectsMalformedJsonAndExistingMembersAlone() {
        Advertisement ad = Advertisement.v1(25565, fp);
        for (String s : new String[] {"[]", "\"x\"", "{", "{\"a\":}", "", "{} {}", null}) {
            assertSame(s, ad.insertInto(s), "input: " + s);
        }
        String already = ad.insertInto(VANILLA);
        assertSame(already, Advertisement.v1(1, fp).insertInto(already));
    }

    @Test
    void ignoresUnknownFieldsButRejectsAnythingInvalid() {
        String good = "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"";
        assertTrue(extract(good + ",\"future\":{\"x\":[1,2]}}").isPresent());
        assertFalse(extract("{\"v\":2,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":\"1\",\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":0,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":65536,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565.5,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());  // unreleased pre-preamble format
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"sha256:xyz\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\"}").isPresent());
        assertFalse(extract("[]").isPresent());
        assertFalse(Advertisement.extract(VANILLA).isPresent());
        assertFalse(Advertisement.extract("not json").isPresent());
        assertFalse(Advertisement.extract(null).isPresent());
    }

    @Test
    void hostileNestingIsRejectedWithoutBlowingTheStack() {
        StringBuilder deep = new StringBuilder("{\"quicraft:quic\":");
        for (int i = 0; i < 100_000; i++) {
            deep.append('[');
        }
        assertFalse(Advertisement.extract(deep.toString()).isPresent());
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void parsesJsonEscapesAndNumbers() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(
                " {\"s\":\"a\\\"b\\\\c\\/\\n\\u0041\",\"n\":-1.5e3,\"t\":true,\"f\":false,\"z\":null,\"l\":[]} ");
        assertEquals("a\"b\\c/\nA", m.get("s"));
        assertEquals(-1500, ((java.math.BigDecimal) m.get("n")).intValueExact());
        assertEquals(Boolean.TRUE, m.get("t"));
        assertSame(Json.NULL, m.get("z"));
        for (String bad : new String[] {"{\"a\":01}", "{\"a\":1.}", "{\"a\":\"\u0001\"}", "{'a':1}", "{\"a\":1,}", "tru"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void extractKeepsTheShippedQuicraft2OnlyBehaviour() {
        String v2 = "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}";
        // A /2 advertisement parses to exactly the value the shipped code always produced.
        assertEquals(Optional.of(Advertisement.v1(25565, fp)), extract(v2));
        assertEquals(extract(v2), extractForSelection(v2));
        Advertisement inserted = Advertisement.v1(25565, fp);
        assertEquals(Optional.of(inserted), Advertisement.extract(inserted.insertInto(VANILLA)));
        // Nor does routing on a /2 advertisement change what the shipped code reads.
        assertEquals(Optional.of(Advertisement.v1(25565, fp)),
                extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp
                        + "\",\"routing\":{\"775\":\"" + digest('a') + "\"}}"));
        // 13.1: a client that does not recognize quicraft/3 treats it as no advertisement.
        String v3 = "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/3\",\"fp\":\"" + fp + "\"}";
        assertFalse(extract(v3).isPresent());
        assertTrue(extractForSelection(v3).isPresent());
    }

    @Test
    void acceptsQuicraft3ForSelectionAndKeepsTheV1Fields() {
        Advertisement ad = extractForSelection(
                        "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/3\",\"fp\":\"" + fp + "\"}")
                .orElseThrow(() -> new AssertionError("quicraft/3 must parse for selection"));
        assertEquals(1, ad.version());
        assertEquals(25565, ad.port());
        assertEquals("quicraft/3", ad.alpn());
        assertEquals(fp, ad.fingerprint());
        assertTrue(ad.routing().isEmpty());
        assertEquals(Optional.empty(), ad.routingDigest(775));
        assertTrue(extractForSelection(
                "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        // Neither entry point recognizes anything else (13.1).
        assertFalse(extractForSelection(
                "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/4\",\"fp\":\"" + fp + "\"}").isPresent());
    }

    @Test
    void routingMemberRoundTripsThroughJson() {
        Map<Integer, String> routing = new LinkedHashMap<>();
        routing.put(775, digest('a'));
        routing.put(0, digest('b'));
        Advertisement ad = Advertisement.of(Protocol.ALPN_V3, 25565, fp, routing);
        // Keys ascend by protocol number, so the encoding is deterministic.
        assertEquals("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/3\",\"fp\":\"" + fp + "\",\"routing\":{"
                + "\"0\":\"" + digest('b') + "\",\"775\":\"" + digest('a') + "\"}}", ad.toJson());
        assertEquals(Optional.of(digest('a')), ad.routingDigest(775));
        assertEquals(Optional.of(digest('b')), ad.routingDigest(0));
        assertEquals(Optional.empty(), ad.routingDigest(776));

        String status = ad.insertInto(VANILLA);
        assertEquals(Optional.of(ad), Advertisement.extractForSelection(status));
        Advertisement reparsed = Advertisement.extractForSelection(status).orElseThrow(AssertionError::new);
        assertEquals(ad.toJson(), reparsed.toJson());
        assertEquals(ad.hashCode(), reparsed.hashCode());
        // A hand-written status response with the same member parses to the same value too.
        assertEquals(ad, extractForSelection("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/3\",\"fp\":\"" + fp
                + "\",\"routing\":{\"775\":\"" + digest('a') + "\",\"0\":\"" + digest('b') + "\"}}")
                .orElseThrow(AssertionError::new));
    }

    @Test
    void routingKeysFollowTheDocumentedNumberFormat() {
        // 13.1: no sign, no leading zero except "0", at most 10 digits, fits a signed 32-bit int.
        for (String key : new String[] {"0", "1", "775", "2147483647"}) {
            Optional<Advertisement> parsed = extractForSelection(
                    withRouting("quicraft/3", "{\"" + key + "\":\"" + digest('a') + "\"}"));
            assertTrue(parsed.isPresent(), "key: " + key);
            assertEquals(1, parsed.get().routing().size(), "key: " + key);
        }
        for (String key : new String[] {"+5", "-5", "007", "05", "5 ", " 5", "5.0", "0x5",
                "2147483648", "99999999999"}) {
            Optional<Advertisement> parsed = extractForSelection(
                    withRouting("quicraft/3", "{\"" + key + "\":\"" + digest('a') + "\"}"));
            assertTrue(parsed.isPresent(), "key: " + key);
            assertTrue(parsed.get().routing().isEmpty(), "key: " + key);
        }
    }

    @Test
    void malformedRoutingIsIgnoredAndTheRestStillParses() {
        for (String routing : new String[] {
            "[]", "\"x\"", "null", "5",
            "{\"+5\":\"" + digest('a') + "\"}",
            "{\"-5\":\"" + digest('a') + "\"}",
            "{\"007\":\"" + digest('a') + "\"}",
            "{\"05\":\"" + digest('a') + "\"}",
            "{\"5 \":\"" + digest('a') + "\"}",
            "{\"abc\":\"" + digest('a') + "\"}",
            "{\"2147483648\":\"" + digest('a') + "\"}",
            "{\"5\":\"SHA256:" + hex('a') + "\"}",
            "{\"5\":\"sha256:" + hex('A') + "\"}",
            "{\"5\":\"sha256:short\"}",
            "{\"5\":\"sha256:" + hex('a') + "0\"}",
            "{\"5\":\"md5:" + hex('a') + "\"}",
            "{\"5\":123}", "{\"5\":null}", "{\"5\":[\"" + digest('a') + "\"]}",
            "{\"5\":\"" + digest('a') + "\",\"6\":\"bad\"}",
        }) {
            Optional<Advertisement> parsed = extract(withRouting("quicraft/2", routing));
            assertTrue(parsed.isPresent(), "routing: " + routing);
            assertEquals("quicraft/2", parsed.get().alpn(), "routing: " + routing);
            assertEquals(Collections.emptyMap(), parsed.get().routing(), "routing: " + routing);
        }
    }

    @Test
    void boundsTheRoutingMember() {
        Optional<Advertisement> atLimit = extractForSelection(
                withRouting("quicraft/3", routingJson(Advertisement.MAX_ROUTING_ENTRIES)));
        assertTrue(atLimit.isPresent());
        assertEquals(Advertisement.MAX_ROUTING_ENTRIES, atLimit.get().routing().size());
        assertEquals(Optional.of(digest('a')), atLimit.get().routingDigest(Advertisement.MAX_ROUTING_ENTRIES - 1));

        Optional<Advertisement> overLimit = extract(withRouting("quicraft/2",
                routingJson(Advertisement.MAX_ROUTING_ENTRIES + 1)));
        assertTrue(overLimit.isPresent());
        assertTrue(overLimit.get().routing().isEmpty());
    }

    @Test
    void keepsTheResponseCapAndDropsRoutingBeforeLeavingTheResponseUnchanged() {
        Advertisement full = routingAdvertisement();
        Advertisement trimmed = Advertisement.of(Protocol.ALPN_V3, 25565, fp, Collections.<Integer, String>emptyMap());
        int fullLength = full.insertInto("{\"a\":1}").length() - "{\"a\":1}".length();
        int trimmedLength = trimmed.insertInto("{\"a\":1}").length() - "{\"a\":1}".length();
        assertTrue(fullLength > trimmedLength);

        // Room for the whole member: it is inserted with its routing.
        String roomy = padded(Advertisement.STATUS_MAX_LENGTH - fullLength);
        String roomyResult = full.insertInto(roomy);
        assertEquals(Advertisement.STATUS_MAX_LENGTH, roomyResult.length());
        assertEquals(Optional.of(full), Advertisement.extractForSelection(roomyResult));

        // Only room without routing: one retry drops routing instead of inserting nothing.
        String tight = padded(Advertisement.STATUS_MAX_LENGTH - trimmedLength);
        String tightResult = full.insertInto(tight);
        assertEquals(Advertisement.STATUS_MAX_LENGTH, tightResult.length());
        assertEquals(Optional.of(trimmed), Advertisement.extractForSelection(tightResult));

        // Room for neither: the response is left exactly as it was.
        String tooBig = padded(Advertisement.STATUS_MAX_LENGTH - trimmedLength + 1);
        assertSame(tooBig, full.insertInto(tooBig));
    }

    private static Advertisement routingAdvertisement() {
        Map<Integer, String> routing = new LinkedHashMap<>();
        for (int i = 0; i < Advertisement.MAX_ROUTING_ENTRIES; i++) {
            routing.put(i, digest('a'));
        }
        return Advertisement.of(Protocol.ALPN_V3, 25565, fp, routing);
    }

    @Test
    void refusesToEncodeAnInvalidAdvertisement() {
        Map<Integer, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= Advertisement.MAX_ROUTING_ENTRIES; i++) {
            tooMany.put(i, digest('a'));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 25565, fp, tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 25565, fp, Collections.singletonMap(775, "sha256:xyz")));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 25565, fp, Collections.singletonMap(-1, digest('a'))));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 25565, fp, Collections.singletonMap(775, null)));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of("quicraft/4", 25565, fp, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 0, fp, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> Advertisement.of(Protocol.ALPN_V3, 65536, fp, Collections.emptyMap()));
        assertEquals(Collections.emptyMap(),
                Advertisement.of(Protocol.ALPN_V3, 25565, fp, null).routing());
    }

    /** A status response whose advertisement has the given ALPN and raw routing member value. */
    private static String withRouting(String alpn, String routingJson) {
        return "{\"v\":1,\"port\":25565,\"alpn\":\"" + alpn + "\",\"fp\":\"" + fp + "\",\"routing\":"
                + routingJson + "}";
    }

    /** A routing object with {@code entries} entries, keyed {@code 0..entries-1}. */
    private static String routingJson(int entries) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < entries; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(i).append("\":\"").append(digest('a')).append('"');
        }
        return sb.append('}').toString();
    }

    /** 64 copies of {@code c}, the hex body of a digest. */
    private static String hex(char c) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String digest(char c) {
        return "sha256:" + hex(c);
    }

    private static Optional<Advertisement> extract(String member) {
        return Advertisement.extract("{\"description\":\"x\",\"quicraft:quic\":" + member + "}");
    }

    private static Optional<Advertisement> extractForSelection(String member) {
        return Advertisement.extractForSelection("{\"description\":\"x\",\"quicraft:quic\":" + member + "}");
    }

    private static String padded(int length) {
        String head = "{\"favicon\":\"";
        String tail = "\"}";
        StringBuilder sb = new StringBuilder(head);
        while (sb.length() < length - tail.length()) {
            sb.append('A');
        }
        return sb.append(tail).toString();
    }
}
