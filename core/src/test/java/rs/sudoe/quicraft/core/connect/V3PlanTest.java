// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The {@code /3} plan's own fail-safe behaviour (docs/protocol.md 12.14, 12.15): its reporting
 * methods must always answer, even when the worker executor refuses the work they would otherwise
 * do. The adapter calls them from the connection's event loop, where an exception would leave the
 * join unfinished instead of falling back to TCP.
 */
class V3PlanTest {
    /** Refuses every task, like an executor that is shutting down. */
    static final class RejectingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("the worker is shutting down");
        }
    }

    private static Fingerprint fingerprint(char digit) {
        StringBuilder text = new StringBuilder("sha256:");
        for (int i = 0; i < 64; i++) {
            text.append(digit);
        }
        return Fingerprint.parse(text.toString());
    }

    private static V3Plan plan(Executor worker) throws Exception {
        return new V3Plan(PinState.CHANGED, null, fingerprint('c'), new byte[32], PinStore.load(null),
                FailureCache.load(null), RebindState.load(null), worker,
                InetAddress.getByName("192.0.2.10"), 25570, "mc.example.com", 25565);
    }

    @Test
    void failedStillAnswersWhenTheWorkerRefusesTheTask() throws Exception {
        V3Plan plan = plan(new RejectingExecutor());
        assertTrue(plan.failed(false, V3Plan.BINDING_FAILED),
                "the retry decision is policy, so a refusing worker must not hide it");
        assertEquals("QUIC multi-stream failed (" + V3Plan.BINDING_FAILED + "), fell back to TCP",
                plan.fallbackMessage());
        assertTrue(plan.isFailed());
    }

    @Test
    void activatedCompletesFalseWhenTheWorkerRefusesTheTask() throws Exception {
        V3Plan plan = plan(new RejectingExecutor());
        // False means "not a passed activation": the caller closes 0x10 (12.15) instead of leaving
        // the join hanging on an exception.
        assertFalse(plan.activated().get(5, TimeUnit.SECONDS).booleanValue());
    }
}
