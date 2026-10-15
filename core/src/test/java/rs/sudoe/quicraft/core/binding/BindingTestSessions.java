// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.util.ArrayDeque;
import java.util.Deque;
import rs.sudoe.quicraft.core.binding.BindingStateMachine.Role;

/**
 * Binding sessions for the tests of the units that consume one, driven through the real five-message
 * exchange of 12.9 against a real peer. {@link BindingSession#isActivated()} is therefore the same
 * fact the adapter sees, which is what 12.15's pin write is tied to.
 */
public final class BindingTestSessions {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] E = BindingTestData.hex(BindingTestData.E_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);
    private static final long ENTERED = 1_000_000_000L;

    private BindingTestSessions() {}

    /** A client session in {@code mode} that completed the exchange, so it reports activation. */
    public static BindingSession activatedClient(int mode) {
        return pair(mode)[0];
    }

    /** A client session in {@code mode} that never entered configuration, so it is not activated. */
    public static BindingSession idleClient(int mode) {
        return new BindingSession(Role.CLIENT, BindingTestData.exporter(E), secret(mode), mode,
                BindingTestData.PROTOCOL_NUMBER, F, R, () -> ENTERED, 0, new Collector());
    }

    private static byte[] secret(int mode) {
        return mode == BindingStateMachine.MODE_ONLINE ? S : null;
    }

    /** A client and a server session that ran the whole exchange to activation, client first. */
    private static BindingSession[] pair(int mode) {
        Collector clientCollector = new Collector();
        Collector serverCollector = new Collector();
        clientCollector.peer = serverCollector;
        serverCollector.peer = clientCollector;
        BindingSession client = new BindingSession(Role.CLIENT, BindingTestData.exporter(E), secret(mode),
                mode, BindingTestData.PROTOCOL_NUMBER, F, R, () -> ENTERED, 0, clientCollector);
        BindingSession server = new BindingSession(Role.SERVER, BindingTestData.exporter(E), secret(mode),
                mode, BindingTestData.PROTOCOL_NUMBER, F, R, () -> ENTERED, 0, serverCollector);
        client.onConfigurationEntered(ENTERED);
        server.onConfigurationEntered(ENTERED);
        for (int guard = 0; guard < 64; guard++) {
            if (!clientCollector.inbound.isEmpty()) {
                client.onPayload(clientCollector.inbound.poll());
                continue;
            }
            if (!serverCollector.inbound.isEmpty()) {
                server.onPayload(serverCollector.inbound.poll());
                continue;
            }
            break;
        }
        if (!client.isActivated() || !server.isActivated()) {
            throw new IllegalStateException("the test binding exchange did not activate");
        }
        return new BindingSession[] {client, server};
    }

    /** Passes payloads to the peer, and records whether the session failed. */
    private static final class Collector implements BindingDriver.Callbacks {
        final Deque<byte[]> inbound = new ArrayDeque<byte[]>();
        Collector peer;

        @Override
        public void send(byte[] payload) {
            if (peer != null) {
                peer.inbound.add(payload);
            }
        }

        @Override
        public void activateOutbound() {}

        @Override
        public void activateInbound() {}

        @Override
        public void fail(int code, String reason) {}
    }
}
