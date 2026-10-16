// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingDriver;

/**
 * The {@code quicraft:binding} payload glue of docs/protocol.md 12.8: callbacks reach the platform
 * through its own payload sender, received payload bytes reach the driver, and on a connection that
 * is not {@code /3} a payload does nothing but leave a debug line.
 */
class BindingPayloadsTest {

    @Test
    void callbacksReachThePlatformAndThePayloadSender() {
        Platform platform = new Platform();
        Sender sender = new Sender();
        FakeDriver driver = new FakeDriver();
        BindingPayloads payloads = new BindingPayloads(driver, platform, sender);

        payloads.send(new byte[] {1, 2});
        payloads.activateOutbound();
        payloads.activateInbound();
        payloads.fail(0x10, "binding failed");

        assertTrue(payloads.isV3());
        assertArrayEquals(new byte[] {1, 2}, sender.sent.get(0));
        assertTrue(platform.outbound, "12.11/12.12: the outbound cutover is the platform's to run");
        assertTrue(platform.inbound);
        assertEquals("0x10:binding failed", platform.failed);
    }

    @Test
    void receivedPayloadsGoToTheDriver() {
        FakeDriver driver = new FakeDriver();
        BindingPayloads payloads = new BindingPayloads(driver, new Platform(), new Sender());

        payloads.onPayload(new byte[] {9, 8, 7});

        assertEquals(1, driver.payloads.size());
        assertArrayEquals(new byte[] {9, 8, 7}, driver.payloads.get(0));
    }

    @Test
    void theExchangeStateAlsoReachesTheDriver() {
        FakeDriver driver = new FakeDriver();
        BindingPayloads payloads = new BindingPayloads(driver, new Platform(), new Sender());

        payloads.onConfigurationEntered(1234L);
        payloads.tick(5678L);
        assertEquals(BindingDriver.QueueResult.QUEUED,
                payloads.queueOrdinary("packet", 7), "12.10: the driver queues ordinary packets");
        assertEquals(Arrays.asList("packet:7"), payloads.releaseQueued());

        assertEquals(1234L, driver.configurationEnteredAt);
        assertEquals(5678L, driver.tickedAt);
        assertEquals("packet:7", driver.queued.get(0));
    }

    @Test
    void aPayloadOnAConnectionThatIsNotV3DoesNothingButLog() {
        Platform platform = new Platform();
        Sender sender = new Sender();
        BindingPayloads payloads = BindingPayloads.outsideV3(platform, sender);
        CapturingHandler logs = new CapturingHandler();
        Logger logger = Logger.getLogger("QUICraft");
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        logger.addHandler(logs);

        try {
            payloads.onPayload(new byte[] {1, 2, 3});
            payloads.onConfigurationEntered(1L);
            payloads.tick(2L);
            assertEquals(BindingDriver.QueueResult.SEND_NOW,
                    payloads.queueOrdinary("packet", 3),
                    "nothing is pausing ordinary packets on a connection that is not /3");
            assertTrue(payloads.releaseQueued().isEmpty());
        } finally {
            logger.removeHandler(logs);
            logger.setLevel(previousLevel);
        }

        assertFalse(payloads.isV3());
        assertNull(payloads.driver());
        assertTrue(sender.sent.isEmpty(), "12.8: no payload, and no binding action, is ever sent");
        assertFalse(platform.outbound || platform.inbound);
        assertNull(platform.failed, "12.8: no cipher or framing action either");
        assertEquals(1, logs.records.size(), "exactly one debug line, and nothing else");
        assertTrue(logs.records.get(0).getMessage().contains("not /3"),
                logs.records.get(0).getMessage());
    }

    @Test
    void aDriverAndAPlatformAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new BindingPayloads(new FakeDriver(), null, new Sender()));
        assertThrows(IllegalArgumentException.class,
                () -> new BindingPayloads(new FakeDriver(), new Platform(), null));
    }

    static final class Platform implements BindingDriver.Callbacks {
        boolean outbound;
        boolean inbound;
        String failed;

        @Override
        public void send(byte[] payload) {
            throw new AssertionError("the glue sends through its own payload sender, not the platform");
        }

        @Override
        public void activateOutbound() {
            outbound = true;
        }

        @Override
        public void activateInbound() {
            inbound = true;
        }

        @Override
        public void fail(int code, String reason) {
            failed = "0x" + Integer.toHexString(code) + ":" + reason;
        }
    }

    static final class Sender implements BindingPayloads.PayloadSender {
        final List<byte[]> sent = new ArrayList<>();

        @Override
        public void sendBindingPayload(byte[] payload) {
            sent.add(payload);
        }
    }

    static final class FakeDriver implements BindingDriver {
        final List<byte[]> payloads = new ArrayList<>();
        final List<String> queued = new ArrayList<>();
        long configurationEnteredAt;
        long tickedAt;

        @Override
        public void onConfigurationEntered(long nowNanos) {
            configurationEnteredAt = nowNanos;
        }

        @Override
        public void onPayload(byte[] payload) {
            payloads.add(payload);
        }

        @Override
        public QueueResult queueOrdinary(Object packet, int bytes) {
            queued.add(packet + ":" + bytes);
            return QueueResult.QUEUED;
        }

        @Override
        public List<Object> releaseQueued() {
            return new ArrayList<Object>(queued);
        }

        @Override
        public void tick(long nowNanos) {
            tickedAt = nowNanos;
        }
    }

    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public Level getLevel() {
            return Level.ALL;
        }
    }
}
