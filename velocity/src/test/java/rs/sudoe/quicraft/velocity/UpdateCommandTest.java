// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

class UpdateCommandTest {

    private static final String DISABLED_REASON = "live jar could not be located";

    private static UpdateCommand command(UpdaterRuntime runtime) {
        return new UpdateCommand(() -> runtime, LoggerFactory.getLogger(UpdateCommandTest.class));
    }

    private static boolean anyLineContains(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void consoleReachesTheUpdaterForEveryForm(@TempDir Path dir) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(dir);
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);

        command(runtime).execute(UpdaterFakes.invocation(console, "update", "check"));
        assertTrue(anyLineContains(out.lines, DISABLED_REASON), out.lines.toString());
        out.lines.clear();

        command(runtime).execute(UpdaterFakes.invocation(console, "update", "reset-state"));
        assertFalse(anyLineContains(out.lines, UpdateCommand.CONSOLE_ONLY), out.lines.toString());
    }

    @Test
    void playerWithoutPermissionIsRefused(@TempDir Path dir) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(dir);
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of());

        command(runtime).execute(UpdaterFakes.invocation(player, "update", "check"));
        assertEquals(List.of(UpdateCommand.DENIED), out.lines);
    }

    @Test
    void playerWithPermissionIsDispatched(@TempDir Path dir) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(dir);
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of(UpdateCommand.PERMISSION));

        command(runtime).execute(UpdaterFakes.invocation(player, "update", "check"));
        assertTrue(anyLineContains(out.lines, DISABLED_REASON), out.lines.toString());
    }

    @Test
    void playerResetStateIsRefusedEvenWithPermission(@TempDir Path dir) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(dir);
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource player = UpdaterFakes.player(out, Set.of(UpdateCommand.PERMISSION));

        command(runtime).execute(UpdaterFakes.invocation(player, "update", "reset-state"));
        assertEquals(List.of(UpdateCommand.CONSOLE_ONLY), out.lines);
    }

    @Test
    void unknownOrMissingSubcommandGivesTheUsageLine(@TempDir Path dir) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(dir);
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);

        command(runtime).execute(UpdaterFakes.invocation(console));
        command(runtime).execute(UpdaterFakes.invocation(console, "frobnicate"));
        command(runtime).execute(UpdaterFakes.invocation(console, "update", "download-everything"));
        assertEquals(List.of(UpdateCommand.USAGE, UpdateCommand.USAGE, UpdateCommand.USAGE), out.lines);
    }

    @Test
    void missingRuntimeSaysSo() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        CommandSource console = UpdaterFakes.console(out);

        new UpdateCommand(() -> null, LoggerFactory.getLogger(UpdateCommandTest.class))
                .execute(UpdaterFakes.invocation(console, "update", "check"));
        assertEquals(List.of(UpdateCommand.NOT_RUNNING), out.lines);
    }

    @Test
    void suggestionsOfferOnlyTheFixedForms() {
        UpdateCommand cmd = new UpdateCommand(() -> null, LoggerFactory.getLogger(UpdateCommandTest.class));
        CommandSource player = UpdaterFakes.player(new UpdaterFakes.Recorder(), Set.of());
        CommandSource console = UpdaterFakes.console(new UpdaterFakes.Recorder());

        assertEquals(List.of("update"), cmd.suggest(UpdaterFakes.invocation(player)));
        assertEquals(List.of("check", "cancel"), cmd.suggest(UpdaterFakes.invocation(player, "update", "")));
        assertEquals(List.of("check", "cancel", "reset-state"),
                cmd.suggest(UpdaterFakes.invocation(console, "update", "")));
        assertTrue(cmd.suggest(UpdaterFakes.invocation(player, "other")).isEmpty());
    }
}
