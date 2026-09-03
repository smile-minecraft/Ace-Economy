package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.domain.TransactionType;
import com.smile.aceeconomy.operations.AuditPage;
import com.smile.aceeconomy.operations.AuditQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the {@code /aceeco history [player] [currency] [page]} admin surface:
 * spec registration, permission node, sender routing, argument validation and the typed
 * query handed to the history boundary.
 */
class HistoryCommandTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);

    private EconomyCommandService economy() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.resolveCurrency("token")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("token", "Token", "T", 0, false)));
        return economy;
    }

    private PlayerLookupService playersResolving(String name, UUID uuid) {
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve(name)).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(uuid, name, true))));
        return players;
    }

    private HistoryQueryService historyReturning(AuditPage page) {
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(org.mockito.ArgumentMatchers.any(AuditQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(page));
        return history;
    }

    private CommandServices services(EconomyCommandService economy, PlayerLookupService players,
                                     HistoryQueryService history) {
        return new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                history,
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender consoleSender(boolean subPermission) {
        return consoleSender(true, subPermission);
    }

    private Sender consoleSender(boolean rootPermission, boolean subPermission) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(rootPermission).when(sender).hasPermission("aceeconomy.admin");
        doReturn(subPermission).when(sender).hasPermission("aceeconomy.admin.history");
        return sender;
    }

    private Sender playerSender(boolean subPermission) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(subPermission).when(sender).hasPermission("aceeconomy.admin.history");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        return sender;
    }

    private Transaction tx(UUID id, UUID account) {
        return new Transaction(id, account, null, "dollar", Amount.of(10, 2), TransactionType.DEPOSIT,
                Amount.of(990, 2), Amount.of(1000, 2), T, "bonus");
    }

    @Test
    void aceecoSpecRegistersHistoryWithAdminPermissionAndArgumentBounds() {
        CommandServices services = services(economy(), mock(PlayerLookupService.class),
                mock(HistoryQueryService.class));

        CommandSpec aceeco = AceEcoCommandSpec.create(services);
        var history = aceeco.findSubCommand("history");

        assertNotNull(history, "/aceeco must expose a history subcommand");
        assertEquals("aceeconomy.admin.history", history.permission());
        assertEquals(0, history.minArgs());
        assertEquals(3, history.maxArgs());
        assertFalse(history.playerOnly());
        assertFalse(history.consoleOnly());

        CommandSpec registered = V2CommandRegistry.create(services).specs().stream()
                .filter(spec -> spec.name().equals("aceeco"))
                .findFirst().orElseThrow();
        assertNotNull(registered.findSubCommand("history"),
                "the registered aceeco spec must contain history");
    }

    @Test
    void consoleDispatchQueriesHistoryAndRepliesSynchronously() {
        UUID account = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(
                List.of(tx(UUID.randomUUID(), account)), 1, 0, 10));
        CommandServices services = services(economy(),
                playersResolving("Alex", account), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history", "Alex"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).send(org.mockito.ArgumentMatchers.any(Sender.class), message.capture());
        assertTrue(message.getValue().contains("Alex"), "reply should identify the target: "
                + message.getValue());
        assertTrue(message.getValue().contains("DEPOSIT"), "reply should list the transaction type");
    }

    @Test
    void playerDispatchRepliesThroughTheFoliaSafeAsyncPath() {
        UUID account = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(
                List.of(tx(UUID.randomUUID(), account)), 1, 0, 10));
        CommandServices services = services(economy(),
                playersResolving("Alex", account), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        Sender sender = playerSender(true);
        registry.dispatch(sender, "aceeco", List.of("history", "Alex"));

        verify(sink).sendPlayerAsync(org.mockito.ArgumentMatchers.same(sender.asPlayer()),
                org.mockito.ArgumentMatchers.contains("Alex"));
    }

    @Test
    void dispatchWithoutSubcommandPermissionIsRejectedAsTypedNoPermissionError() {
        CommandServices services = services(economy(), mock(PlayerLookupService.class),
                historyReturning(new AuditPage(List.of(), 0, 0, 10)));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(false), "aceeco", List.of("history"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        assertEquals("ACELIB-CMD-003", ((CommandException) error.getValue()).getCode());
    }

    @Test
    void unknownPlayerIsReportedThroughTheTypedErrorPath() {
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Ghost")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        CommandServices services = services(economy(), players,
                historyReturning(new AuditPage(List.of(), 0, 0, 10)));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history", "Ghost"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        assertEquals("ACELIB-CMD-ACCOUNT-NOT-FOUND", ((CommandException) error.getValue()).getCode());
    }

    @Test
    void emptyResultPageIsAnsweredWithAnExplicitNoTransactionsMessage() {
        CommandServices services = services(economy(), mock(PlayerLookupService.class),
                historyReturning(new AuditPage(List.of(), 0, 3, 10)));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).send(org.mockito.ArgumentMatchers.any(Sender.class), message.capture());
        assertTrue(message.getValue().contains("history.empty"),
                "empty page must be explicit via localized key, got: " + message.getValue());
    }

    @Test
    void invalidPageArgumentsAreRejectedWithATypedError() {
        for (String bad : List.of("-1", "abc", "1.5")) {
            CommandServices services = services(economy(), mock(PlayerLookupService.class),
                    historyReturning(new AuditPage(List.of(), 0, 0, 10)));
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(consoleSender(true), "aceeco", List.of("history", "Alex", "dollar", bad));

            ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
            verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
            assertTrue(error.getValue() instanceof CommandException,
                    "page '" + bad + "' must fail as a command error");
            assertEquals("ACELIB-CMD-INVALID-PAGE",
                    ((CommandException) error.getValue()).getCode(),
                    "page '" + bad + "' must map to the invalid-page code");
        }
    }

    @Test
    void handlerBuildsTheTypedQueryFromResolvedPlayerCurrencyAndPage() {
        UUID account = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 5, 2, 10));
        CommandServices services = services(economy(), playersResolving("Alex", account), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco",
                List.of("history", "Alex", "token", "2"));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history).query(query.capture());
        assertEquals(account, query.getValue().accountId());
        assertEquals("token", query.getValue().currencyId());
        assertEquals(2, query.getValue().page());
        assertEquals(10, query.getValue().limit());
    }

    @Test
    void omittedCurrencyFallsBackToTheDefaultCurrencyInTheTypedQuery() {
        UUID account = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), playersResolving("Alex", account), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history", "Alex"));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history).query(query.capture());
        assertEquals(account, query.getValue().accountId());
        assertEquals("dollar", query.getValue().currencyId());
        assertEquals(0, query.getValue().page());
        assertEquals(10, query.getValue().limit());
    }

    @Test
    void exceptionalPlayerLookupIsReportedAsFailureAndNeverQueriesHistory() {
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Alex")).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("lookup boom")));
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), players, history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history", "Alex"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        assertEquals("lookup boom", error.getValue().getMessage(),
                "the lookup failure must reach the typed error reply");
        verify(history, org.mockito.Mockito.never())
                .query(org.mockito.ArgumentMatchers.any(AuditQuery.class));
        verify(sink, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(Sender.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exceptionalHistoryQueryIsReportedAsFailureWithoutFakeSuccess() {
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(org.mockito.ArgumentMatchers.any(AuditQuery.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage boom")));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender(true), "aceeco", List.of("history"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        assertEquals("storage boom", error.getValue().getMessage(),
                "the query failure must reach the typed error reply");
        verify(sink, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(Sender.class),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingRootPermissionRejectsHistoryEvenWhenChildPermissionIsPresent() {
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        // Root aceeconomy.admin is absent while the child node claims true:
        // the dispatcher must still reject at the root check.
        registry.dispatch(consoleSender(false, true), "aceeco", List.of("history"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(org.mockito.ArgumentMatchers.any(Sender.class), error.capture());
        assertEquals("ACELIB-CMD-003", ((CommandException) error.getValue()).getCode());
        verify(history, org.mockito.Mockito.never())
                .query(org.mockito.ArgumentMatchers.any(AuditQuery.class));
    }
}
