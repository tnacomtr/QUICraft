// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Lane;

/** The acknowledgement waits behind the handler tasks it acknowledges (docs/protocol.md 13.10). */
class AckQueueTest {
    @Test
    void noAckIsReleasedWhileAnEarlierHandlerStillWaitsForTheMainThread() {
        ArrayDeque<Runnable> mainThreadQueue = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        AckQueue acks = new AckQueue(mainThreadQueue::add, (lane, sequence) -> log.add("ack " + lane + " " + sequence));

        // Connection.channelRead0 returned, but the handler is only queued for the main thread.
        mainThreadQueue.add(() -> log.add("handler ran"));
        acks.acknowledge(Lane.CONTROL, 7);

        assertEquals(List.of(), log, "no ack and so no credit yet");

        while (!mainThreadQueue.isEmpty()) {
            mainThreadQueue.poll().run();
        }
        assertEquals(List.of("handler ran", "ack CONTROL 7"), log, "the ack follows the handler");
    }
}
