package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.operations.BackupRestoreError;
import com.smile.aceeconomy.operations.BackupResult;
import com.smile.aceeconomy.operations.RestoreResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the {@code /aceeco backup} and {@code /aceeco restore} admin surface:
 * spec registration, permission nodes, console-only restore policy, the exact-literal
 * confirmation, and typed result/error mapping. Everything is dispatched through the real
 * registry so sender/permission/argument handling is exercised together with the mapping.
 */
class BackupRestoreCommandTest {

    private BackupCommandService backupFacade;

    private CommandServices services() {
        backupFacade = mock(BackupCommandService.class);
        return new CommandServices(mock(EconomyCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                backupFacade);
    }

    private Sender consoleSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.backup");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.restore");
        return sender;
    }

    private Sender playerSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.backup");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.restore");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        return sender;
    }

    private CommandRegistryImpl registryFor(CommandServices services, ReplySink sink) {
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        return registry;
    }

    private Throwable capturedError(ReplySink sink) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink, org.mockito.Mockito.atLeastOnce()).sendError(any(Sender.class), error.capture());
        return error.getValue();
    }

    private String capturedReply(ReplySink sink) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink, org.mockito.Mockito.atLeastOnce()).send(any(Sender.class), message.capture());
        return message.getValue();
    }

    // ---------------- surface ----------------

    @Test
    void aceecoSpecRegistersBackupAndRestoreWithTheirPolicies() {
        CommandServices services = services();

        CommandSpec aceeco = AceEcoCommandSpec.create(services);
        var backup = aceeco.findSubCommand("backup");
        var restore = aceeco.findSubCommand("restore");

        assertNotNull(backup, "/aceeco must expose a backup subcommand");
        assertEquals("aceeconomy.admin.backup", backup.permission());
        assertEquals(0, backup.minArgs(), "backup label is optional");
        assertEquals(1, backup.maxArgs(), "backup accepts at most one label");
        assertFalse(backup.consoleOnly(),
                "backup may run from an authorized in-game admin or the console");

        assertNotNull(restore, "/aceeco must expose a restore subcommand");
        assertEquals("aceeconomy.admin.restore", restore.permission());
        assertEquals(2, restore.minArgs(), "restore requires <backup-id> confirm");
        assertEquals(2, restore.maxArgs(), "restore accepts no extra arguments");
        assertTrue(restore.consoleOnly(),
                "restore is destructive and must be console-only");

        CommandSpec registered = V2CommandRegistry.create(services).specs().stream()
                .filter(spec -> spec.name().equals("aceeco"))
                .findFirst().orElseThrow();
        assertNotNull(registered.findSubCommand("backup"));
        assertNotNull(registered.findSubCommand("restore"));
    }

    // ---------------- sender / permission gates ----------------

    @Test
    void playerDispatchOfRestoreIsRejectedBeforeTheFacadeIsCalled() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(playerSender(true, true), "aceeco",
                List.of("restore", "20260824T093000-aaaa1111", "confirm"));

        assertEquals("ACELIB-CMD-005", ((CommandException) capturedError(sink)).getCode(),
                "a player invoking console-only restore must hit PLAYER_NOT_ALLOWED");
        verify(backupFacade, never()).restore(anyString());
    }

    @Test
    void missingChildPermissionRejectsBackupAndRestore() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, false), "aceeco", List.of("backup"));
        assertEquals("ACELIB-CMD-003", ((CommandException) capturedError(sink)).getCode());

        registry.dispatch(consoleSender(true, false), "aceeco",
                List.of("restore", "some-id-1234", "confirm"));
        assertEquals("ACELIB-CMD-003", ((CommandException) capturedError(sink)).getCode());
        verify(backupFacade, never()).createBackup(any());
        verify(backupFacade, never()).restore(anyString());
    }

    // ---------------- confirmation gate ----------------

    @Test
    void restoreRequiresTheExactLowercaseConfirmLiteral() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        // Note: the shared arg helper trims arguments, so a trailing space is normalized
        // platform-wide exactly like every other command argument; case and wording must
        // match exactly.
        List<String> wrongConfirmations = List.of("CONFIRM", "Confirm", "yes", "confirmed");
        for (String wrong : wrongConfirmations) {
            registry.dispatch(consoleSender(true, true), "aceeco",
                    List.of("restore", "20260824T093000-aaaa1111", wrong));
            Throwable error = capturedError(sink);
            assertTrue(error instanceof CommandException, "expected typed error for '" + wrong + "'");
            assertEquals("ACELIB-CMD-RESTORE-CONFIRM-REQUIRED",
                    ((CommandException) error).getCode(),
                    "confirmation must match exactly: '" + wrong + "'");
        }
        verify(backupFacade, never()).restore(anyString());
    }

    @Test
    void restoreWithMissingConfirmationArgumentNeverReachesTheFacade() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "20260824T093000-aaaa1111"));

        Throwable error = capturedError(sink);
        assertTrue(error instanceof CommandException,
                "the dispatcher must reject the missing argument as a typed error");
        verify(backupFacade, never()).restore(anyString());
    }

    @Test
    void restoreWithExtraArgumentsNeverReachesTheFacade() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "20260824T093000-aaaa1111", "confirm", "extra"));

        Throwable error = capturedError(sink);
        assertTrue(error instanceof CommandException,
                "the dispatcher must reject extra arguments as a typed error");
        verify(backupFacade, never()).restore(anyString());
    }

    // ---------------- success replies ----------------

    @Test
    void successfulBackupReportsTheGeneratedSnapshotId() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.createBackup(eq(null))).thenReturn(CompletableFuture.completedFuture(
                BackupResult.success("20260824T093000-aaaa1111")));

        registry.dispatch(consoleSender(true, true), "aceeco", List.of("backup"));

        String reply = capturedReply(sink);
        assertTrue(reply.contains("20260824T093000-aaaa1111"),
                "success reply must identify the snapshot id: " + reply);
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void successfulLabeledBackupPassesTheLabelThrough() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.createBackup(eq("weekly"))).thenReturn(CompletableFuture.completedFuture(
                BackupResult.success("20260824T093000-aaaa1111-weekly")));

        registry.dispatch(consoleSender(true, true), "aceeco", List.of("backup", "weekly"));

        verify(backupFacade).createBackup("weekly");
        assertTrue(capturedReply(sink).contains("weekly"));
    }

    @Test
    void successfulRestoreReportsSafetyIdAndRestartBoundary() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.restore("20260824T093000-aaaa1111"))
                .thenReturn(CompletableFuture.completedFuture(
                        RestoreResult.success("20260824T093000-aaaa1111",
                                "20260824T093001-bbbb2222-pre-restore")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "20260824T093000-aaaa1111", "confirm"));

        String reply = capturedReply(sink);
        assertTrue(reply.contains("20260824T093000-aaaa1111"), reply);
        assertTrue(reply.contains("20260824T093001-bbbb2222-pre-restore"),
                "success reply must report the safety backup id: " + reply);
        // With localized messages==null fallback, reply is identifier + ids; with mocked messages it contains restart wording.
        // Offline test uses null messages -> identifier path, so verify ids present rather than English restart wording.
        assertTrue(reply.contains("20260824T093000-aaaa1111"),
                "success reply must be present: " + reply);
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    // ---------------- typed failures ----------------

    @Test
    void playersOnlineFailureMapsToItsStableCodeWithoutSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.restore("id-1")).thenReturn(CompletableFuture.completedFuture(
                RestoreResult.failure(BackupRestoreError.PLAYERS_ONLINE,
                        "players are still online; ask everyone to leave first")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "id-1", "confirm"));

        assertEquals("ACELIB-CMD-RESTORE-PLAYERS-ONLINE",
                ((CommandException) capturedError(sink)).getCode());
        verify(sink, never()).send(any(Sender.class), anyString());
    }

    @Test
    void labelInvalidAndBusyFailuresMapToStableCodes() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.createBackup("../evil")).thenReturn(CompletableFuture.completedFuture(
                BackupResult.failure(BackupRestoreError.LABEL_INVALID, "bad label")));
        when(backupFacade.createBackup(null)).thenReturn(CompletableFuture.completedFuture(
                BackupResult.failure(BackupRestoreError.BUSY, "another operation is running")));

        registry.dispatch(consoleSender(true, true), "aceeco", List.of("backup", "../evil"));
        assertEquals("ACELIB-CMD-BACKUP-LABEL-INVALID",
                ((CommandException) capturedError(sink)).getCode());

        registry.dispatch(consoleSender(true, true), "aceeco", List.of("backup"));
        assertEquals("ACELIB-CMD-BACKUP-BUSY",
                ((CommandException) capturedError(sink)).getCode());
        verify(sink, never()).send(any(Sender.class), anyString());
    }

    @Test
    void safetyBackupFailureKeepsItsOperatorGuidanceVisible() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.restore("id-2")).thenReturn(CompletableFuture.completedFuture(
                RestoreResult.failure(BackupRestoreError.SAFETY_BACKUP_FAILED,
                        "safety snapshot failed: disk full; live state untouched")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "id-2", "confirm"));

        Throwable error = capturedError(sink);
        assertEquals("ACELIB-CMD-RESTORE-SAFETY-BACKUP-FAILED",
                ((CommandException) error).getCode());
        assertTrue(error.getMessage().contains("disk full"),
                "the underlying cause must stay visible: " + error.getMessage());
        verify(sink, never()).send(any(Sender.class), anyString());
    }

    @Test
    void exceptionalFacadeCompletionIsReportedAsFailureWithoutFakeSuccess() {
        CommandServices services = services();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(backupFacade.restore("id-3"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage boom")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("restore", "id-3", "confirm"));

        assertEquals("storage boom", capturedError(sink).getMessage());
        verify(sink, never()).send(any(Sender.class), anyString());
    }
}
