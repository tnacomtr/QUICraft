// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.ChannelFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.multistream.ReverseChunkRig;

/** Initial and rolling reverse-CHUNK exposure, using the production codec and session (13.13). */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReverseChunkWindowTest {
    @Test
    void reverseChunkSecondByteIsFlowControlled() throws Exception {
        try (ReverseChunkRig rig = new ReverseChunkRig(1)) {
            assertEquals(1, rig.capacity(0), "exact initial transport credit");
            rig.write(0, 7).get(5, TimeUnit.SECONDS);
            ChannelFuture second = rig.write(0, 8);
            assertFalse(second.await(500), "quiche must not accept a second byte while the guard is held");
            assertEquals(0, rig.capacity(0), "no rolling transport credit without a read");
            assertEquals(0, rig.bytes.get());
            assertEquals(1, rig.reads.get(), "one charged guard read only");
            rig.releaseGuards();
            assertEquals(0x11, rig.peerClose());
            assertEquals(1, rig.bytes.get());
            assertEquals(1, rig.synchronousCloses.get());
            assertEquals(1, rig.reads.get(), "never rearm the guard");
            assertEquals(0, rig.workBeforeClose.get());
            assertFalse(second.isSuccess(), "the second byte never entered quiche");
        }
    }

    @Test
    void coalescedReverseGuardsNeverReplenishCredit() throws Exception {
        int n = 6;
        try (ReverseChunkRig rig = new ReverseChunkRig(n)) {
            ChannelFuture[] seconds = new ChannelFuture[n];
            for (int i = 0; i < n; i++) {
                assertEquals(1, rig.capacity(i));
                rig.write(i, 7).get(5, TimeUnit.SECONDS);
                seconds[i] = rig.write(i, 8);
            }
            for (int i = 0; i < n; i++) {
                assertFalse(seconds[i].await(100));
                assertEquals(0, rig.capacity(i));
            }
            assertEquals(n, rig.reads.get());
            rig.releaseGuards();
            assertEquals(0x11, rig.peerClose());
            assertEquals(1, rig.bytes.get(), "the first guard closes before the other coalesced reads");
            assertEquals(1, rig.synchronousCloses.get());
            assertEquals(n, rig.reads.get());
            assertEquals(0, rig.workBeforeClose.get());
            for (ChannelFuture second : seconds) assertFalse(second.isSuccess());
        }
    }
}
