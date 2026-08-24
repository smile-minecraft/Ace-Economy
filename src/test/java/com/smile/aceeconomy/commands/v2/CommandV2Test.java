package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.acelib.command.CommandSpec;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.domain.Amount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class CommandV2Test {

    @Test
    void buildsAndRegistersTheSixCommandSpecs() {
        CommandServices services = new CommandServices(
                mock(EconomyCommandService.class),
                mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class),
                mock(LeaderboardQueryService.class),
                mock(BankCommandService.class),
                mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
        V2CommandRegistry commands = V2CommandRegistry.create(services);
        CommandRegistry registry = mock(CommandRegistry.class);

        commands.register(registry);

        assertEquals(List.of("money", "pay", "withdraw", "baltop", "bank", "aceeco"),
                commands.specs().stream().map(CommandSpec::name).toList());
        commands.specs().forEach(spec -> {
            assertFalse(spec.subCommands().isEmpty());
            assertNotNull(spec.permission());
        });
        verify(registry).register(commands.specs().get(0));
    }

    @Test
    void exposesPermissionAndSenderPoliciesInTheSpecs() {
        CommandServices services = new CommandServices(
                mock(EconomyCommandService.class),
                mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class),
                mock(LeaderboardQueryService.class),
                mock(BankCommandService.class),
                mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));

        CommandSpec money = MoneyCommandSpec.create(services);
        CommandSpec pay = PayCommandSpec.create(services);
        CommandSpec withdraw = WithdrawCommandSpec.create(services);
        CommandSpec bank = BankCommandSpec.create(services);
        CommandSpec aceeco = AceEcoCommandSpec.create(services);

        assertTrue(money.findSubCommand("balance").permission().startsWith("aceeconomy.command."));
        assertTrue(pay.findSubCommand("send").playerOnly());
        assertTrue(withdraw.findSubCommand("cash").playerOnly());
        assertTrue(bank.findSubCommand("open").playerOnly());
        assertTrue(aceeco.findSubCommand("reload").consoleOnly());
        assertEquals(2, pay.findSubCommand("send").minArgs());
        assertEquals(3, pay.findSubCommand("send").maxArgs());
    }

    @Test
    void mapsDomainFailuresByCodeAndFiltersCompletionsByPrefix() {
        var error = TypedErrors.from(EconomyResult.failure(EconomyError.INSUFFICIENT_FUNDS, "domain wording"));

        assertEquals("ACELIB-CMD-INSUFFICIENT-FUNDS", error.getCode());
        assertEquals(List.of("dollar", "doubloon"),
                CommandCompletion.byPrefix(List.of("dollar", "doubloon", "euro"), "d"));
        assertEquals(List.of(), CommandCompletion.byPrefix(List.of("dollar"), "x"));
    }

    @Test
    void rejectsInvalidAmountsWithoutMessageMatching() {
        assertEquals("ACELIB-CMD-AMOUNT-NON-POSITIVE", assertThrows(CommandException.class,
                () -> AmountParser.parse("0", 2)).getCode());
        assertEquals("ACELIB-CMD-AMOUNT-NON-POSITIVE", assertThrows(CommandException.class,
                () -> AmountParser.parse("-1", 2)).getCode());
        assertEquals("ACELIB-CMD-INVALID-AMOUNT", assertThrows(CommandException.class,
                () -> AmountParser.parse("1.001", 2)).getCode());
        assertEquals("ACELIB-CMD-AMOUNT-OVERFLOW", assertThrows(CommandException.class,
                () -> AmountParser.parse("1000000000000001", 2)).getCode());
        assertEquals(new BigDecimal("12.30"), AmountParser.parse("12.3", 2).value());

        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.resolveCurrency("wat")).thenReturn(java.util.Optional.empty());
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", assertThrows(CommandException.class,
                () -> CurrencyArgResolver.resolve(economy, "wat", "dollar")).getCode());
    }

    @Test
    void routesCompletedPlayerRepliesThroughAceLibReplySink() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(java.util.Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.getBalance(anyUuid(), org.mockito.ArgumentMatchers.eq("dollar")))
                .thenReturn(CompletableFuture.completedFuture(EconomyResult.success(Amount.of(12, 2))));
        CommandServices services = new CommandServices(economy, mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.command.money");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(sender, "money", List.of("balance"));

        verify(sink).sendPlayerAsync(player, "Alex has $12.00 Dollar");
    }

    @Test
    void routesAsyncDomainFailuresAsTypedCommandErrors() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(java.util.Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.getBalance(anyUuid(), org.mockito.ArgumentMatchers.eq("dollar")))
                .thenReturn(CompletableFuture.completedFuture(
                        EconomyResult.failure(EconomyError.ACCOUNT_NOT_FOUND, "account wording")));
        CommandServices services = new CommandServices(economy, mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.command.money");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(sender, "money", List.of("balance"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.same(sender), error.capture());
        assertEquals("ACELIB-CMD-ACCOUNT-NOT-FOUND", ((CommandException) error.getValue()).getCode());
    }

    @Test
    void registryUsesPermissionGatedCompletionForTargetAndCurrency() {
        CommandServices services = new CommandServices(
                mock(EconomyCommandService.class), mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
        when(services.economy().knownCurrencyIds()).thenReturn(List.of("dollar", "euro"));
        when(services.players().onlinePlayerNames()).thenReturn(List.of("Alex", "Bob"));
        Sender sender = mock(Sender.class);
        when(sender.hasPermission("aceeconomy.command.pay")).thenReturn(true);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        assertEquals(List.of("Alex"), registry.tabComplete(sender, "pay", List.of("send", "A")));
        assertEquals(List.of("dollar"), registry.tabComplete(sender, "pay", List.of("send", "Alex", "1", "d")));
        assertEquals(List.of(), registry.tabComplete(mock(Sender.class), "pay", List.of()));
    }

    @Test
    void rejectsDuplicatePrimaryRegistrationAtTheAceLibBoundary() {
        CommandServices services = new CommandServices(
                mock(EconomyCommandService.class), mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry commands = V2CommandRegistry.create(services);
        commands.register(registry);

        assertThrows(IllegalArgumentException.class, () -> registry.register(commands.specs().get(0)));
    }

    private static CommandServices mockedServices() {
        return new CommandServices(
                mock(EconomyCommandService.class), mock(PlayerLookupService.class),
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    @Test
    void leaderboardToggleGatesBaltopSpecCreation() {
        assertTrue(V2CommandRegistry.create(mockedServices(), "aceeco", true, List.of()).specs()
                        .stream().anyMatch(spec -> spec.name().equals("baltop")),
                "enabled leaderboard must keep the baltop spec");
        assertFalse(V2CommandRegistry.create(mockedServices(), "aceeco", false, List.of()).specs()
                        .stream().anyMatch(spec -> spec.name().equals("baltop")),
                "disabled leaderboard must not create an executable baltop spec");
    }

    @Test
    void mainCommandAliasIsWiredOntoTheAceEcoSpecAndResolvable() {
        V2CommandRegistry commands = V2CommandRegistry.create(mockedServices(), "eco", true, List.of());
        CommandSpec aceeco = commands.specs().stream()
                .filter(spec -> spec.name().equals("aceeco")).findFirst().orElseThrow();
        assertEquals(List.of("eco"), aceeco.aliases(), "configured alias must reach the aceeco spec");

        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        commands.register(registry);
        assertEquals(aceeco, registry.findCommand("eco"),
                "the alias label must dispatch to the aceeco spec");
    }

    @Test
    void defaultAliasKeepsSingleEntryPointAndCollisionsAreRejected() {
        // alias equal to the default entry point is a no-op, never a duplicate alias
        V2CommandRegistry defaulted = V2CommandRegistry.create(
                mockedServices(), "aceeco", true, List.of("aceeco"));
        CommandSpec aceeco = defaulted.specs().stream()
                .filter(spec -> spec.name().equals("aceeco")).findFirst().orElseThrow();
        assertEquals(List.of(), aceeco.aliases());

        // sibling spec primary / spec alias / plugin.yml-declared label collisions fail fast
        assertThrows(IllegalArgumentException.class,
                () -> V2CommandRegistry.create(mockedServices(), "money", true, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> V2CommandRegistry.create(mockedServices(), "balance", true, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> V2CommandRegistry.create(mockedServices(), "bankmenu", true,
                        MainCommandAliasPolicy.declaredBukkitLabels(Map.of(
                                "bank", Map.of("aliases", List.of("menu", "bankmenu"))))));
    }

    private static UUID anyUuid() {
        return org.mockito.ArgumentMatchers.any(UUID.class);
    }
}
