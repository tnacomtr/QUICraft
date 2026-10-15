// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * Task 5 of the /3 transport unit: the native decides whether {@code /3} exists (12.3, 13.13). A
 * listener asked for {@code /3} logs one INFO line and binds the plain {@code /2} listener on a
 * native without the maximum-window caps; a dial fails fast with
 * {@link QuicClient.V3UnavailableException} so its caller can fall back to {@code /2} or TCP.
 *
 * <p>Deliberately not skipped: Gradle runs this class in both {@code test} (QUICraft's patched
 * native) and {@code testUpstreamNative} (upstream Netty's native), and each run asserts its own
 * side of the decision.
 */
class V3NativeRefusalTest {
    static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":4242,\"default\":\"control\",\"routes\":[]}");

    static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @Test
    void theNativeDecidesWhetherThreeIsAvailable() throws Exception {
        if (QuicSupport.hasMaxFlowControlWindows()) {
            // The patched native: a /3 listener serves a /3 dial.
            try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                    stream -> { }, protocol -> TABLE)) {
                V3Connection connection = QuicClient.connectV3(server.localAddress(), identity.fingerprint(),
                        TABLE, null).get(10, TimeUnit.SECONDS);
                assertNotNull(connection);
                connection.close();
            }
            return;
        }

        // Upstream native: a /3 dial fails fast with the distinct type, before any packet.
        CompletableFuture<V3Connection> refused = QuicClient.connectV3(Loopback.anyLocal(), identity.fingerprint(),
                TABLE, null);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof QuicClient.V3UnavailableException,
                "expected V3UnavailableException, got " + failure.getCause());

        // And a listener asked for /3 binds the plain /2 listener instead, one INFO line logged.
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                stream -> { }, protocol -> TABLE)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(10, TimeUnit.SECONDS);
            assertNotNull(stream, "the fallback listener must speak /2");
            stream.close();
        }
    }

    /**
     * The one INFO line of task 5 is per JVM, not per bind: two listeners asked for {@code /3} on a
     * native without the window caps must log it exactly once. The patched run asserts the other
     * side of the same flag: no line at all.
     */
    @Test
    void enablingThreeOnANativeWithoutTheWindowCapsLogsOneInfoLine() throws Exception {
        // This test owns the one-time line; a sibling that bound a /3 listener may have consumed it.
        warningFlag().set(false);
        List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        Logger logger = Logger.getLogger("QUICraft");
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            try (QuicServer first = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                    stream -> { }, protocol -> TABLE)) {
                assertNotNull(first);
            }
            try (QuicServer second = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                    stream -> { }, protocol -> TABLE)) {
                assertNotNull(second);
            }
        } finally {
            logger.removeHandler(handler);
        }

        int matching = 0;
        for (String line : lines) {
            if (line != null && line.contains("cannot cap quiche's flow-control windows")) {
                matching++;
            }
        }
        if (QuicSupport.hasMaxFlowControlWindows()) {
            assertEquals(0, matching, "the patched native enables /3 without a warning");
        } else {
            assertEquals(1, matching, "one INFO line per JVM across the two binds");
        }
    }

    /** The listener's one-time warning flag, so this test can own it. */
    private static AtomicBoolean warningFlag() throws Exception {
        java.lang.reflect.Field field = QuicServer.class.getDeclaredField("V3_NATIVE_WARNING");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(null);
    }
}
