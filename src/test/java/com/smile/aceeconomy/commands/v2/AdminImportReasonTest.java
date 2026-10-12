package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.ImportCommandService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.operations.ImportOutcome;
import com.smile.aceeconomy.operations.ImportReport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Reason position for {@code /aceeco import}: currency comes before the
 * {@code apply confirm} pair and the reason always comes last. A preview with
 * {@code [currency reason]} stays a dry run on that currency; an apply with
 * {@code [currency apply confirm reason]} (or {@code [apply confirm reason]} on the
 * default) records the reason. An unknown currency is never guessed as a reason.
 *
 * <p>Skeleton: the trailing reason slot is parsed but the handler still calls the
 * unaudited overloads, so the apply-with-reason handover fails until the audit wiring
 * lands.
 */
class AdminImportReasonTest {

    private ImportCommandService imports;
    private CommandServices services;

    private CommandServices wire() {
        com.smile.aceeconomy.commands.v2.ports.EconomyCommandService economy =
                mock(com.smile.aceeconomy.commands.v2.ports.EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("coin");
        when(economy.resolveCurrency("coin")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("coin", "Coin", "C", 2, true)));
        when(economy.knownCurrencyIds()).thenReturn(List.of("coin"));

        imports = mock(ImportCommandService.class);
        ImportOutcome preview = new ImportOutcome(true, null,
                new ImportReport(true, 2, 0, 0, true, List.of()), List.of());
        ImportOutcome applied = new ImportOutcome(false, "pre-import-id",
                new ImportReport(false, 2, 0, 0, true, List.of()), List.of());
        when(imports.preview(any(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(preview));
        when(imports.apply(any(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(applied));
        when(imports.apply(any(), anyString(), anyString(), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(applied));

        return new CommandServices(economy,
                mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class),
                imports);
    }

    private Sender consoleSender() {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin.import");
        return sender;
    }

    private void dispatch(CommandServices services, List<String> args, ReplySink sink) {
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(consoleSender(), "aceeco", args);
    }

    @Test
    void previewWithCurrencyAndReasonStaysADryRunOnThatCurrency() {
        CommandServices services = wire();
        ReplySink sink = mock(ReplySink.class);

        dispatch(services, List.of("import", "cmi", "balances.csv", "coin", "harbor-import"), sink);

        // Preview never writes: it stays on the unaudited preview path with the explicit
        // currency, and the reason is validated but not persisted. Skeleton ignores the
        // reason entirely, so this locks the currency parsing while the audit handover
        // for apply is verified separately.
        verify(imports, org.mockito.Mockito.timeout(2000)).preview(
                com.smile.aceeconomy.ports.operations.ImportSource.CMI, "balances.csv", "coin");
        verify(imports, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void applyWithCurrencyAndReasonRecordsTheReason() {
        CommandServices services = wire();
        ReplySink sink = mock(ReplySink.class);

        dispatch(services, List.of("import", "cmi", "balances.csv", "coin",
                "apply", "confirm", "harbor-import"), sink);

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(imports, org.mockito.Mockito.timeout(2000)).apply(
                eq(com.smile.aceeconomy.ports.operations.ImportSource.CMI),
                eq("balances.csv"), eq("coin"), audit.capture());
        assertEquals("harbor-import", audit.getValue().reason(),
                "the trailing token after apply confirm is the reason");
    }

    @Test
    void unknownCurrencyIsStillUnknownWhenAReasonFollows() {
        CommandServices services = wire();
        ReplySink sink = mock(ReplySink.class);

        dispatch(services, List.of("import", "cmi", "balances.csv", "nosuchcoin", "harbor-import"),
                sink);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink, org.mockito.Mockito.timeout(2000))
                .sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY",
                ((CommandException) error.getValue()).getCode());
        verify(imports, never()).preview(any(), anyString(), anyString());
        verify(imports, never()).apply(any(), anyString(), anyString());
    }
}
