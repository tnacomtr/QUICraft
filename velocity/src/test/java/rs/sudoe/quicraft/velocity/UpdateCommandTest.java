// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/** The command grammar and permission checks of docs/updater.md 3. */
class UpdateCommandTest {

    private static UpdateCommand command(UpdateDispatcher dispatcher) {
        return new UpdateCommand(() -> dispatcher, LoggerFactory.getLogger(UpdateCommandTest.class));
    }

    private static UpdateCommand noDispatcher() {
        return new UpdateCommand(() -> null, LoggerFactory.getLogger(UpdateCommandTest.class));
    }

    @Test
    void consoleReachesTheDispatcherForEveryFormWithTheExactArgs() {
        UpdaterFakes.RecordingDispatcher dispatcher = new UpdaterFakes.RecordingDispatcher();
        CommandSource console = UpdaterFakes.console(new UpdaterFakes.Recorder());
        UpdateCommand cmd = command(dispatcher);

        cmd.execute(UpdaterFakes.invocation(console, "update"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "check"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "check", "-s"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "cancel"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "reset-state"));

        assertEquals(5, dispatcher.calls.size());
        assertArrayEquals(new String[] {}, dispatcher.calls.get(0));
        assertArrayEquals(new String[] {"check"}, dispatcher.calls.get(1));
        assertArrayEquals(new String[] {"check", "-s"}, dispatcher.calls.get(2));
        assertArrayEquals(new String[] {"cancel"}, dispatcher.calls.get(3));
        assertArrayEquals(new String[] {"reset-state"}, dispatcher.calls.get(4));
    }

    @Test
    void playerWithPermissionReachesTheDispatcherAndIsMarkedNonConsole() {
        UpdaterFakes.RecordingDispatcher dispatcher = new UpdaterFakes.RecordingDispatcher();
        CommandSource player = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of(UpdateCommand.PERMISSION));

        command(dispatcher).execute(UpdaterFakes.invocation(player, "update", "check"));

        assertEquals(1, dispatcher.calls.size());
        assertArrayEquals(new String[] {"check"}, dispatcher.calls.get(0));
        assertEquals(List.of(false), dispatcher.consoles);
    }

    @Test
    void anotherUpdateWordIsRefused() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);

        command(new UpdaterFakes.RecordingDispatcher())
                .execute(UpdaterFakes.invocation(console, "update", "update"));
        assertEquals(List.of(UpdateCommand.USAGE), out.lines);
    }

    @Test
    void uppercaseResetStateFromAPlayerIsNormalisedAndRefusedAsConsoleOnly() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of(UpdateCommand.PERMISSION));

        command(new UpdaterFakes.RecordingDispatcher())
                .execute(UpdaterFakes.invocation(player, "update", "RESET-STATE"));
        assertEquals(List.of(UpdateCommand.CONSOLE_ONLY), out.lines);
    }

    @Test
    void uppercaseResetStateFromTheConsoleIsDispatchedLowercased() {
        UpdaterFakes.RecordingDispatcher dispatcher = new UpdaterFakes.RecordingDispatcher();
        CommandSource console = UpdaterFakes.console(new UpdaterFakes.Recorder());

        command(dispatcher).execute(UpdaterFakes.invocation(console, "update", "RESET-STATE"));
        assertEquals(1, dispatcher.calls.size());
        assertArrayEquals(new String[] {"reset-state"}, dispatcher.calls.get(0));
    }

    @Test
    void playerWithoutPermissionIsRefusedBeforeTheUsageLine() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of());

        command(new UpdaterFakes.RecordingDispatcher())
                .execute(UpdaterFakes.invocation(player, "update", "frobnicate"));
        assertEquals(List.of(UpdateCommand.DENIED), out.lines);
    }

    @Test
    void playerResetStateIsRefusedEvenWithPermission() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of(UpdateCommand.PERMISSION));

        command(new UpdaterFakes.RecordingDispatcher())
                .execute(UpdaterFakes.invocation(player, "update", "reset-state"));
        assertEquals(List.of(UpdateCommand.CONSOLE_ONLY), out.lines);
    }

    @Test
    void unknownOrMissingSubcommandGivesTheUsageLine() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);
        UpdateCommand cmd = command(new UpdaterFakes.RecordingDispatcher());

        cmd.execute(UpdaterFakes.invocation(console));
        cmd.execute(UpdaterFakes.invocation(console, "frobnicate"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "download-everything"));
        cmd.execute(UpdaterFakes.invocation(console, "update", "check", "-x"));
        assertEquals(List.of(UpdateCommand.USAGE, UpdateCommand.USAGE, UpdateCommand.USAGE, UpdateCommand.USAGE),
                out.lines);
    }

    @Test
    void missingRuntimeSaysSo() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);

        noDispatcher().execute(UpdaterFakes.invocation(console, "update", "check"));
        assertEquals(List.of(UpdateCommand.NOT_RUNNING), out.lines);
    }

    @Test
    void hasPermissionIsConsoleOrTheUpdatePermission() {
        CommandSource console = UpdaterFakes.console(new UpdaterFakes.Recorder());
        CommandSource admin = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of(UpdateCommand.PERMISSION));
        CommandSource plain = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of());
        UpdateCommand cmd = noDispatcher();

        assertTrue(cmd.hasPermission(UpdaterFakes.invocation(console)));
        assertTrue(cmd.hasPermission(UpdaterFakes.invocation(admin)));
        assertFalse(cmd.hasPermission(UpdaterFakes.invocation(plain)));
    }

    @Test
    void suggestionsHonourThePermissionAndStayFixed() {
        UpdateCommand cmd = noDispatcher();
        CommandSource admin = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of(UpdateCommand.PERMISSION));
        CommandSource plain = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of());
        CommandSource console = UpdaterFakes.console(new UpdaterFakes.Recorder());

        assertTrue(cmd.suggest(UpdaterFakes.invocation(plain)).isEmpty());
        assertEquals(List.of("update"), cmd.suggest(UpdaterFakes.invocation(admin)));
        assertEquals(List.of("check", "cancel"), cmd.suggest(UpdaterFakes.invocation(admin, "update", "")));
        assertEquals(List.of("check", "cancel", "reset-state"),
                cmd.suggest(UpdaterFakes.invocation(console, "update", "")));
        assertTrue(cmd.suggest(UpdaterFakes.invocation(admin, "other")).isEmpty());
    }

    @Test
    void aFailureInTheDispatcherIsReportedWithoutReachingVelocity() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);
        UpdateDispatcher failing = (args, console1, sink) -> {
            throw new IllegalStateException("boom");
        };

        new UpdateCommand(() -> failing, LoggerFactory.getLogger(UpdateCommandTest.class))
                .execute(UpdaterFakes.invocation(console, "update"));
        assertEquals(List.of(UpdateCommand.FAILED), out.lines);
    }

    @Test
    void theDispatcherOutputReachesTheInvoker() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);
        UpdateDispatcher echoing = (args, isConsole, sink) -> {
            sink.accept("a line");
            return UpdaterRuntime.Reply.DONE;
        };

        new UpdateCommand(() -> echoing, LoggerFactory.getLogger(UpdateCommandTest.class))
                .execute(UpdaterFakes.invocation(console, "update"));
        assertEquals(List.of("a line"), out.lines);
    }
}
