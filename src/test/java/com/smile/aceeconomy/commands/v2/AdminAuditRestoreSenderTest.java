package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.ImportCommandService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.operations.RestoreResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sender-derived audit for the console-only restore: the replacement is
 * filed under the fixed console marker with the trailing reason. The stored
 * snapshot itself is never rewritten; the audit rides the restore publish.
 *
 * <p>Dispatch goes through the real registry so the confirmation gate and
 * the audit handover are exercised together.</p>
 */
class AdminAuditRestoreSenderTest {

    private BackupCommandService backups;
    private CommandServices services;

    private void wire() {
        backups = mock(BackupCommandService.class);
        when(backups.restore(anyString(), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        RestoreResult.success("seed-id", "safety-id")));
        when(backups.restore(anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        RestoreResult.success("seed-id", "safety-id")));

        services = new CommandServices(mock(EconomyCommandService.class),
                mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class), mock(RollbackCommandService.class),
                backups, mock(ImportCommandService.class));
    }

    private Sender consoleSender() {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin.restore");
        doReturn("console").when(sender).getName();
        return sender;
    }

    private void dispatch(Sender sender, List<String> args) {
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(sender, "aceeco", args);
    }

    @Test
    void consoleRestoreCarriesReasonUnderTheConsoleMarker() {
        wire();

        dispatch(consoleSender(),
                List.of("restore", "seed-id", "confirm", "harbor-restore"));

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(backups, org.mockito.Mockito.timeout(2000))
                .restore(eq("seed-id"), audit.capture());
        assertNull(audit.getValue().operatorId());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.getValue().operatorName());
        assertEquals("harbor-restore", audit.getValue().reason());
    }

    @Test
    void omittedRestoreReasonFallsBackToTheDefault() {
        wire();

        dispatch(consoleSender(), List.of("restore", "seed-id", "confirm"));

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(backups, org.mockito.Mockito.timeout(2000))
                .restore(eq("seed-id"), audit.capture());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.getValue().operatorName());
        assertEquals(AdminAudit.DEFAULT_REASON, audit.getValue().reason());
    }
}
