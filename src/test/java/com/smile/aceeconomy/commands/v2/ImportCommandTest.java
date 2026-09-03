package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.ImportCommandService;
import com.smile.aceeconomy.operations.ImportOutcome;
import com.smile.aceeconomy.operations.ImportReport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Contract tests for the {@code /aceeco import} admin surface: console-only
 * policy, dual permission, dry-run by default, explicit {@code apply confirm}
 * for writes, and typed result mapping. Dispatched through the real registry.
 */
class ImportCommandTest {

    private ImportCommandService importFacade;

    private EconomyCommandService economy() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("coin");
        when(economy.resolveCurrency("coin")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("coin", "Coin", "C", 2, true)));
        return economy;
    }

    private CommandServices services(EconomyCommandService economy) {
        importFacade = mock(ImportCommandService.class);
        return new CommandServices(economy,
                mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class),
                importFacade);
    }

    private Sender consoleSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.import");
        return sender;
    }

    private Sender playerSender(boolean rootPermission, boolean childPermission) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(childPermission).when(sender).hasPermission("aceeconomy.admin.import");
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

    private static ImportOutcome previewOutcome() {
        return new ImportOutcome(true, null,
                new ImportReport(true, 2, 0, 0, true, List.of()), List.of());
    }

    private static ImportOutcome appliedOutcome() {
        return new ImportOutcome(false, "pre-import-id",
                new ImportReport(false, 2, 0, 0, true, List.of()), List.of());
    }

    // ---------------- surface ----------------

    @Test
    void aceecoSpecRegistersImportConsoleOnlyWithItsPermission() {
        CommandServices services = services(economy());

        CommandSpec aceeco = AceEcoCommandSpec.create(services);
        var importSpec = aceeco.findSubCommand("import");

        assertNotNull(importSpec, "/aceeco must expose an import subcommand");
        assertEquals("aceeconomy.admin.import", importSpec.permission());
        assertEquals(2, importSpec.minArgs(), "import requires <source> <path>");
        assertEquals(5, importSpec.maxArgs(), "import accepts <source> <path> [currency] [apply confirm]");
        assertTrue(importSpec.consoleOnly(), "import writes balances and must be console-only");

        CommandSpec registered = V2CommandRegistry.create(services).specs().stream()
                .filter(spec -> spec.name().equals("aceeco"))
                .findFirst().orElseThrow();
        assertNotNull(registered.findSubCommand("import"));
    }

    // ---------------- sender / permission gates ----------------

    @Test
    void playerDispatchIsRejectedBeforeTheFacadeIsCalled() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(playerSender(true, true), "aceeco",
                List.of("import", "essentials", "userdata"));

        assertEquals("ACELIB-CMD-005", ((CommandException) capturedError(sink)).getCode());
        verify(importFacade, never()).preview(any(), anyString(), anyString());
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void missingChildPermissionRejectsImport() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, false), "aceeco",
                List.of("import", "essentials", "userdata"));

        assertEquals("ACELIB-CMD-003", ((CommandException) capturedError(sink)).getCode());
        verify(importFacade, never()).preview(any(), anyString(), anyString());
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    // ---------------- source / confirmation gates ----------------

    @Test
    void unknownSourceNeverReachesTheFacade() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "vault", "userdata"));

        assertEquals("ACELIB-CMD-IMPORT-SOURCE-UNKNOWN",
                ((CommandException) capturedError(sink)).getCode());
        verify(importFacade, never()).preview(any(), anyString(), anyString());
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void applyWithoutConfirmNeverReachesTheFacade() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "essentials", "userdata", "apply"));

        assertEquals("ACELIB-CMD-IMPORT-CONFIRM-REQUIRED",
                ((CommandException) capturedError(sink)).getCode());
        verify(importFacade, never()).preview(any(), anyString(), anyString());
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void wrongConfirmLiteralNeverReachesTheFacade() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "essentials", "userdata", "apply", "yes"));

        assertEquals("ACELIB-CMD-IMPORT-CONFIRM-REQUIRED",
                ((CommandException) capturedError(sink)).getCode());
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    // ---------------- dry-run by default ----------------

    @Test
    void bareImportIsADryRunPreview() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(importFacade.preview(
                eq(com.smile.aceeconomy.ports.operations.ImportSource.ESSENTIALS),
                eq("userdata"), eq("coin")))
                .thenReturn(CompletableFuture.completedFuture(previewOutcome()));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "essentials", "userdata"));

        verify(importFacade).preview(
                com.smile.aceeconomy.ports.operations.ImportSource.ESSENTIALS, "userdata", "coin");
        verify(importFacade, never()).apply(any(), anyString(), anyString());
        assertTrue(capturedReply(sink).contains("2"), "preview reply must carry counts");
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void explicitCurrencyStaysADryRun() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(importFacade.preview(any(), anyString(), eq("coin")))
                .thenReturn(CompletableFuture.completedFuture(previewOutcome()));

        // "token" is unknown to this economy mock, so use the known coin id explicitly.
        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "cmi", "balances.csv", "coin"));

        verify(importFacade).preview(
                com.smile.aceeconomy.ports.operations.ImportSource.CMI, "balances.csv", "coin");
        verify(importFacade, never()).apply(any(), anyString(), anyString());
    }

    @Test
    void applyConfirmReachesApply() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(importFacade.apply(
                eq(com.smile.aceeconomy.ports.operations.ImportSource.ESSENTIALS),
                eq("userdata"), eq("coin")))
                .thenReturn(CompletableFuture.completedFuture(appliedOutcome()));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "essentials", "userdata", "apply", "confirm"));

        verify(importFacade).apply(
                com.smile.aceeconomy.ports.operations.ImportSource.ESSENTIALS, "userdata", "coin");
        verify(importFacade, never()).preview(any(), anyString(), anyString());
        String reply = capturedReply(sink);
        assertTrue(reply.contains("pre-import-id"), "apply reply must name the safety backup: " + reply);
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void applyWithCurrencyReachesApply() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(importFacade.apply(any(), anyString(), eq("coin")))
                .thenReturn(CompletableFuture.completedFuture(appliedOutcome()));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "cmi", "balances.csv", "coin", "apply", "confirm"));

        verify(importFacade).apply(
                com.smile.aceeconomy.ports.operations.ImportSource.CMI, "balances.csv", "coin");
    }

    // ---------------- typed failures ----------------

    @Test
    void facadeFailureMapsToStableCodeWithoutSuccess() {
        CommandServices services = services(economy());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = registryFor(services, sink);
        when(importFacade.preview(any(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new com.smile.aceeconomy.operations.ImportException(
                        com.smile.aceeconomy.operations.ImportFailureReason.PATH_REJECTED,
                        "path escapes the import directory")));

        registry.dispatch(consoleSender(true, true), "aceeco",
                List.of("import", "essentials", "../config.yml"));

        assertEquals("ACELIB-CMD-IMPORT-PATH-REJECTED",
                ((CommandException) capturedError(sink)).getCode());
        verify(sink, never()).send(any(Sender.class), anyString());
    }
}
