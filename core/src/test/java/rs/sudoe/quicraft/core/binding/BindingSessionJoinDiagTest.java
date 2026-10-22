// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingStateMachine.Role;
import rs.sudoe.quicraft.core.multistream.JoinDiag;
import rs.sudoe.quicraft.core.multistream.JoinDiags;

/**
 * The join marks a real five-message binding writes: each side records exactly its own messages, in
 * wire order, and a diagnostics sink that throws changes nothing about the exchange (12.9 to 12.12).
 */
class BindingSessionJoinDiagTest {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] E = BindingTestData.hex(BindingTestData.E_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);
    private static final long ENTERED = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    @Test
    void eachSideMarksItsOwnMessagesInWireOrder() {
        List<String> clientLines = new ArrayList<String>();
        List<String> serverLines = new ArrayList<String>();
        AtomicLong clientClock = new AtomicLong();
        AtomicLong serverClock = new AtomicLong();
        JoinDiag clientDiag = JoinDiags.forTest(JoinDiag.Side.CLIENT,
                () -> clientClock.getAndAdd(MILLI), clientLines::add);
        JoinDiag serverDiag = JoinDiags.forTest(JoinDiag.Side.SERVER,
                () -> serverClock.getAndAdd(MILLI), serverLines::add);

        Rig rig = new Rig(clientDiag, serverDiag);
        rig.run();
        JoinDiag.finish(clientDiag);
        JoinDiag.finish(serverDiag);

        assertEquals(1, clientLines.size(), "one line per connection and side");
        assertEquals("QUICraft v3 join phases (client): OFFER received=0 CLIENT_PROOF sent=1"
                + " SERVER_PROOF received=2 CLIENT_SWITCH sent=3 SERVER_SWITCH received=4",
                clientLines.get(0));
        assertEquals(1, serverLines.size(), "one line per connection and side");
        assertEquals("QUICraft v3 join phases (server): OFFER sent=0 CLIENT_PROOF received=1"
                + " SERVER_PROOF sent=2 CLIENT_SWITCH received=3 SERVER_SWITCH sent=4",
                serverLines.get(0));
    }

    @Test
    void aThrowingSinkLeavesTheExchangeActivating() {
        JoinDiag clientDiag = JoinDiags.forTest(JoinDiag.Side.CLIENT, System::nanoTime, line -> {
            throw new IllegalStateException("sink");
        });
        JoinDiag serverDiag = JoinDiags.forTest(JoinDiag.Side.SERVER, System::nanoTime, line -> {
            throw new IllegalStateException("sink");
        });

        Rig rig = new Rig(clientDiag, serverDiag);
        rig.run();

        assertTrue(rig.client.isActivated(), "a throwing diagnostics sink cannot fail the binding");
        assertTrue(rig.server.isActivated(), "a throwing diagnostics sink cannot fail the binding");
        JoinDiag.finish(clientDiag);
        JoinDiag.finish(serverDiag);
    }

    /** A client and a server session of 12.9 to 12.12, wired to each other without any I/O. */
    private static final class Rig {
        final Collector clientCollector = new Collector();
        final Collector serverCollector = new Collector();
        final BindingSession client;
        final BindingSession server;

        Rig(JoinDiag clientDiag, JoinDiag serverDiag) {
            clientCollector.peer = serverCollector;
            serverCollector.peer = clientCollector;
            client = new BindingSession(Role.CLIENT, BindingTestData.exporter(E), S,
                    BindingStateMachine.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, 1, F, R,
                    () -> ENTERED, 0, clientCollector, null, null, clientDiag);
            server = new BindingSession(Role.SERVER, BindingTestData.exporter(E), S,
                    BindingStateMachine.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, 1, F, R,
                    () -> ENTERED, 0, serverCollector, null, null, serverDiag);
        }

        void run() {
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
                return;
            }
        }
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
