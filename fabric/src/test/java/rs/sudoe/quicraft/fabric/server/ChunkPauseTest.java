// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkPauseTest {
    private final List<String> infos = new ArrayList<>();
    private Consumer<String> previousSink;

    @BeforeEach
    void captureInfo() {
        previousSink = ChunkPause.setInfoSink(infos::add);
    }

    @AfterEach
    void restoreInfo() {
        ChunkPause.setInfoSink(previousSink);
    }

    @Test
    void aNullChannelIsNeverPaused() {
        assertFalse(ChunkPause.paused(null));
        ChunkPause.attach(null, () -> true);
        assertFalse(ChunkPause.paused(null));
    }

    @Test
    void aChannelWithoutAnAttributeIsNeverPaused() {
        EmbeddedChannel channel = new EmbeddedChannel();
        assertFalse(ChunkPause.paused(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    void aNullSupplierIsIgnored() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChunkPause.attach(channel, null);
        assertFalse(ChunkPause.paused(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    void pausedFollowsThePressureSupplierBothWays() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicBoolean pressure = new AtomicBoolean();
        ChunkPause.attach(channel, pressure::get);

        assertFalse(ChunkPause.paused(channel));
        pressure.set(true);
        assertTrue(ChunkPause.paused(channel));
        assertTrue(ChunkPause.paused(channel));
        pressure.set(false);
        assertFalse(ChunkPause.paused(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    void aThrowingSupplierMeansNotPaused() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChunkPause.attach(channel, () -> {
            throw new IllegalStateException("broken check");
        });
        assertFalse(ChunkPause.paused(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    void aThrowingLoggerDoesNotChangeTheDecision() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Consumer<String> previous = ChunkPause.setInfoSink(message -> {
            throw new IllegalStateException("broken logger");
        });
        try {
            ChunkPause.attach(channel, () -> true);
            assertTrue(ChunkPause.paused(channel));
        } finally {
            ChunkPause.setInfoSink(previous);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void theInfoLineIsWrittenOncePerConnection() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        ChunkPause.attach(first, () -> true);
        ChunkPause.attach(second, () -> true);
        long before = ChunkPause.pausedCalls();

        for (int tick = 0; tick < 5; tick++) {
            assertTrue(ChunkPause.paused(first));
        }
        assertTrue(ChunkPause.paused(second));

        // One line per connection, however many ticks it was paused for.
        assertEquals(2, infos.size());
        assertTrue(infos.stream().allMatch(line -> line.startsWith("QUICraft v3 chunk pause")));
        assertEquals(6, ChunkPause.pausedCalls() - before);
        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    @Test
    void noLineIsWrittenWhileNotPaused() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChunkPause.attach(channel, () -> false);
        assertFalse(ChunkPause.paused(channel));
        assertEquals(0, infos.size());
        channel.finishAndReleaseAll();
    }
}
