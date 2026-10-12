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
import com.smile.aceeconomy.operations.RollbackResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sender-derived audit for the console-only rollback: the reversal is filed
 * under the fixed console marker with the trailing reason, an omitted reason
 * falls back to the default, and the operator can never come from an
 * argument.
 *
 * <p>Every case dispatches through the real registry so sender, permission
 * and argument handling are exercised together with the audit handover.</p>
 */
class AdminAuditRollbackSenderTest {

    private RollbackCommandService rollback;
    private CommandServices services;

    private void wire() {
        rollback = mock(RollbackCommandService.class);
        when(rollback.rollback(any(UUID.class), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        RollbackResult.success(List.of(UUID.randomUUID()))));
        when(rollback.rollback(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        RollbackResult.success(List.of(UUID.randomUUID()))));

        services = new CommandServices(mock(EconomyCommandService.class),
                mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class), rollback,
                mock(BackupCommandService.class), mock(ImportCommandService.class));
    }

    private Sender consoleSender() {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin.rollback");
        doReturn("console").when(sender).getName();
        return sender;
    }

    private void dispatch(Sender sender, List<String> args) {
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(sender, "aceeco", args);
    }

    private AdminAudit captured(UUID expectedId) {
        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(rollback, org.mockito.Mockito.timeout(2000))
                .rollback(org.mockito.ArgumentMatchers.eq(expectedId), audit.capture());
        return audit.getValue();
    }

    @Test
    void consoleRollbackCarriesReasonUnderTheConsoleMarker() {
        wire();
        UUID target = UUID.randomUUID();

        dispatch(consoleSender(),
                List.of("rollback", target.toString(), "harbor-rollback"));

        AdminAudit audit = captured(target);
        assertNull(audit.operatorId());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.operatorName());
        assertEquals("harbor-rollback", audit.reason());
    }

    @Test
    void omittedRollbackReasonFallsBackToTheDefault() {
        wire();
        UUID target = UUID.randomUUID();

        dispatch(consoleSender(), List.of("rollback", target.toString()));

        AdminAudit audit = captured(target);
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.operatorName());
        assertEquals(AdminAudit.DEFAULT_REASON, audit.reason());
    }
}
