// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.TransportSelection.Missing;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.connect.TransportSelection.Transport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * Table-driven coverage of every rule of docs/protocol.md 12.3, 13.1 and 12.16 for
 * {@link TransportSelection}.
 */
class TransportSelectionTest {
    private static final int PROTOCOL = 775;
    /** The client's routing digest R for {@link #PROTOCOL}, lowercase hex without the prefix. */
    private static final String LOCAL = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String OTHER = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private static final String ADVERTISED_LOCAL = "sha256:" + LOCAL;
    private static final Fingerprint FP = Fingerprint.parse("sha256:" + hex('c'));

    @Test
    void selectsTheDocumentedTransportForEveryRule() {
        List<Row> rows = Arrays.asList(
                // 12.1, §4: the setting comes first.
                row("tcp-only with no advertisement returns TCP", Transport.TCP, Missing.TCP_ONLY_SETTING, "tcp-only")
                        .setting(Mode.TCP_ONLY),
                row("tcp-only beats an enabled quicraft/3 advertisement", Transport.TCP, Missing.TCP_ONLY_SETTING, "tcp-only")
                        .setting(Mode.TCP_ONLY).advertisement(v3()).pin(PinState.SAME)
                        .flag(true).exporter(true).local(LOCAL),
                // §1: no advertisement is TCP.
                row("no advertisement returns TCP", Transport.TCP, Missing.NO_ADVERTISEMENT, "no advertisement"),
                row("no advertisement with an unusable pin store still returns TCP and names the store",
                        Transport.TCP, Missing.PIN_STORE_UNUSABLE, "pin store is unusable").pin(PinState.STORE_UNUSABLE),
                // 12.1, 13.1: an advertised /2 keeps the unchanged v1 behaviour.
                row("quicraft/2 returns V2", Transport.V2, Missing.ONLY_V2_ADVERTISED, "quicraft/2")
                        .advertisement(v2()),
                row("quicraft/2 with a routing member still returns V2", Transport.V2,
                        Missing.ONLY_V2_ADVERTISED, "quicraft/2")
                        .advertisement(v2WithRouting()).flag(true).exporter(true).local(LOCAL),
                row("quicraft/2 with an unusable pin store returns V2", Transport.V2,
                        Missing.PIN_STORE_UNUSABLE, "12.15").advertisement(v2()).pin(PinState.STORE_UNUSABLE),
                // 12.16: never trust F' over /2 and never fall back to /2 for a changed pin.
                row("quicraft/2 with a changed pin returns fresh TCP", Transport.TCP,
                        Missing.ONLY_V2_ADVERTISED, "12.16")
                        .advertisement(v2()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true).local(LOCAL),
                // 12.3, 13.1: every missing /3 prerequisite falls back to /2.
                row("quicraft/3 with the multistream flag off returns V2", Transport.V2,
                        Missing.MULTISTREAM_FLAG_OFF, "12.1")
                        .advertisement(v3()).exporter(true).local(LOCAL),
                row("quicraft/3 without the exporter probe returns V2", Transport.V2, Missing.NO_EXPORTER, "12.3")
                        .advertisement(v3()).flag(true).local(LOCAL),
                row("quicraft/3 without a local routing table returns V2", Transport.V2,
                        Missing.NO_LOCAL_ROUTING_TABLE, "no routing table")
                        .advertisement(v3()).flag(true).exporter(true),
                row("quicraft/3 without an advertised digest returns V2", Transport.V2,
                        Missing.NO_ADVERTISED_DIGEST, "no routing digest")
                        .advertisement(v3WithoutRouting()).flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a digest for another protocol returns V2", Transport.V2,
                        Missing.NO_ADVERTISED_DIGEST, "no routing digest")
                        .advertisement(v3WithDigestFor(776)).flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a mismatched digest returns V2", Transport.V2,
                        Missing.DIGEST_MISMATCH, "differs from the local one")
                        .advertisement(v3WithDigestFor(PROTOCOL, OTHER)).flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with every prerequisite returns V3", Transport.V3, Missing.NONE, "13.1")
                        .advertisement(v3()).flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a same pin returns V3 and ignores the backoff", Transport.V3,
                        Missing.NONE, "13.1")
                        .advertisement(v3()).pin(PinState.SAME).flag(true).exporter(true).local(LOCAL),
                // 12.15: a corrupt pin store disables /3, whatever else holds.
                row("quicraft/3 with an unusable pin store returns V2", Transport.V2,
                        Missing.PIN_STORE_UNUSABLE, "12.15")
                        .advertisement(v3()).pin(PinState.STORE_UNUSABLE)
                        .flag(true).exporter(true).local(LOCAL),
                // 12.16: a changed pin needs /3 and an allowed re-bind, else fresh TCP.
                row("quicraft/3 with a changed pin and a re-bind allowed returns V3", Transport.V3,
                        Missing.NONE, "12.16")
                        .advertisement(v3()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a changed pin during backoff returns TCP", Transport.TCP,
                        Missing.REBIND_BACKOFF, "backoff")
                        .advertisement(v3()).pin(PinState.CHANGED).rebind(false)
                        .flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a changed pin and the flag off returns TCP", Transport.TCP,
                        Missing.MULTISTREAM_FLAG_OFF, "12.1")
                        .advertisement(v3()).pin(PinState.CHANGED).rebind(true)
                        .exporter(true).local(LOCAL),
                row("quicraft/3 with a changed pin and no exporter returns TCP", Transport.TCP,
                        Missing.NO_EXPORTER, "12.3")
                        .advertisement(v3()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).local(LOCAL),
                row("quicraft/3 with a changed pin and no local table returns TCP", Transport.TCP,
                        Missing.NO_LOCAL_ROUTING_TABLE, "no routing table")
                        .advertisement(v3()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true),
                row("quicraft/3 with a changed pin and no advertised digest returns TCP", Transport.TCP,
                        Missing.NO_ADVERTISED_DIGEST, "no routing digest")
                        .advertisement(v3WithoutRouting()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true).local(LOCAL),
                row("quicraft/3 with a changed pin and a mismatched digest returns TCP", Transport.TCP,
                        Missing.DIGEST_MISMATCH, "differs from the local one")
                        .advertisement(v3WithDigestFor(PROTOCOL, OTHER)).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true).local(LOCAL),
                // §4: quic-only fails where the decision would be TCP, and keeps V2 and V3.
                row("quic-only with no advertisement fails", Transport.FAIL, Missing.NO_ADVERTISEMENT, "quic-only")
                        .setting(Mode.QUIC_ONLY),
                row("quic-only with quicraft/2 stays V2", Transport.V2, Missing.ONLY_V2_ADVERTISED, "quicraft/2")
                        .setting(Mode.QUIC_ONLY).advertisement(v2()).flag(true).exporter(true).local(LOCAL),
                row("quic-only with quicraft/2 and a changed pin fails", Transport.FAIL,
                        Missing.ONLY_V2_ADVERTISED, "quic-only")
                        .setting(Mode.QUIC_ONLY).advertisement(v2()).pin(PinState.CHANGED).rebind(true),
                row("quic-only with quicraft/3 and the flag off stays V2", Transport.V2,
                        Missing.MULTISTREAM_FLAG_OFF, "12.1")
                        .setting(Mode.QUIC_ONLY).advertisement(v3()).exporter(true).local(LOCAL),
                row("quic-only with quicraft/3 and a mismatched digest stays V2", Transport.V2,
                        Missing.DIGEST_MISMATCH, "differs from the local one")
                        .setting(Mode.QUIC_ONLY).advertisement(v3WithDigestFor(PROTOCOL, OTHER))
                        .flag(true).exporter(true).local(LOCAL),
                row("quic-only with quicraft/3 and an unusable pin store stays V2", Transport.V2,
                        Missing.PIN_STORE_UNUSABLE, "12.15")
                        .setting(Mode.QUIC_ONLY).advertisement(v3()).pin(PinState.STORE_UNUSABLE)
                        .flag(true).exporter(true).local(LOCAL),
                row("quic-only with quicraft/3 with every prerequisite stays V3", Transport.V3,
                        Missing.NONE, "13.1")
                        .setting(Mode.QUIC_ONLY).advertisement(v3()).flag(true).exporter(true).local(LOCAL),
                row("quic-only with a changed pin during backoff fails", Transport.FAIL,
                        Missing.REBIND_BACKOFF, "quic-only")
                        .setting(Mode.QUIC_ONLY).advertisement(v3()).pin(PinState.CHANGED).rebind(false)
                        .flag(true).exporter(true).local(LOCAL),
                row("quic-only with a changed pin and a missing digest fails", Transport.FAIL,
                        Missing.NO_ADVERTISED_DIGEST, "quic-only")
                        .setting(Mode.QUIC_ONLY).advertisement(v3WithoutRouting()).pin(PinState.CHANGED).rebind(true)
                        .flag(true).exporter(true).local(LOCAL));

        for (Row row : rows) {
            TransportSelection.Result result = row.result();
            assertEquals(row.expected, result.transport(), row.name + " -> " + result);
            assertEquals(row.expectedMissing, result.missing(), row.name + " -> " + result);
            assertEquals(row.expected == Transport.FAIL, result.isFail(), row.name + " -> " + result);
            assertEquals(row.expected == Transport.V3, result.missing() == Missing.NONE,
                    row.name + " -> " + result);
            assertTrue(result.reason().contains(row.fragment),
                    row.name + " reason \"" + result.reason() + "\" must mention \"" + row.fragment + "\"");
        }
    }

    @Test
    void theDefaultsAreTheShippedBehaviour() {
        assertFalse(TransportSelection.DEFAULT_MULTISTREAM_ENABLED);
        TransportSelection.Result result = TransportSelection.select(
                TransportSelection.Inputs.builder().clientProtocolNumber(PROTOCOL).build());
        assertEquals(Transport.TCP, result.transport());
        assertEquals(Missing.NO_ADVERTISEMENT, result.missing());
        assertTrue(result.reason().contains("no advertisement"));
        // The builder's default flag is the shipped one, so a /3 advertisement alone cannot pick V3.
        TransportSelection.Result blocked = TransportSelection.select(TransportSelection.Inputs.builder()
                .advertisement(v3()).exporterCapable(true).clientProtocolNumber(PROTOCOL)
                .localRoutingDigestHex(LOCAL).build());
        assertEquals(Transport.V2, blocked.transport());
        assertEquals(Missing.MULTISTREAM_FLAG_OFF, blocked.missing());
    }

    @Test
    void theUnusablePinStoreReasonIsFitToShowThePlayer() {
        TransportSelection.Result result = TransportSelection.select(TransportSelection.Inputs.builder()
                .advertisement(v3()).pinState(PinState.STORE_UNUSABLE)
                .multistreamEnabled(true).exporterCapable(true)
                .clientProtocolNumber(PROTOCOL).localRoutingDigestHex(LOCAL).build());
        assertEquals(Transport.V2, result.transport());
        assertEquals(Missing.PIN_STORE_UNUSABLE, result.missing());
        assertTrue(result.reason().contains("pin store is unusable"), result.reason());
        assertTrue(result.reason().contains("notify the player"), result.reason());
        assertTrue(result.reason().contains("12.15"), result.reason());
        // 12.15 wants the player told on every attempt, even one with no advertisement at all.
        TransportSelection.Result noAdvertisement = TransportSelection.select(
                TransportSelection.Inputs.builder().pinState(PinState.STORE_UNUSABLE)
                        .clientProtocolNumber(PROTOCOL).build());
        assertEquals(Transport.TCP, noAdvertisement.transport());
        assertEquals(Missing.PIN_STORE_UNUSABLE, noAdvertisement.missing());
        assertTrue(noAdvertisement.reason().contains("pin store is unusable"), noAdvertisement.reason());
    }

    @Test
    void aFailureNamesTheMissingTcpFallback() {
        TransportSelection.Result result = TransportSelection.select(TransportSelection.Inputs.builder()
                .setting(Mode.QUIC_ONLY).clientProtocolNumber(PROTOCOL).build());
        assertEquals(Transport.FAIL, result.transport());
        assertEquals(Missing.NO_ADVERTISEMENT, result.missing());
        assertTrue(result.transport() != Transport.TCP);
        assertTrue(result.reason().contains("quic-only has no TCP fallback"), result.reason());
    }

    @Test
    void resultsCompareByValue() {
        TransportSelection.Inputs inputs = TransportSelection.Inputs.builder()
                .advertisement(v3()).multistreamEnabled(true).exporterCapable(true)
                .clientProtocolNumber(PROTOCOL).localRoutingDigestHex(LOCAL).build();
        TransportSelection.Result first = TransportSelection.select(inputs);
        assertEquals(first, TransportSelection.select(inputs));
        assertEquals(first.hashCode(), TransportSelection.select(inputs).hashCode());
        assertEquals("V3: " + first.reason(), first.toString());
        assertNotEquals(first, TransportSelection.select(TransportSelection.Inputs.builder().build()));
    }

    @Test
    void rejectsAnInvalidLocalRoutingDigestEarly() {
        for (String bad : new String[] {"xyz", LOCAL.toUpperCase(Locale.ROOT), LOCAL.substring(1),
                LOCAL + "0", "sha256:" + LOCAL, hex('g'), ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TransportSelection.Inputs.builder()
                            .localRoutingDigestHex(bad).clientProtocolNumber(PROTOCOL).build(),
                    "digest: " + bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TransportSelection.Inputs.builder().localRoutingDigestHex(Optional.of("short")));
        // null and empty stay "no local table", and the comparison with the advertisement is exact.
        assertEquals(Optional.empty(), TransportSelection.Inputs.builder()
                .localRoutingDigestHex((String) null).build().localRoutingDigestHex());
        assertEquals(Optional.empty(), TransportSelection.Inputs.builder()
                .localRoutingDigestHex(Optional.<String>empty()).build().localRoutingDigestHex());
        assertEquals(Optional.of(LOCAL), TransportSelection.Inputs.builder()
                .localRoutingDigestHex(LOCAL).build().localRoutingDigestHex());
        assertEquals(Transport.V3, TransportSelection.select(TransportSelection.Inputs.builder()
                .advertisement(v3()).multistreamEnabled(true).exporterCapable(true)
                .clientProtocolNumber(PROTOCOL).localRoutingDigestHex(LOCAL).build()).transport());
        // One differing character is a mismatch (13.1), never a match.
        assertEquals(Transport.V2, TransportSelection.select(TransportSelection.Inputs.builder()
                .advertisement(v3()).multistreamEnabled(true).exporterCapable(true)
                .clientProtocolNumber(PROTOCOL).localRoutingDigestHex(OTHER).build()).transport());
    }

    /** An advertisement for a {@code quicraft/2} listener, with no routing member. */
    private static Advertisement v2() {
        return Advertisement.v1(25565, FP);
    }

    /** A {@code quicraft/2} listener that still advertises a routing member (13.1). */
    private static Advertisement v2WithRouting() {
        return Advertisement.of(Protocol.ALPN, 25565, FP, Collections.singletonMap(PROTOCOL, ADVERTISED_LOCAL));
    }

    /** A {@code quicraft/3} advertisement with a matching digest for {@link #PROTOCOL}. */
    private static Advertisement v3() {
        return v3WithDigestFor(PROTOCOL);
    }

    /** A {@code quicraft/3} advertisement with no routing member at all. */
    private static Advertisement v3WithoutRouting() {
        return Advertisement.of(Protocol.ALPN_V3, 25565, FP, Collections.<Integer, String>emptyMap());
    }

    /** A {@code quicraft/3} advertisement whose digest for {@code protocolNumber} is the local one. */
    private static Advertisement v3WithDigestFor(int protocolNumber) {
        return v3WithDigestFor(protocolNumber, LOCAL);
    }

    /** A {@code quicraft/3} advertisement whose digest for {@code protocolNumber} is {@code hexDigest}. */
    private static Advertisement v3WithDigestFor(int protocolNumber, String hexDigest) {
        Map<Integer, String> routing = new LinkedHashMap<>();
        routing.put(protocolNumber, "sha256:" + hexDigest);
        return Advertisement.of(Protocol.ALPN_V3, 25565, FP, routing);
    }

    /** 64 copies of {@code c}, the hex body of a digest. */
    private static String hex(char c) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static Row row(String name, Transport expected, Missing expectedMissing, String fragment) {
        return new Row(name, expected, expectedMissing, fragment);
    }

    /** One table row: the inputs, the transport and blocker they must select, and a reason fragment. */
    private static final class Row {
        private final String name;
        private final Transport expected;
        private final Missing expectedMissing;
        private final String fragment;
        private Mode setting = Mode.AUTO;
        private Advertisement advertisement;
        private PinState pin = PinState.NONE;
        private boolean flag;
        private boolean exporter;
        private String local;
        private boolean rebind;

        private Row(String name, Transport expected, Missing expectedMissing, String fragment) {
            this.name = name;
            this.expected = expected;
            this.expectedMissing = expectedMissing;
            this.fragment = fragment;
        }

        Row setting(Mode setting) {
            this.setting = setting;
            return this;
        }

        Row advertisement(Advertisement advertisement) {
            this.advertisement = advertisement;
            return this;
        }

        Row pin(PinState pin) {
            this.pin = pin;
            return this;
        }

        Row flag(boolean flag) {
            this.flag = flag;
            return this;
        }

        Row exporter(boolean exporter) {
            this.exporter = exporter;
            return this;
        }

        Row local(String local) {
            this.local = local;
            return this;
        }

        Row rebind(boolean rebind) {
            this.rebind = rebind;
            return this;
        }

        TransportSelection.Result result() {
            return TransportSelection.select(TransportSelection.Inputs.builder()
                    .setting(setting)
                    .multistreamEnabled(flag)
                    .exporterCapable(exporter)
                    .advertisement(advertisement)
                    .pinState(pin)
                    .rebindAllowed(rebind)
                    .clientProtocolNumber(PROTOCOL)
                    .localRoutingDigestHex(local)
                    .build());
        }
    }
}
