// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.core.connect.FallbackReason;
import rs.sudoe.quicraft.core.connect.FallbackReport;

/** The proxy consumes players' fallback reports and logs a rate-limited hint (docs/protocol.md §6). */
class FallbackReportTest {
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void setUp() {
        Logger.getLogger("QUICraft").addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("QUICraft").removeHandler(capture);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getName().equals("toString")) {
                return type.getSimpleName();
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private static QuicraftVelocity plugin() {
        QuicraftVelocity plugin = new QuicraftVelocity(null, LoggerFactory.getLogger("test"), Path.of("unused"));
        plugin.fallbackLog = new FallbackReport.Log(25577);
        return plugin;
    }

    @Test
    void aPlayersReportIsConsumedAndLogged() {
        QuicraftVelocity plugin = plugin();
        PluginMessageEvent event = new PluginMessageEvent(stub(Player.class), stub(ServerConnection.class),
                QuicraftVelocity.FALLBACK_CHANNEL, FallbackReport.encode(FallbackReason.UDP_UNREACHABLE));
        plugin.onPluginMessage(event);
        assertFalse(event.getResult().isAllowed(), "never forwarded to the backend");
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).getMessage().contains("fell back to TCP"));

        PluginMessageEvent again = new PluginMessageEvent(stub(Player.class), stub(ServerConnection.class),
                QuicraftVelocity.FALLBACK_CHANNEL, FallbackReport.encode(FallbackReason.UDP_UNREACHABLE));
        plugin.onPluginMessage(again);
        assertFalse(again.getResult().isAllowed());
        assertEquals(1, logged.size(), "rate-limited");
    }

    @Test
    void otherChannelsAndSourcesAreLeftAlone() {
        QuicraftVelocity plugin = plugin();
        PluginMessageEvent other = new PluginMessageEvent(stub(Player.class), stub(ServerConnection.class),
                MinecraftChannelIdentifier.from("example:other"), new byte[] {1, 1});
        plugin.onPluginMessage(other);
        assertTrue(other.getResult().isAllowed());

        PluginMessageEvent fromBackend = new PluginMessageEvent(stub(ServerConnection.class),
                stub(ChannelMessageSink.class), QuicraftVelocity.FALLBACK_CHANNEL, new byte[] {1, 1});
        plugin.onPluginMessage(fromBackend);
        assertTrue(fromBackend.getResult().isAllowed(), "only players report");
        assertTrue(logged.isEmpty());
    }
}
