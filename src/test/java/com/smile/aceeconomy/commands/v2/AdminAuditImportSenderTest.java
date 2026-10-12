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
import com.smile.aceeconomy.operations.ImportOutcome;
import com.smile.aceeconomy.operations.ImportReport;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
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
 * Sender-derived audit for the console-only import apply: every applied
 * record is filed under the fixed console marker with the trailing reason.
 * A preview never writes, so it carries no audit.
 *
 * <p>Dispatch goes through the real registry so the tail-shape handling
 * (currency, apply confirm, reason) is exercised with the handover.</p>
 */
class AdminAuditImportSenderTest {

    private ImportCommandService imports;
    private CommandServices services;

    private void wire() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("coin");
        when(economy.resolveCurrency("coin")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("coin", "Coin", "C", 2, true)));
        when(economy.knownCurrencyIds()).thenReturn(List.of("coin"));

        imports = mock(ImportCommandService.class);
        ImportOutcome applied = new ImportOutcome(false, "pre-import-id",
                new ImportReport(false, 1, 0, 0, true, List.of()), List.of());
        when(imports.apply(any(ImportSource.class), anyString(), anyString(),
                any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(applied));
        when(imports.apply(any(ImportSource.class), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(applied));

        services = new CommandServices(economy, mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class), mock(RollbackCommandService.class),
                mock(BackupCommandService.class), imports);
    }

    private Sender consoleSender() {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin.import");
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
    void consoleApplyCarriesReasonUnderTheConsoleMarker() {
        wire();

        dispatch(consoleSender(), List.of("import", "cmi", "balances.csv",
                "coin", "apply", "confirm", "harbor-import"));

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(imports, org.mockito.Mockito.timeout(2000)).apply(
                eq(ImportSource.CMI), eq("balances.csv"), eq("coin"), audit.capture());
        assertNull(audit.getValue().operatorId());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.getValue().operatorName());
        assertEquals("harbor-import", audit.getValue().reason());
    }
}
