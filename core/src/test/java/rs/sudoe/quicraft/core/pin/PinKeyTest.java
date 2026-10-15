// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Normalization of the logical server key (docs/protocol.md 12.15). */
class PinKeyTest {
    @Test
    void lowercasesIdnaNamesAndStripsOneTrailingDot() {
        assertEquals("mc.example.com", PinKey.of("MC.Example.COM.", 25565).host());
        assertEquals("mc.example.com", PinKey.normalizeHost("mc.example.com"));
        assertEquals("mc.example.com", PinKey.normalizeHost("mc.example.com."));
        assertEquals("xn--bcher-kva.example", PinKey.normalizeHost("Bücher.Example"));
        assertEquals("play.example.co.uk", PinKey.normalizeHost("PLAY.EXAMPLE.CO.UK"));
        assertEquals("my_server.example.com", PinKey.normalizeHost("my_server.example.com"));
    }

    @Test
    void everySpellingOfOneNameSharesOneKey() {
        PinKey dotted = PinKey.of("MC.Example.COM.", 25565);
        PinKey plain = PinKey.of("mc.example.com", 25565);
        assertEquals(dotted, plain);
        assertEquals(dotted.hashCode(), plain.hashCode());
        assertEquals("mc.example.com:25565", plain.toString());
        assertNotEquals(plain, PinKey.of("mc.example.com", 25566));
        assertNotEquals(plain, PinKey.of("other.example.com", 25565));
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.1", "0.0.0.0", "255.255.255.255", "10.1.2.3"})
    void keepsACanonicalIpv4Literal(String address) {
        assertEquals(address, PinKey.normalizeHost(address));
        assertEquals(address, PinKey.of(address, 25565).host());
    }

    @Test
    void acceptsABracketedLiteralButNothingElseInBrackets() {
        assertEquals("192.0.2.1", PinKey.normalizeHost("[192.0.2.1]"));
        assertEquals("::1", PinKey.normalizeHost("[::1]"));
        assertEquals("2001:db8::1", PinKey.normalizeHost("[2001:0DB8::0001]"));
        assertThrows(IllegalArgumentException.class, () -> PinKey.normalizeHost("[mc.example.com]"));
        assertThrows(IllegalArgumentException.class, () -> PinKey.normalizeHost("[::1"));
    }

    @Test
    void compressesAndLowercasesIpv6() {
        assertEquals("2001:db8::1", PinKey.normalizeHost("2001:0db8:0:0:0:0:0:1"));
        assertEquals("2001:db8::1", PinKey.normalizeHost("2001:DB8::1"));
        assertEquals("2001:db8::1", PinKey.normalizeHost("2001:db8:0:0:0:0:0:0001"));
        assertEquals("2001:db8::1:0:0:1", PinKey.normalizeHost("2001:db8:0:0:1:0:0:1"),
                "the leftmost longest zero run is the one compressed");
        assertEquals("::", PinKey.normalizeHost("0:0:0:0:0:0:0:0"));
        assertEquals("::1", PinKey.normalizeHost("::1"));
        assertEquals("1:2:3:4:5:6:7:8", PinKey.normalizeHost("1:2:3:4:5:6:7:8"));
        assertEquals("::ffff:c000:201", PinKey.normalizeHost("::ffff:192.0.2.1"));
        assertEquals("fe80::1", PinKey.normalizeHost("FE80:0000:0000:0000:0000:0000:0000:0001"));
        assertEquals("[2001:db8::1]:25565", PinKey.of("2001:db8::1", 25565).toString());
    }

    @Test
    void followsIdna2003WhereUts46CouldDiffer() {
        // java.net.IDN is IDNA2003: nameprep maps the sharp s to ss and folds the final sigma, so two
        // spellings that UTS 46 could keep apart share one key here, and one name never splits in two.
        assertEquals("strasse.example", PinKey.normalizeHost("straße.example"));
        assertEquals(PinKey.normalizeHost("σ.example"), PinKey.normalizeHost("ς.example"));
    }

    @Test
    void normalizationIsIdempotent() {
        List<String> hosts = new ArrayList<>();
        hosts.add("MC.Example.COM.");
        hosts.add("Bücher.Example");
        hosts.add("2001:0DB8:0:0:0:0:0:1");
        hosts.add("[::1]");
        hosts.add("192.0.2.1");
        hosts.add("::ffff:192.0.2.1");
        for (String host : hosts) {
            String once = PinKey.normalizeHost(host);
            assertEquals(once, PinKey.normalizeHost(once), host);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "example..com",
        "example.com..",
        ".example.com",
        "fe80::1%eth0",
        "[fe80::1%eth0]",
        "192.0.2.01",
        "192.0.2.256",
        "192.0.2",
        "192.0.2.1.5",
        "1.2.3.4:25565",
        "2001:db8:::1",
        "1:2:3:4:5:6:7:8:9",
        ":::",
        "2001:db8::1::2",
        "exa mple.com",
        "a/b",
    })
    void rejectsHostsItCannotCanonicalize(String host) {
        assertThrows(IllegalArgumentException.class, () -> PinKey.normalizeHost(host), host);
        assertThrows(IllegalArgumentException.class, () -> PinKey.of(host, 25565), host);
    }

    @Test
    void rejectsNullAndImpossiblePorts() {
        assertThrows(IllegalArgumentException.class, () -> PinKey.normalizeHost(null));
        assertThrows(IllegalArgumentException.class, () -> PinKey.of("mc.example.com", 0));
        assertThrows(IllegalArgumentException.class, () -> PinKey.of("mc.example.com", 65536));
        assertThrows(IllegalArgumentException.class, () -> PinKey.of("mc.example.com", -1));
        assertEquals(1, PinKey.of("mc.example.com", 1).port());
        assertEquals(65535, PinKey.of("mc.example.com", 65535).port());
    }

    @Test
    void anAllDigitsLabelWithoutDotsIsANameNotAnAddress() {
        assertEquals("12345", PinKey.normalizeHost("12345"));
        assertFalse(PinKey.normalizeHost("12345").contains("."));
        assertTrue(PinKey.normalizeHost("123.abc").contains("."));
    }
}
