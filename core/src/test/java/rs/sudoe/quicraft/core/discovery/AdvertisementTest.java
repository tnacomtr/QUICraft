// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
        assertEquals("{\"v\":1,\"port\":25577,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}",
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
        String good = "{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"";
        assertTrue(extract(good + ",\"future\":{\"x\":[1,2]}}").isPresent());
        assertFalse(extract("{\"v\":2,\"port\":25565,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":\"1\",\"port\":25565,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":0,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":65536,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565.5,\"alpn\":\"quicraft/1\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/2\",\"fp\":\"" + fp + "\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/1\",\"fp\":\"sha256:xyz\"}").isPresent());
        assertFalse(extract("{\"v\":1,\"port\":25565,\"alpn\":\"quicraft/1\"}").isPresent());
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

    private static Optional<Advertisement> extract(String member) {
        return Advertisement.extract("{\"description\":\"x\",\"quicraft:quic\":" + member + "}");
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
