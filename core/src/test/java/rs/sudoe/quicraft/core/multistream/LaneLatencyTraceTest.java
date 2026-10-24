// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Lane;
import static org.junit.jupiter.api.Assertions.*;

class LaneLatencyTraceTest {
    @Test void disabledDoesNotReadClockOrFormatRecord() {
        LaneLatencyTrace trace = new LaneLatencyTrace(false,
                () -> { throw new AssertionError("clock read while off"); },
                line -> { throw new AssertionError("logged while off"); });
        trace.event(null, "unused", null);
        trace.route(null, 62, null);
    }

    @Test void enabledLogsRecordIdentityDependenciesAndRouteWithoutPayload() {
        List<String> lines = new ArrayList<>();
        LaneLatencyTrace trace = new LaneLatencyTrace(true, () -> 123L, lines::add);
        Record record = new Record(0, 9, 0,
                Collections.singletonList(new Record.Dependency(2, 7)), 0, new byte[] {99});
        trace.event(this, "tx-selected", record);
        SentPacket packet = new SentPacket();
        packet.assign(Lane.CONTROL, 0, 9);
        trace.route(this, 62, packet);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("t=123 owner="));
        assertTrue(lines.get(0).contains("stage=tx-selected stream=0 seq=9 kind=0 deps=2:7,"));
        assertTrue(lines.get(1).contains("stage=tx-route packet=62 stream=0 seq=9"));
    }
}
