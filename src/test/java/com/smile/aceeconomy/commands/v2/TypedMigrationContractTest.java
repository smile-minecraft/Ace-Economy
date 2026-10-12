package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.application.TransferResult;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * typed-argument migration contract: every player-visible error code and message produced by the v2
 * command argument surface, locked through the real registry before the typed-argument
 * migration. Each test dispatches exactly what a player or console would send and asserts
 * the code (and the fallback message with no message service) so the migration must
 * reproduce them one by one. Covers the TDD-required gates: bad amounts, unknown
 * currency, blank-currency fallback, unknown/async-failed player lookups, bad pages,
 * console-only and player-only sender gates, and tokens outside any charset gate.
 */
class TypedMigrationContractTest {

    private static final CommandModels.CurrencyInfo DOLLAR =
            new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true);

    private EconomyCommandService economy() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(DOLLAR));
        when(economy.resolveCurrency("wat")).thenReturn(Optional.empty());
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar", "euro"));
        return economy;
    }

    private CommandServices services(EconomyCommandService economy, PlayerLookupService players,
                                     HistoryQueryService history) {
        return new CommandServices(economy, players,
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class), history,
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender playerSender(String... permissions) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        for (String permission : permissions) {
            doReturn(true).when(sender).hasPermission(permission);
        }
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        return sender;
    }

    private Sender consoleSender(String... permissions) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        for (String permission : permissions) {
            doReturn(true).when(sender).hasPermission(permission);
        }
        return sender;
    }

    private CommandException capturedError(ReplySink sink) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException,
                "expected a typed command error, got: " + error.getValue());
        return (CommandException) error.getValue();
    }

    // ---------------- amounts ----------------

    @Test
    void payRejectsNonNumericAmount() {
        EconomyCommandService economy = economy();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Bob", "bogus"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-INVALID-AMOUNT", error.getCode());
        assertEquals("general.invalid-amount", error.getMessage());
    }

    @Test
    void payRejectsExcessScaleZeroNegativeAndOverflowAmounts() {
        for (String raw : List.of("1.001", "0", "-5", "1000000000000001")) {
            EconomyCommandService economy = economy();
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                    mock(HistoryQueryService.class))).register(registry);

            registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                    "pay", List.of("send", "Bob", raw));

            CommandException error = capturedError(sink);
            String expected = raw.equals("1.001") ? "ACELIB-CMD-INVALID-AMOUNT"
                    : raw.equals("1000000000000001") ? "ACELIB-CMD-AMOUNT-OVERFLOW"
                    : "ACELIB-CMD-AMOUNT-NON-POSITIVE";
            assertEquals(expected, error.getCode(), "raw=" + raw);
        }
    }

    @Test
    void giveRejectsBadAmountThroughTheSharedParser() {
        EconomyCommandService economy = economy();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.give"),
                "aceeco", List.of("give", "Bob", "nope"));

        assertEquals("ACELIB-CMD-INVALID-AMOUNT", capturedError(sink).getCode());
    }

    @Test
    void withdrawRejectsBadAmount() {
        EconomyCommandService economy = economy();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.withdraw", "aceeconomy.command.withdraw"),
                "withdraw", List.of("cash", "-1"));

        assertEquals("ACELIB-CMD-AMOUNT-NON-POSITIVE", capturedError(sink).getCode());
    }

    // ---------------- currency ----------------

    @Test
    void payRejectsUnknownCurrency() {
        EconomyCommandService economy = economy();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Bob", "10", "wat"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", error.getCode());
        assertEquals("general.unknown-currency", error.getMessage());
    }

    @Test
    void omittedCurrencyFallsBackToTheDefault() {
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        UUID bob = UUID.randomUUID();
        when(players.resolve("Bob")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(bob, "Bob", true))));
        UUID self = UUID.randomUUID();
        Sender sender = playerSender("aceeconomy.command.pay", "aceeconomy.command.pay");
        when(sender.asPlayer().getUniqueId()).thenReturn(self);
        TransferResult result = new TransferResult(self, bob, Amount.of(90, 2),
                Amount.of(10, 2), UUID.randomUUID(), UUID.randomUUID());
        when(economy.transfer(eq(self), eq(bob), eq("dollar"), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(EconomyResult.success(result)));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players,
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(sender, "pay", List.of("send", "Bob", "10"));

        verify(economy).transfer(eq(self), eq(bob), eq("dollar"), any(Amount.class));
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void slashTokensReachHandlerValidationInsteadOfAPreCheck() {
        EconomyCommandService economy = economy();
        when(economy.resolveCurrency("a/b")).thenReturn(Optional.empty());
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Bob", "10", "a/b"));

        // The compat path has no charset pre-check: a slash token still arrives at the
        // currency validation and reports the domain code, not a platform rejection.
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", capturedError(sink).getCode());
    }

    // ---------------- players ----------------

    @Test
    void payReportsUnknownPlayer() {
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Zed")).thenReturn(
                CompletableFuture.completedFuture(Optional.empty()));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players,
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Zed", "10"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-ACCOUNT-NOT-FOUND", error.getCode());
        assertEquals("general.player-not-found", error.getMessage());
    }

    @Test
    void exceptionalPlayerLookupPassesThroughAndNeverTouchesTheEconomy() {
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Bob")).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("lookup boom")));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players,
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Bob", "10"));

        // A failed lookup reaches the reply untouched (never re-typed): same passthrough
        // the history surface asserts, so only the message is locked here.
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), failure.capture());
        assertEquals("lookup boom", failure.getValue().getMessage());
        verify(economy, never()).transfer(any(), any(), anyString(), any());
    }

    @Test
    void moneyShowsSelfBalanceButResolvesOtherPlayers() {
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        UUID bob = UUID.randomUUID();
        when(players.resolve("Bob")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(bob, "Bob", true))));
        when(economy.getBalance(any(UUID.class), eq("dollar"))).thenReturn(
                CompletableFuture.completedFuture(EconomyResult.success(Amount.of(12, 2))));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players,
                mock(HistoryQueryService.class))).register(registry);
        Sender sender = playerSender("aceeconomy.command.money", "aceeconomy.command.money");

        registry.dispatch(sender, "money", List.of("balance"));
        ArgumentCaptor<String> self = ArgumentCaptor.forClass(String.class);
        verify(sink).sendPlayerAsync(same(sender.asPlayer()), self.capture());
        assertTrue(self.getValue().contains("economy.balance-check"), self.getValue());
        assertTrue(self.getValue().contains("Alex"), self.getValue());

        registry.dispatch(sender, "money", List.of("balance", "Bob"));
        ArgumentCaptor<String> other = ArgumentCaptor.forClass(String.class);
        verify(sink, org.mockito.Mockito.atLeast(2))
                .sendPlayerAsync(same(sender.asPlayer()), other.capture());
        assertTrue(other.getAllValues().get(other.getAllValues().size() - 1).contains("Bob"));
    }

    // ---------------- pages ----------------

    @Test
    void historyRejectsNonIntegerAndNegativePages() {
        for (String raw : List.of("abc", "-1")) {
            EconomyCommandService economy = economy();
            PlayerLookupService players = mock(PlayerLookupService.class);
            UUID alex = UUID.randomUUID();
            when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                    Optional.of(new CommandModels.PlayerIdentity(alex, "Alex", true))));
            HistoryQueryService history = mock(HistoryQueryService.class);
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services(economy, players, history)).register(registry);

            registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.history"),
                    "aceeco", List.of("history", "Alex", "dollar", raw));

            CommandException error = capturedError(sink);
            assertEquals("ACELIB-CMD-INVALID-PAGE", error.getCode(), "raw=" + raw);
            assertEquals("command.invalid-page", error.getMessage(), "raw=" + raw);
            verify(history, never()).query(any(AuditQuery.class));
        }
    }

    @Test
    void historyPassesAValidPageThrough() {
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        UUID alex = UUID.randomUUID();
        when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(alex, "Alex", true))));
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(
                CompletableFuture.completedFuture(new AuditPage(List.of(), 2, 0, 10)));
        when(history.callbackExecutor()).thenReturn(Runnable::run);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players, history)).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.history"),
                "aceeco", List.of("history", "Alex", "dollar", "2"));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history).query(query.capture());
        assertEquals(2, query.getValue().page());
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void historyReportsUnknownCurrencyBeforeAnInvalidPage() {
        // Intentional ordering difference, not a regression: the legacy handler parsed the page
        // first and replied ACELIB-CMD-INVALID-PAGE for this input. The typed surface parses
        // arguments in declaration order (player, currency, page), so the unknown currency wins.
        // A single bad input still gets the same reply on both paths; only this combined case differs.
        EconomyCommandService economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        UUID alex = UUID.randomUUID();
        when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(alex, "Alex", true))));
        HistoryQueryService history = mock(HistoryQueryService.class);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, players, history)).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.history"),
                "aceeco", List.of("history", "Alex", "wat", "abc"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", error.getCode());
        assertEquals("general.unknown-currency", error.getMessage());
        verify(history, never()).query(any(AuditQuery.class));
    }

    // ---------------- spec shape ----------------

    @Test
    void subcommandArgNamesAndCooldownDefaultsAreLocked() {
        CommandServices services = services(economy(), mock(PlayerLookupService.class),
                mock(HistoryQueryService.class));
        CommandSpec money = MoneyCommandSpec.create(services);
        assertEquals(List.of("player", "currency"),
                money.findSubCommand("balance").argNames());
        assertEquals(List.of("player", "amount", "currency"),
                PayCommandSpec.create(services).findSubCommand("send").argNames());
        assertEquals(List.of("amount", "currency"),
                WithdrawCommandSpec.create(services).findSubCommand("cash").argNames());
        assertEquals(List.of("currency"),
                BaltopCommandSpec.create(services).findSubCommand("top").argNames());
        CommandSpec aceeco = AceEcoCommandSpec.create(services);
        assertEquals(List.of("player", "amount", "currency"),
                aceeco.findSubCommand("give").argNames());
        assertEquals(List.of("player", "currency", "page"),
                aceeco.findSubCommand("history").argNames());
        assertEquals(List.of("transaction-id"), aceeco.findSubCommand("rollback").argNames());
        assertEquals(List.of("label"), aceeco.findSubCommand("backup").argNames());
        assertEquals(List.of("backup-id", "confirm"), aceeco.findSubCommand("restore").argNames());
        assertEquals(List.of("source", "path", "currency", "apply", "confirm"),
                aceeco.findSubCommand("import").argNames());
        // No subcommand configures a cooldown today; the typed builders must not add one.
        for (CommandSpec root : List.of(money, aceeco)) {
            for (var sub : root.subCommands().values()) {
                assertEquals(0L, sub.cooldownMillis(), "sub=" + sub.name());
            }
        }
    }

    // ---------------- sender gates ----------------

    @Test
    void consoleCannotUsePlayerOnlyCommands() {
        EconomyCommandService economy = economy();
        CommandServices services = services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender("aceeconomy.command.pay", "aceeconomy.command.pay"),
                "pay", List.of("send", "Bob", "10"));
        assertEquals("ACELIB-CMD-004", capturedError(sink).getCode());

        ReplySink sink2 = mock(ReplySink.class);
        CommandRegistryImpl registry2 = new CommandRegistryImpl(sink2);
        V2CommandRegistry.create(services).register(registry2);
        registry2.dispatch(consoleSender("aceeconomy.command.withdraw", "aceeconomy.command.withdraw"),
                "withdraw", List.of("cash", "10"));
        assertEquals("ACELIB-CMD-004", capturedError(sink2).getCode());

        ReplySink sink3 = mock(ReplySink.class);
        CommandRegistryImpl registry3 = new CommandRegistryImpl(sink3);
        V2CommandRegistry.create(services).register(registry3);
        registry3.dispatch(consoleSender("aceeconomy.command.bank", "aceeconomy.command.bank"),
                "bank", List.of("open"));
        assertEquals("ACELIB-CMD-004", capturedError(sink3).getCode());
    }

    @Test
    void playersCannotUseConsoleOnlyCommands() {
        EconomyCommandService economy = economy();
        CommandServices services = services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        Sender player = playerSender("aceeconomy.admin", "aceeconomy.admin.rollback",
                "aceeconomy.admin.restore", "aceeconomy.admin.import", "aceeconomy.admin.reload");

        registry.dispatch(player, "aceeco", List.of("rollback", UUID.randomUUID().toString()));
        assertEquals("ACELIB-CMD-005", capturedError(sink).getCode());
    }

    @Test
    void rollbackRejectsAMalformedTransactionId() {
        EconomyCommandService economy = economy();
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services(economy, mock(PlayerLookupService.class),
                mock(HistoryQueryService.class))).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.rollback"),
                "aceeco", List.of("rollback", "not-a-uuid"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-INVALID-UUID", error.getCode());
        assertEquals("command.invalid-uuid", error.getMessage());
    }
}
