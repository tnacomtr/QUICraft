// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The binding exchange order and fail-closed rules of docs/protocol.md §12.9 and §12.19. Inputs are
 * the fixed values of {@link BindingTestData}.
 */
class BindingStateMachineTest {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] E = BindingTestData.hex(BindingTestData.E_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);
    private static final byte[] ZEROS = new byte[32];

    private static BindingStateMachine onlineClient() {
        return machine(BindingStateMachine.client(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
    }

    private static BindingStateMachine onlineServer() {
        return machine(BindingStateMachine.server(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
    }

    private static BindingStateMachine machine(BindingMachineResult result) {
        assertNotNull(result.machine(), "expected a machine but got " + result);
        return result.machine();
    }

    private static BindingMessage message(BindingKind kind, int mode, int protocol, byte[] tag) {
        return BindingMessage.of(kind, mode, protocol, F, R, tag);
    }

    @Test
    void fullHappyPathAgreesOnBothSides() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();

        BindingResult offer = server.start();
        assertTrue(offer.isSend());
        assertEquals(BindingKind.OFFER, offer.message().kind());
        assertArrayEquals(ZEROS, offer.message().tag());

        BindingResult clientProof = client.receive(offer.message().encode());
        assertTrue(clientProof.isSend());
        assertEquals(BindingKind.CLIENT_PROOF, clientProof.message().kind());

        BindingResult serverProof = server.receive(clientProof.message().encode());
        assertTrue(serverProof.isSend());
        assertEquals(BindingKind.SERVER_PROOF, serverProof.message().kind());

        BindingResult clientSwitch = client.receive(serverProof.message().encode());
        assertTrue(clientSwitch.isSend());
        assertEquals(BindingKind.CLIENT_SWITCH, clientSwitch.message().kind());

        BindingResult serverSwitch = server.receive(clientSwitch.message().encode());
        assertTrue(serverSwitch.isActive());
        assertNotNull(serverSwitch.message(), "server still has to write SERVER_SWITCH");
        assertEquals(BindingKind.SERVER_SWITCH, serverSwitch.message().kind());

        BindingResult done = client.receive(serverSwitch.message().encode());
        assertTrue(done.isActive());
        assertNull(done.message(), "client has nothing left to send");

        assertTrue(client.isActivated());
        assertTrue(server.isActivated());
        assertNull(client.expectedKind());
        // Both sides derived identical tags for the same kinds, so each accepted the other's.
        assertArrayEquals(serverSwitch.message().tag(), clientTag(BindingKind.SERVER_SWITCH));
    }

    /**
     * Regression: one {@link ExporterSource} may hand out the same array instance forever, because it
     * keeps ownership of it. Building a machine must therefore never wipe the source's array, or a
     * second machine derived from it would work with a zeroed exporter value.
     */
    @Test
    void oneExporterArrayInstanceCanBackTwoMachines() {
        byte[] shared = BindingTestData.hex(BindingTestData.E_HEX);
        byte[] expected = shared.clone();
        ExporterSource source = () -> shared;

        BindingStateMachine server = machine(BindingStateMachine.server(source, S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        BindingStateMachine client = machine(BindingStateMachine.client(source, S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));

        BindingResult offer = server.start();
        assertTrue(offer.isSend());
        BindingResult clientProof = client.receive(offer.message().encode());
        assertArrayEquals(clientTag(BindingKind.CLIENT_PROOF), clientProof.message().tag());
        BindingResult serverProof = server.receive(clientProof.message().encode());
        assertArrayEquals(clientTag(BindingKind.SERVER_PROOF), serverProof.message().tag());
        BindingResult clientSwitch = client.receive(serverProof.message().encode());
        assertArrayEquals(clientTag(BindingKind.CLIENT_SWITCH), clientSwitch.message().tag());
        BindingResult serverSwitch = server.receive(clientSwitch.message().encode());
        assertTrue(serverSwitch.isActive());
        assertArrayEquals(clientTag(BindingKind.SERVER_SWITCH), serverSwitch.message().tag());
        assertTrue(client.receive(serverSwitch.message().encode()).isActive());
        assertTrue(client.isActivated() && server.isActivated());

        assertArrayEquals(expected, shared, "the exporter source keeps ownership of its array");
    }

    @Test
    void buildingAMachineLeavesTheSharedFixtureExporterValueIntact() {
        onlineServer();
        onlineClient();
        assertArrayEquals(BindingTestData.hex(BindingTestData.E_HEX), E,
                "the shared fixture E must survive a machine build");
    }

    @Test
    void exporterFailureIsATypedFailureAndNotAnException() {
        ExporterSource throwing = () -> {
            throw new IllegalStateException("no exporter value");
        };
        BindingMachineResult thrown = BindingStateMachine.client(throwing, S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R);
        assertTrue(thrown.isFailed());
        assertEquals(BindingFailure.EXPORTER_ERROR, thrown.failure());
        assertNull(thrown.machine());

        BindingMachineResult absent = BindingStateMachine.client(() -> null, S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R);
        assertTrue(absent.isFailed());
        assertEquals(BindingFailure.EXPORTER_ERROR, absent.failure());

        BindingMachineResult tooShort = BindingStateMachine.server(BindingTestData.exporter(new byte[16]), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R);
        assertTrue(tooShort.isFailed());
        assertEquals(BindingFailure.EXPORTER_ERROR, tooShort.failure());

        // Offline mode has no S, but the exchange still needs the exporter value (§12.3, §12.19).
        BindingMachineResult offline = BindingStateMachine.client(() -> null, null,
                BindingTestData.MODE_OFFLINE, BindingTestData.PROTOCOL_NUMBER, F, R);
        assertEquals(BindingFailure.EXPORTER_ERROR, offline.failure());
    }

    @Test
    void aSuccessfulBuildCarriesOnlyTheMachine() {
        BindingMachineResult result = BindingStateMachine.client(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R);
        assertTrue(result.isOk());
        assertFalse(result.isFailed());
        assertNotNull(result.machine());
        assertNull(result.failure());
    }

    @Test
    void abortWipesTagsSettlesAndIsIdempotent() throws Exception {
        BindingStateMachine server = onlineServer();
        assertFalse(server.isSettled());
        byte[][] before = tagsOf(server).clone();
        int nonZero = 0;
        for (byte[] tag : before) {
            for (byte b : tag) {
                nonZero |= b;
            }
        }
        assertTrue(nonZero != 0, "an online machine must hold nonzero tags before abort");
        server.abort();
        server.abort();
        for (int i = 0; i < before.length; i++) {
            if (before[i] != null) {
                for (byte b : before[i]) {
                    assertEquals(0, b, "precomputed tag " + i + " must be zeroed, not just dropped");
                }
            }
        }

        assertTrue(server.isSettled());
        assertFalse(server.isActivated());
        assertNull(server.expectedKind());
        byte[][] tags = tagsOf(server);
        assertEquals(5, tags.length);
        for (int i = 0; i < tags.length; i++) {
            assertNull(tags[i], "precomputed tag " + i + " must be wiped");
        }

        // Every later call that would use a tag fails.
        assertThrows(IllegalStateException.class, server::start);
        assertEquals(BindingFailure.ALREADY_SETTLED, server.receive(new byte[103]).failure());
    }

    @Test
    void abortMidExchangeStopsTheRemainingMessages() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        BindingResult offer = server.start();
        assertTrue(client.receive(offer.message().encode()).isSend());

        client.abort();
        assertNull(client.expectedKind());
        assertEquals(BindingFailure.ALREADY_SETTLED,
                client.receive(offer.message().encode()).failure());
        assertFalse(client.isActivated());
        assertFalse(server.isSettled(), "abort is local to one endpoint");
    }

    @Test
    void abortAfterSettlementChangesNothing() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        BindingResult cp = client.receive(server.start().message().encode());
        BindingResult sp = server.receive(cp.message().encode());
        BindingResult cs = client.receive(sp.message().encode());
        BindingResult ss = server.receive(cs.message().encode());
        assertTrue(server.isActivated());
        server.abort();
        assertTrue(server.isSettled());
        assertTrue(server.isActivated());
        assertEquals(BindingFailure.ALREADY_SETTLED, server.receive(ss.message().encode()).failure());
        assertTrue(client.receive(ss.message().encode()).isActive());
    }

    @Test
    void wrongExporterValueFails() {
        BindingStateMachine server = machine(BindingStateMachine.server(
                BindingTestData.exporter(BindingTestData.hex(BindingTestData.E_HEX)), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        BindingStateMachine client = machine(BindingStateMachine.client(
                BindingTestData.exporter(new byte[32]), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));

        BindingResult offer = server.start();
        BindingResult clientProof = client.receive(offer.message().encode());
        assertTrue(clientProof.isSend());
        assertEquals(BindingFailure.BAD_TAG, server.receive(clientProof.message().encode()).failure());
    }

    @Test
    void wrongMinecraftSecretFails() {
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E),
                BindingTestData.hex("ffffffffffffffffffffffffffffffff"),
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        BindingStateMachine client = onlineClient();

        BindingResult clientProof = client.receive(server.start().message().encode());
        assertEquals(BindingFailure.BAD_TAG, server.receive(clientProof.message().encode()).failure());
    }

    @Test
    void wrongFingerprintFails() {
        byte[] otherF = new byte[32];
        Arrays.fill(otherF, (byte) 7);
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, otherF, R));
        BindingStateMachine client = onlineClient();

        assertEquals(BindingFailure.WRONG_FINGERPRINT,
                client.receive(server.start().message().encode()).failure());
    }

    @Test
    void wrongRoutingDigestFails() {
        byte[] otherR = new byte[32];
        Arrays.fill(otherR, (byte) 9);
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, otherR));
        BindingStateMachine client = onlineClient();

        assertEquals(BindingFailure.WRONG_ROUTING,
                client.receive(server.start().message().encode()).failure());
    }

    @Test
    void wrongModeFails() {
        BindingStateMachine offlineServer = machine(BindingStateMachine.server(BindingTestData.exporter(E), null,
                BindingTestData.MODE_OFFLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        BindingStateMachine onlineClient = onlineClient();

        assertEquals(BindingFailure.WRONG_MODE,
                onlineClient.receive(offlineServer.start().message().encode()).failure());
    }

    @Test
    void wrongProtocolNumberFails() {
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E), S,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER + 1, F, R));
        BindingStateMachine client = onlineClient();

        assertEquals(BindingFailure.WRONG_PROTOCOL,
                client.receive(server.start().message().encode()).failure());
    }

    @Test
    void unsolicitedClientKindOnClientIsWrongDirection() throws Exception {
        BindingStateMachine client = onlineClient();
        byte[] ownProof = message(BindingKind.CLIENT_PROOF, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, ZEROS).encode();
        assertEquals(BindingFailure.WRONG_DIRECTION, client.receive(ownProof).failure());
    }

    @Test
    void unsolicitedOfferOnServerIsWrongDirection() throws Exception {
        BindingStateMachine server = onlineServer();
        byte[] offer = message(BindingKind.OFFER, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, ZEROS).encode();
        assertEquals(BindingFailure.WRONG_DIRECTION, server.receive(offer).failure());
    }

    @Test
    void clientTagReplayedAsServerTagFails() throws Exception {
        BindingStateMachine client = onlineClient();
        assertTrue(client.receive(onlineServer().start().message().encode()).isSend());
        // A SERVER_PROOF carrying the CLIENT_PROOF tag: kind separation makes the MAC differ.
        byte[] replayed = message(BindingKind.SERVER_PROOF, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, clientTag(BindingKind.CLIENT_PROOF)).encode();
        assertEquals(BindingFailure.BAD_TAG, client.receive(replayed).failure());
    }

    @Test
    void wrongOrderFails() throws Exception {
        BindingStateMachine server = onlineServer();
        byte[] clientSwitch = message(BindingKind.CLIENT_SWITCH, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, ZEROS).encode();
        assertEquals(BindingFailure.WRONG_ORDER, server.receive(clientSwitch).failure());
    }

    @Test
    void duplicateFails() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        BindingResult clientProof = client.receive(server.start().message().encode());
        assertTrue(server.receive(clientProof.message().encode()).isSend());
        assertEquals(BindingFailure.DUPLICATE,
                server.receive(clientProof.message().encode()).failure());
    }

    @Test
    void duplicateOfferFails() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        BindingResult offer = server.start();
        assertTrue(client.receive(offer.message().encode()).isSend());
        assertEquals(BindingFailure.DUPLICATE, client.receive(offer.message().encode()).failure());
    }

    @Test
    void tamperedTagFails() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        byte[] clientProof = client.receive(server.start().message().encode()).message().encode();
        clientProof[102] ^= 0x01;
        assertEquals(BindingFailure.BAD_TAG, server.receive(clientProof).failure());
    }

    @Test
    void messageAfterSettlementFails() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        BindingResult cp = client.receive(server.start().message().encode());
        BindingResult sp = server.receive(cp.message().encode());
        BindingResult cs = client.receive(sp.message().encode());
        BindingResult ss = server.receive(cs.message().encode());
        assertTrue(client.receive(ss.message().encode()).isActive());
        assertEquals(BindingFailure.ALREADY_SETTLED,
                client.receive(message(BindingKind.SERVER_SWITCH, BindingTestData.MODE_ONLINE,
                        BindingTestData.PROTOCOL_NUMBER, ZEROS).encode()).failure());
    }

    @Test
    void failureIsTerminalAndSticky() {
        BindingStateMachine server = onlineServer();
        BindingStateMachine client = onlineClient();
        byte[] clientProof = client.receive(server.start().message().encode()).message().encode();
        assertEquals(BindingFailure.BAD_TAG, server.receive(tamper(clientProof)).failure());
        assertEquals(BindingFailure.BAD_TAG, server.receive(clientProof).failure());
    }

    @Test
    void offlineModeUsesZeroTagsAndCompletes() {
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E), null,
                BindingTestData.MODE_OFFLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        BindingStateMachine client = machine(BindingStateMachine.client(BindingTestData.exporter(E), null,
                BindingTestData.MODE_OFFLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertOfflineHappyPath(server, client);
    }

    @Test
    void offlineModeRejectsNonZeroTag() throws Exception {
        BindingStateMachine server = machine(BindingStateMachine.server(BindingTestData.exporter(E), null,
                BindingTestData.MODE_OFFLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertTrue(server.start().isSend());
        byte[] clientProof = message(BindingKind.CLIENT_PROOF, BindingTestData.MODE_OFFLINE,
                BindingTestData.PROTOCOL_NUMBER, BindingTags.zeros()).encode();
        clientProof[71] = 1;
        assertEquals(BindingFailure.BAD_TAG, server.receive(clientProof).failure());
    }

    @Test
    void serverRejectsClientProofBeforeStart() throws Exception {
        BindingStateMachine server = onlineServer();
        byte[] clientProof = message(BindingKind.CLIENT_PROOF, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, ZEROS).encode();
        assertEquals(BindingFailure.WRONG_ORDER, server.receive(clientProof).failure());
    }

    @Test
    void clientNeverStarts() {
        assertThrows(UnsupportedOperationException.class, () -> onlineClient().start());
    }

    @Test
    void serverCannotStartTwice() {
        BindingStateMachine server = onlineServer();
        assertTrue(server.start().isSend());
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    void missingExporterSourceIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> BindingStateMachine.client(
                null, S, BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
    }

    @Test
    void onlineModeRequiresA16ByteSecret() {
        assertThrows(IllegalArgumentException.class, () -> BindingStateMachine.client(
                BindingTestData.exporter(E), null,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
        assertThrows(IllegalArgumentException.class, () -> BindingStateMachine.client(
                BindingTestData.exporter(E), new byte[8],
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R));
    }

    @Test
    void invalidModeIsRejectedBeforeTheExporterIsTouched() {
        assertThrows(IllegalArgumentException.class, () -> BindingStateMachine.client(
                BindingTestData.exporter(E), S,
                2, BindingTestData.PROTOCOL_NUMBER, F, R));
    }

    private static void assertOfflineHappyPath(BindingStateMachine server, BindingStateMachine client) {
        BindingResult offer = server.start();
        assertArrayEquals(ZEROS, offer.message().tag(), "OFFER tag is always zeros");
        BindingResult cp = client.receive(offer.message().encode());
        assertArrayEquals(ZEROS, cp.message().tag());
        BindingResult sp = server.receive(cp.message().encode());
        assertArrayEquals(ZEROS, sp.message().tag());
        BindingResult cs = client.receive(sp.message().encode());
        assertArrayEquals(ZEROS, cs.message().tag());
        BindingResult ss = server.receive(cs.message().encode());
        assertArrayEquals(ZEROS, ss.message().tag());
        assertTrue(client.receive(ss.message().encode()).isActive());
        assertTrue(client.isActivated() && server.isActivated());
    }

    private static byte[] clientTag(BindingKind kind) {
        byte[] key = BindingKeys.derive(S);
        byte[] transcript = BindingTranscript.compute(BindingTestData.PROTOCOL_NUMBER,
                BindingTestData.MODE_ONLINE, F, R);
        return BindingTags.tag(key, kind, transcript, E);
    }

    /** Reads the private precomputed tags so a wipe can be asserted, not assumed. */
    private static byte[][] tagsOf(BindingStateMachine machine) throws Exception {
        Field field = BindingStateMachine.class.getDeclaredField("tags");
        field.setAccessible(true);
        return (byte[][]) field.get(machine);
    }

    private static byte[] tamper(byte[] payload) {
        byte[] copy = payload.clone();
        copy[102] ^= 0x01;
        return copy;
    }
}
