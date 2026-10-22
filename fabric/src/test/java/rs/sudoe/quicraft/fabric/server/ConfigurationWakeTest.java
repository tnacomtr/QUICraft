// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The early wake of the configuration stage after {@code hold.release()}: it runs once, only while
 * the released task is still the listener's, and a wake that fails leaves the vanilla tick in
 * charge. Nothing here starts a game.
 */
class ConfigurationWakeTest {
    /** The released task as the server thread sees it, plus what the vanilla tick would do. */
    private static class FakeStage implements ConfigurationWake.Stage {
        boolean open = true;
        int advanced;
        int ticks;
        boolean throwOnOpen;
        boolean throwOnAdvance;

        @Override
        public boolean open() {
            if (throwOnOpen) {
                throw new IllegalStateException("open");
            }
            return open;
        }

        @Override
        public void advance() {
            if (throwOnAdvance) {
                throw new IllegalStateException("advance");
            }
            advanced++;
        }

        /** What {@code ServerConfigurationPacketListenerImpl.tick()} does on its next pass. */
        void tick() {
            if (open) {
                open = false;
                ticks++;
            }
        }
    }

    private final FakeStage stage = new FakeStage();
    private final List<Runnable> serverThread = new ArrayList<Runnable>();

    private ConfigurationWake wake() {
        return new ConfigurationWake(stage, serverThread::add);
    }

    @Test
    void theReleasedTaskIsAdvancedOnceOnTheServerThread() {
        ConfigurationWake wake = wake();
        wake.schedule();

        assertEquals(1, serverThread.size(), "the wake is one task on the server thread");
        serverThread.get(0).run();
        assertEquals(1, stage.advanced);
        assertEquals(0, stage.ticks, "the tick does not advance it a second time");

        // The vanilla tick sees the stage already moved on, exactly as after a normal tick.
        stage.open = false;
        stage.tick();
        assertEquals(1, stage.advanced);
        assertEquals(0, stage.ticks);
    }

    @Test
    void schedulingTwiceSchedulesOneWake() {
        ConfigurationWake wake = wake();
        wake.schedule();
        wake.schedule();

        assertEquals(1, serverThread.size());
    }

    @Test
    void runningTheTaskTwiceAdvancesOnce() {
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();
        serverThread.get(0).run();

        assertEquals(1, stage.advanced, "the wake-up is one-shot even if the server runs it again");
    }

    @Test
    void aClosedConnectionOrAnotherTaskIsNotAdvanced() {
        stage.open = false; // the connection closed, or the listener already finished the task
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();

        assertEquals(0, stage.advanced);
    }

    @Test
    void aThrowingWakeLeavesTheTickWorking() {
        stage.throwOnAdvance = true;
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run(); // swallowed, no propagation

        assertEquals(0, stage.advanced);
        stage.tick();
        assertEquals(1, stage.ticks, "the vanilla tick still advances the stage");
        assertFalse(stage.open);
    }

    @Test
    void aThrowingCheckDoesNotAdvanceOrPropagate() {
        stage.throwOnOpen = true;
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();

        assertEquals(0, stage.advanced);
    }

    @Test
    void tickBeforeWakeLeavesNoDoubleAdvance() {
        // The vanilla tick runs first (e.g., a busy server tick before the scheduled wake).
        stage.tick(); // the tick finishes the stage
        assertFalse(stage.open, "the tick finished the task");
        assertEquals(1, stage.ticks);
        assertEquals(0, stage.advanced, "the tick does not call advance()");

        // The wake still runs once, but sees the stage is no longer open.
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();
        assertEquals(0, stage.advanced, "no double-advance when tick came first");
        assertEquals(1, stage.ticks);
    }

    @Test
    void listenerOrTaskReplacedBeforeWakeRuns() {
        // Simulates the listener finishing or replacing the task before our wake reaches it.
        stage.open = false; // listener/task no longer current
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();
        assertEquals(0, stage.advanced, "no advance when listener/task was replaced");
    }

    @Test
    void partialAdvancementThenExceptionLeavesVanillaTickWorking() {
        // The advance throws after doing some work: the tick must still advance it.
        FakeStage throwingAfterPartial = new FakeStage() {
            @Override
            public void advance() {
                advanced++;
                if (advanced > 0) {
                    throw new IllegalStateException("after partial");
                }
            }
        };
        ConfigurationWake wake = new ConfigurationWake(throwingAfterPartial, serverThread::add);
        wake.schedule();
        serverThread.get(0).run();
        assertEquals(1, throwingAfterPartial.advanced, "partial advancement happened");
        throwingAfterPartial.tick();
        assertEquals(1, throwingAfterPartial.ticks, "the tick still finishes the stage");
        assertFalse(throwingAfterPartial.open);
    }

    @Test
    void diagnosticsOffDoesNotAffectTheWake() {
        // When JoinDiag is off, the stage behaves identically.
        FakeStage stageOff = new FakeStage();
        ConfigurationWake wake = new ConfigurationWake(stageOff, serverThread::add);
        wake.schedule();
        serverThread.get(0).run();
        assertEquals(1, stageOff.advanced);
        assertEquals(0, stageOff.ticks);
        stageOff.tick();
        assertEquals(1, stageOff.ticks);
    }

    @Test
    void aServerThatRefusesTheTaskDoesNothing() {
        ConfigurationWake wake = new ConfigurationWake(stage, task -> {
            throw new IllegalStateException("shutting down");
        });
        wake.schedule(); // swallowed

        assertEquals(0, stage.advanced);
        stage.tick();
        assertEquals(1, stage.ticks, "the vanilla tick still advances the stage");
    }

    @Test
    void tickBeforeWakeLeavesTheTickAsAuthority() {
        stage.open = true;
        stage.tick(); // the tick finishes first
        assertEquals(1, stage.ticks, "tick ran first");
        assertFalse(stage.open, "the tick finished the stage");

        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run(); // wake sees open=false, does nothing
        assertEquals(0, stage.advanced, "only the tick finished; advance() was never called");
        assertEquals(1, stage.ticks, "no extra tick from the wake");
    }

    @Test
    void listenerOrTaskReplacedBeforeWakeDoesNotAdvance() {
        stage.open = false; // replaced by the listener or finished by another task
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run();
        assertEquals(0, stage.advanced, "a replaced task is not advanced again");
    }

    @Test
    void exceptionAfterPartialAdvanceLeavesTickAsAuthority() {
        // The stage's open() passes but advance() throws: the tick must still finish it.
        stage.throwOnAdvance = true;
        ConfigurationWake wake = wake();
        wake.schedule();
        serverThread.get(0).run(); // swallowed

        assertEquals(0, stage.advanced, "the failed wake did not complete the advance");
        stage.throwOnAdvance = false; // restore for the tick
        stage.tick();
        assertEquals(1, stage.ticks, "the tick finishes the stage after a failed wake");
        assertFalse(stage.open);
    }
}
