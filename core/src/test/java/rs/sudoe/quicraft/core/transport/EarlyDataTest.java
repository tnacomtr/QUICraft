// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** 0-RTT joins (docs/protocol.md §8). */
class EarlyDataTest {
    /** Two VarInt frames, like a handshake and Login Start. */
    static final byte[] FLIGHT = {3, 0, 1, 2, 2, 0, 7};
    static final byte[] OTHER_FLIGHT = {3, 0, 1, 2, 2, 0, 8};
    static final byte[] AFTER = {5, 0, 9, 9, 9, 9};

    /** A handshake (contents arbitrary) and a Login Start for "Steve" with a UUID. */
    static byte[] loginFlight(String name) {
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(4);
        out.write(new byte[] {0, 1, 2, 3}, 0, 4);
        int loginLength = 1 + 1 + n.length + 16;
        out.write(loginLength);
        out.write(0);
        out.write(n.length);
        out.write(n, 0, n.length);
        out.write(new byte[16], 0, 16);
        return out.toByteArray();
    }

    @Test
    void readsThePlayerNameFromAFirstFlight() {
        assertEquals("Steve", FirstFlight.playerName(loginFlight("Steve")));
        assertEquals(null, FirstFlight.playerName(FLIGHT));
        assertEquals(null, FirstFlight.playerName(new byte[] {9}));
    }

    @Test
    void aTcpLoginClosesTheUnconfirmedEarlyConnectionForItsName() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        byte[] flight = loginFlight("Steve");
        try (GameServer game = new GameServer(identity); DelayRelay relay = new DelayRelay(game.address(), 0)) {
            Join.run(relay, identity, "inputs", flight).closeAfterTicket(relay);
            relay.dropFromServer(true); // the client never confirms: an abandoned attempt
            EarlyFlight early = new EarlyFlight("inputs");
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(relay.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT, early);
            GameServer.Accepted ghost = game.awaitAccepted(1, 5, TimeUnit.SECONDS);
            game.received(1, flight.length); // the server has read the Login Start
            InetSocketAddress tcpLogin = new InetSocketAddress(ghost.stream.remoteAddress().getAddress(), 1);

            assertEquals(0, game.server.closeUnconfirmedEarly(tcpLogin, "alex").get(1, TimeUnit.SECONDS));
            assertEquals(0, game.server.closeUnconfirmedEarly(ghost.stream.remoteAddress(), "Steve")
                    .get(1, TimeUnit.SECONDS), "a connection's own login never closes it");
            assertFalse(ghost.closed.await(200, TimeUnit.MILLISECONDS), "another name's login stays");
            long start = System.nanoTime();
            assertEquals(1, game.server.closeUnconfirmedEarly(tcpLogin, "STEVE").get(1, TimeUnit.SECONDS));
            assertTrue(ghost.closed.await(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "closed at once");
            attempt.cancel(false);
        }
    }


    @BeforeAll
    static void native_() {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
    }

    @AfterEach
    void reset() {
        Faults.set();
        QuicServer.confirmMillis = 3_000;
    }

    @Test
    void rejoinSendsItsFirstFlightAsZeroRttAndGetsTheAnswerOneRoundTripSooner() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity); DelayRelay relay = new DelayRelay(game.address(), 100)) {
            Join first = Join.run(relay, identity, "inputs", FLIGHT);
            assertFalse(first.early.sent(), "a first join has nothing to send early");
            first.closeAfterTicket(relay);

            Join second = Join.run(relay, identity, "inputs", FLIGHT);
            assertTrue(second.early.sent(), "the rejoin must send its first flight as 0-RTT data");
            double firstRtt = first.replyRtt(relay);
            double secondRtt = second.replyRtt(relay);
            System.out.printf("first reply: full %.2f RTT, 0-RTT %.2f RTT after the first datagram%n", firstRtt,
                    secondRtt);
            assertTrue(firstRtt > 1.8, "without 0-RTT the reply takes ~2 RTT, was " + firstRtt);
            assertTrue(secondRtt < 1.5, "with 0-RTT the reply takes ~1 RTT, was " + secondRtt);

            // The server got the flight once, the game's own copy was dropped.
            second.stream.write(ByteBuffer.wrap(AFTER));
            second.stream.flush();
            assertArrayEquals(concat(FLIGHT, AFTER), second.collector.await(FLIGHT.length + AFTER.length, 5,
                    TimeUnit.SECONDS));
            assertArrayEquals(concat(FLIGHT, AFTER), game.received(1, FLIGHT.length + AFTER.length));
            second.stream.close();
        }
    }

    @Test
    void aReplayedFlightNeverReachesTheGame() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity); DelayRelay relay = new DelayRelay(game.address(), 0)) {
            Join.run(relay, identity, "inputs", FLIGHT).closeAfterTicket(relay);
            relay.capture(true);
            Join second = Join.run(relay, identity, "inputs", FLIGHT);
            assertTrue(second.early.sent());
            relay.capture(false);
            second.stream.close();
            Thread.sleep(1_000); // the server forgets the original connection
            int before = game.accepted.size();
            relay.replay();
            Thread.sleep(1_000);
            assertEquals(before, game.accepted.size(), "a replay's token was used: it must never reach the game");
        }
    }

    @Test
    void aCopyThatBeatsTheOriginalIsClosedWhenItNeverConfirms() throws Exception {
        QuicServer.confirmMillis = 500;
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity); DelayRelay relay = new DelayRelay(game.address(), 0)) {
            Join.run(relay, identity, "inputs", FLIGHT).closeAfterTicket(relay);
            relay.copyFirst(true); // the server only ever sees the copies, from another address
            EarlyFlight early = new EarlyFlight("inputs");
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(relay.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT, early);
            GameServer.Accepted copy = game.awaitAccepted(1, 5, TimeUnit.SECONDS);
            assertTrue(early.sent());
            long passedAt = System.nanoTime();
            assertTrue(copy.closed.await(3, TimeUnit.SECONDS), "an unconfirmed early connection must be closed");
            long closedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - passedAt);
            assertTrue(closedAfter >= 300 && closedAfter < 1_500, "closed after " + closedAfter + " ms");
            attempt.cancel(false);
        }
    }

    @Test
    void aRejoinFromAnotherAddressStillUsesZeroRtt() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity)) {
            Join.run(game.address(), identity, "inputs", FLIGHT, null).closeAfterTicket(null);
            InetSocketAddress otherAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0);
            Join second = Join.run(game.address(), identity, "inputs", FLIGHT, otherAddress);
            assertTrue(second.early.sent(), "tokens and tickets are not bound to the client's address");
            assertEquals(InetAddress.getByName("127.0.0.2"), game.accepted.get(1).stream.remoteAddress().getAddress());
            second.stream.close();
        }
    }

    @Test
    void aDifferentFirstFlightClosesTheConnectionAndForgetsTheServer() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity)) {
            Join.run(game.address(), identity, "inputs", FLIGHT, null).closeAfterTicket(null);

            EarlyFlight early = new EarlyFlight("inputs");
            ClientStream stream = (ClientStream) QuicClient.connect(game.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT, early).get(5, TimeUnit.SECONDS);
            assertTrue(early.sent());
            CountDownLatch mismatch = new CountDownLatch(1);
            stream.onFirstFlightMismatch(mismatch::countDown);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            stream.write(ByteBuffer.wrap(OTHER_FLIGHT));
            stream.flush();
            assertTrue(mismatch.await(5, TimeUnit.SECONDS), "the platform must hear of the mismatch");
            assertTrue(collector.closed.await(5, TimeUnit.SECONDS), "the game must see the stream close");
            assertEquals(0, collector.await(1, 200, TimeUnit.MILLISECONDS).length,
                    "the server's answer to the other flight must never reach the game");
            assertTrue(game.accepted.get(1).closed.await(5, TimeUnit.SECONDS), "the server's side must close");

            // Forgotten: the next join sends nothing early and records the new flight.
            Join third = Join.run(game.address(), identity, "inputs", OTHER_FLIGHT, null);
            assertFalse(third.early.sent());
            third.closeAfterTicket(null);
            Join fourth = Join.run(game.address(), identity, "inputs", OTHER_FLIGHT, null);
            assertTrue(fourth.early.sent());
            fourth.stream.close();
        }
    }

    @Test
    void zeroRttRejectedByARestartedServerStillArrivesOnce() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        InetSocketAddress address;
        try (GameServer game = new GameServer(identity)) {
            address = game.address();
            Join.run(address, identity, "inputs", FLIGHT, null).closeAfterTicket(null);
        }
        try (GameServer game = new GameServer(identity, address)) {
            Join second = Join.run(address, identity, "inputs", FLIGHT, null);
            assertTrue(second.early.sent());
            second.stream.write(ByteBuffer.wrap(AFTER));
            second.stream.flush();
            assertArrayEquals(concat(FLIGHT, AFTER), second.collector.await(FLIGHT.length + AFTER.length, 5,
                    TimeUnit.SECONDS));
            assertArrayEquals(concat(FLIGHT, AFTER), game.received(0, FLIGHT.length + AFTER.length));
            second.stream.close();
        }
    }

    @Test
    void anAttemptAbandonedAfterZeroRttEndsTheServersLoginByTheDeadline() throws Exception {
        QuicServer.confirmMillis = 500;
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity); DelayRelay relay = new DelayRelay(game.address(), 100)) {
            Join.run(relay, identity, "inputs", FLIGHT).closeAfterTicket(relay);
            EarlyFlight early = new EarlyFlight("inputs");
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(relay.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT, early);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!early.sent() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(early.sent());
            // TCP won before the client heard from the server: quiche closes without a
            // CONNECTION_CLOSE, so the server's deadline has to end the login it started.
            attempt.cancel(false);
            GameServer.Accepted accepted = game.awaitAccepted(1, 2, TimeUnit.SECONDS);
            assertTrue(accepted.closed.await(2, TimeUnit.SECONDS), "the deadline must close it");
        }
    }

    @Test
    void eachEarlyStageFailingLeavesAWorkingJoin() throws Exception {
        for (String stage : new String[] {Faults.EARLY_SEND, Faults.EARLY_RECORD, Faults.EARLY_TOKEN_ISSUE,
                Faults.EARLY_TOKEN_REDEEM}) {
            ServerIdentity identity = ServerIdentity.generate();
            try (GameServer game = new GameServer(identity)) {
                Faults.set(stage);
                Join.run(game.address(), identity, "inputs", FLIGHT, null).closeAfterTicket(null);
                Join second = Join.run(game.address(), identity, "inputs", FLIGHT, null);
                second.stream.write(ByteBuffer.wrap(AFTER));
                second.stream.flush();
                assertArrayEquals(concat(FLIGHT, AFTER), second.collector.await(FLIGHT.length + AFTER.length, 5,
                        TimeUnit.SECONDS), stage);
                second.stream.close();
            } finally {
                Faults.set();
            }
        }
    }

    @Test
    void aFailingComparisonIsTreatedAsAMismatch() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (GameServer game = new GameServer(identity)) {
            Join.run(game.address(), identity, "inputs", FLIGHT, null).closeAfterTicket(null);
            EarlyFlight early = new EarlyFlight("inputs");
            ClientStream stream = (ClientStream) QuicClient.connect(game.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT, early).get(5, TimeUnit.SECONDS);
            assertTrue(early.sent());
            CountDownLatch mismatch = new CountDownLatch(1);
            stream.onFirstFlightMismatch(mismatch::countDown);
            Faults.set(Faults.EARLY_COMPARE);
            stream.write(ByteBuffer.wrap(FLIGHT));
            assertTrue(mismatch.await(5, TimeUnit.SECONDS));
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A client join: connect, write the first flight, wait for the server's echo of it. */
    static final class Join {
        final EarlyFlight early;
        final QuicByteStream stream;
        final Loopback.Collector collector;
        final long replyNanos;
        final long firstDatagramNanos;

        private Join(EarlyFlight early, QuicByteStream stream, Loopback.Collector collector, long replyNanos,
                long firstDatagramNanos) {
            this.early = early;
            this.stream = stream;
            this.collector = collector;
            this.replyNanos = replyNanos;
            this.firstDatagramNanos = firstDatagramNanos;
        }

        static Join run(DelayRelay relay, ServerIdentity identity, String inputs, byte[] flight) throws Exception {
            relay.reset();
            Join j = run(relay.address(), identity, inputs, flight, null);
            return new Join(j.early, j.stream, j.collector, j.replyNanos, relay.firstClientDatagramNanos());
        }

        static Join run(InetSocketAddress target, ServerIdentity identity, String inputs, byte[] flight,
                InetSocketAddress local) throws Exception {
            EarlyFlight early = new EarlyFlight(inputs);
            QuicByteStream stream = (local == null
                    ? QuicClient.connect(target, identity.fingerprint(), TransportConfig.DEFAULT, early)
                    : QuicClient.connect(local, target, identity.fingerprint(), TransportConfig.DEFAULT, early))
                    .get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            stream.write(ByteBuffer.wrap(flight));
            stream.flush();
            assertArrayEquals(flight, collector.await(flight.length, 5, TimeUnit.SECONDS));
            return new Join(early, stream, collector, collector.firstDataNanos(), 0);
        }

        double replyRtt(DelayRelay relay) {
            return (replyNanos - firstDatagramNanos) / 1e6 / relay.rttMillis();
        }

        /** Stays connected until the session ticket and the early token are in, then closes. */
        void closeAfterTicket(DelayRelay relay) throws InterruptedException {
            Thread.sleep(relay == null ? 200 : 3 * relay.rttMillis());
            stream.close();
            Thread.sleep(relay == null ? 100 : 2 * relay.rttMillis());
        }
    }

    /** A QUIC listener whose "game" echoes everything and keeps what it received per stream. */
    static final class GameServer implements AutoCloseable {
        static final class Accepted {
            final QuicByteStream stream;
            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            final CountDownLatch closed = new CountDownLatch(1);

            Accepted(QuicByteStream stream) {
                this.stream = stream;
            }
        }

        final List<Accepted> accepted = new CopyOnWriteArrayList<>();
        final QuicServer server;

        GameServer(ServerIdentity identity) throws Exception {
            this(identity, Loopback.anyLocal());
        }

        GameServer(ServerIdentity identity, InetSocketAddress address) throws Exception {
            server = QuicServer.bind(address, identity, TransportConfig.DEFAULT, stream -> {
                Accepted a = new Accepted(stream);
                accepted.add(a);
                synchronized (accepted) {
                    accepted.notifyAll();
                }
                stream.setListener(new QuicByteStream.Listener() {
                    @Override
                    public void onData(ByteBuffer data) {
                        byte[] copy = new byte[data.remaining()];
                        data.duplicate().get(copy);
                        synchronized (a.received) {
                            a.received.write(copy, 0, copy.length);
                        }
                        stream.write(data);
                        stream.flush();
                    }

                    @Override
                    public void onWritabilityChanged(boolean writable) {}

                    @Override
                    public void onClosed(Throwable cause) {
                        a.closed.countDown();
                    }
                });
                stream.setAutoRead(true);
            });
        }

        InetSocketAddress address() {
            return server.localAddress();
        }

        Accepted awaitAccepted(int index, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (accepted) {
                while (accepted.size() <= index) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new AssertionError("no stream #" + index + " accepted");
                    }
                    TimeUnit.NANOSECONDS.timedWait(accepted, left);
                }
            }
            return accepted.get(index);
        }

        byte[] received(int index, int atLeast) throws InterruptedException {
            Accepted a = awaitAccepted(index, 5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                synchronized (a.received) {
                    if (a.received.size() >= atLeast) {
                        break;
                    }
                }
                Thread.sleep(5);
            }
            synchronized (a.received) {
                return a.received.toByteArray();
            }
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
