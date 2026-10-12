package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.kyori.adventure.text.Component;

import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the {@code /money history [currency] [page]} player surface:
 * spec registration, permission separation from {@code /aceeco history}, self-only
 * querying, query parity with the admin path, the async reply path and sender gates.
 */
class MoneyHistoryCommandTest {

    private static final Instant T = Instant.ofEpochMilli(1_700_000_000_000L);

    private com.smile.aceeconomy.commands.v2.ports.EconomyCommandService economy() {
        var economy = mock(com.smile.aceeconomy.commands.v2.ports.EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency(any(String.class))).thenReturn(Optional.empty());
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.resolveCurrency("token")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("token", "Token", "T", 0, false)));
        return economy;
    }

    private CommandServices services(
            com.smile.aceeconomy.commands.v2.ports.EconomyCommandService economy,
            PlayerLookupService players, HistoryQueryService history) {
        return new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                history,
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private HistoryQueryService historyReturning(AuditPage page) {
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(page));
        when(history.callbackExecutor()).thenReturn(Runnable::run);
        return history;
    }

    private Sender playerSender(UUID selfId, String selfName, String... permissions) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        for (String permission : permissions) {
            doReturn(true).when(sender).hasPermission(permission);
        }
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(selfId);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn(selfName);
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

    private Transaction tx(UUID id, UUID account) {
        return new Transaction(id, account, null, "dollar", Amount.of(10, 2), TransactionType.DEPOSIT,
                Amount.of(990, 2), Amount.of(1000, 2), T, "bonus");
    }

    @Test
    void moneySpecRegistersHistoryWithSeparatedPlayerPermission() {
        CommandServices services = services(economy(), mock(PlayerLookupService.class),
                mock(HistoryQueryService.class));

        CommandSpec money = MoneyCommandSpec.create(services);
        var history = money.findSubCommand("history");

        assertNotNull(history, "/money must expose a history subcommand");
        assertEquals("aceeconomy.command.history", history.permission());
        assertTrue(history.playerOnly(), "player history must be player-only");
        assertFalse(history.consoleOnly());
        assertEquals(0, history.minArgs());
        assertEquals(2, history.maxArgs());
        assertEquals(List.of("currency", "page"), history.argNames(),
                "player history must not expose any player-addressing argument");

        CommandSpec registered = V2CommandRegistry.create(services).specs().stream()
                .filter(spec -> spec.name().equals("money"))
                .findFirst().orElseThrow();
        assertNotNull(registered.findSubCommand("history"),
                "the registered money spec must contain history");
    }

    @Test
    void missingHistoryPermissionIsRejectedWithoutQuery() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex", "aceeconomy.command.money"),
                "money", List.of("history"));

        assertEquals("ACELIB-CMD-003", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void missingMoneyRootPermissionIsRejectedWithoutQuery() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex", "aceeconomy.command.history"),
                "money", List.of("history"));

        assertEquals("ACELIB-CMD-003", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void adminHistoryPermissionDoesNotGrantPlayerHistory() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.admin.history"),
                "money", List.of("history"));

        assertEquals("ACELIB-CMD-003", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void playerHistoryPermissionDoesNotGrantAdminHistory() {
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(
                playerSender(UUID.randomUUID(), "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "aceeco", List.of("history"));

        assertEquals("ACELIB-CMD-003", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void selfQueryUsesTheExecutorUuidAndNeverResolvesAPlayer() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(
                new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10));
        PlayerLookupService players = mock(PlayerLookupService.class);
        CommandServices services = services(economy(), players, history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        Sender sender = playerSender(self, "Alex",
                "aceeconomy.command.money", "aceeconomy.command.history");
        registry.dispatch(sender, "money", List.of("history"));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history).query(query.capture());
        assertEquals(self, query.getValue().accountId(),
                "player history must query the executor's own account");
        verify(players, never()).resolve(any(String.class));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).sendPlayerAsync(any(PlayerHandle.class), message.capture());
        assertTrue(message.getValue().contains("DEPOSIT"), "reply should list the transaction type");
    }

    @Test
    void extraArgumentsAreRejectedWithoutQuery() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history", "dollar", "0", "extra"));

        // AceLib's own arity gate rejects the surplus before the handler runs;
        // the handler keeps its explicit guard as a second line of defense.
        // Either way the surplus is an explicit error, never silently ignored.
        assertEquals("ACELIB-CMD-001", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void aPlayerNameTokenIsNotTreatedAsALookup() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        PlayerLookupService players = mock(PlayerLookupService.class);
        CommandServices services = services(economy(), players, history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history", "Bob"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", error.getCode());
        assertEquals("general.unknown-currency", error.getMessage());
        verify(history, never()).query(any(AuditQuery.class));
        verify(players, never()).resolve(any(String.class));
    }

    @Test
    void playerAndAdminPathsBuildTheSameQuery() {
        UUID alex = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 5, 2, 10));
        var economy = economy();
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(alex, "Alex", true))));
        CommandServices services = services(economy, players, history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.history"),
                "aceeco", List.of("history", "Alex", "token", "2"));
        registry.dispatch(playerSender(alex, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history", "token", "2"));

        ArgumentCaptor<AuditQuery> queries = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history, org.mockito.Mockito.times(2)).query(queries.capture());
        AuditQuery admin = queries.getAllValues().get(0);
        AuditQuery player = queries.getAllValues().get(1);
        assertEquals(admin.accountId(), player.accountId());
        assertEquals(admin.currencyId(), player.currencyId());
        assertEquals(admin.page(), player.page());
        assertEquals(admin.limit(), player.limit());
        assertEquals(admin.ascending(), player.ascending());
        assertEquals(admin.counterparty(), player.counterparty());
        assertEquals(admin.types(), player.types());
        assertEquals(admin.reasonContains(), player.reasonContains());
        assertEquals(admin.from(), player.from());
        assertEquals(admin.to(), player.to());
        assertEquals("token", player.currencyId());
        assertEquals(2, player.page());
        assertEquals(10, player.limit());
    }

    @Test
    void omittedCurrencyAndPageDefaultToTheDefaultCurrencyAndFirstPage() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history"));

        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(history).query(query.capture());
        assertEquals(self, query.getValue().accountId());
        assertEquals("dollar", query.getValue().currencyId());
        assertEquals(0, query.getValue().page());
        assertEquals(10, query.getValue().limit());
    }

    @Test
    void invalidPagesAreRejectedWithATypedError() {
        for (String bad : List.of("-1", "abc", "1.5")) {
            UUID self = UUID.randomUUID();
            HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
            CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(playerSender(self, "Alex",
                            "aceeconomy.command.money", "aceeconomy.command.history"),
                    "money", List.of("history", "dollar", bad));

            CommandException error = capturedError(sink);
            assertEquals("ACELIB-CMD-INVALID-PAGE", error.getCode(), "page=" + bad);
            assertEquals("command.invalid-page", error.getMessage(), "page=" + bad);
            verify(history, never()).query(any(AuditQuery.class));
        }
    }

    @Test
    void unknownCurrencyIsRejectedWithATypedError() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history", "wat"));

        CommandException error = capturedError(sink);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY", error.getCode());
        assertEquals("general.unknown-currency", error.getMessage());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void noReplyIsSentBeforeTheHistoryFutureCompletes() {
        UUID self = UUID.randomUUID();
        CompletableFuture<AuditPage> pending = new CompletableFuture<>();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(pending);
        when(history.callbackExecutor()).thenReturn(Runnable::run);
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        Sender sender = playerSender(self, "Alex",
                "aceeconomy.command.money", "aceeconomy.command.history");
        registry.dispatch(sender, "money", List.of("history"));

        verify(history).query(any(AuditQuery.class));
        verify(sink, never()).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
        verify(sink, never()).send(any(Sender.class), any(String.class));
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));

        pending.complete(new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).sendPlayerAsync(any(PlayerHandle.class), message.capture());
        assertTrue(message.getValue().contains("DEPOSIT"), "completed query must reply: "
                + message.getValue());
    }

    @Test
    void emptyResultPageIsAnsweredWithAnExplicitMessage() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sink).sendPlayerAsync(any(PlayerHandle.class), message.capture());
        assertTrue(message.getValue().contains("history.empty"),
                "empty page must be explicit via localized key, got: " + message.getValue());
        verify(sink, never()).sendError(any(Sender.class), any(Throwable.class));
    }

    @Test
    void consoleIsRejectedWithoutQueryAndWithoutThrowing() {
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender("aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history"));

        assertEquals("ACELIB-CMD-004", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }

    @Test
    void alreadyCompletedFutureStillAssemblesOffTheCallingThread() throws Exception {
        UUID self = UUID.randomUUID();
        Thread callingThread = Thread.currentThread();
        AuditPage page = new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10);
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(page));
        ExecutorService historyExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-history-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            when(history.callbackExecutor()).thenReturn(historyExecutor);
            CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
            ReplySink sink = mock(ReplySink.class);
            AtomicReference<Thread> replyThread = new AtomicReference<>();
            doAnswer(invocation -> {
                replyThread.set(Thread.currentThread());
                return null;
            }).when(sink).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(playerSender(self, "Alex",
                            "aceeconomy.command.money", "aceeconomy.command.history"),
                    "money", List.of("history"));

            historyExecutor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(sink).sendPlayerAsync(any(PlayerHandle.class), message.capture());
            assertTrue(message.getValue().contains("DEPOSIT"),
                    "completed query must reply: " + message.getValue());
            assertNotNull(replyThread.get(), "reply must have been sent");
            assertNotSame(callingThread, replyThread.get(),
                    "an already-completed history future must not assemble the page on the calling region thread");
        } finally {
            historyExecutor.shutdownNow();
        }
    }

    @Test
    void pendingFutureCompletedLaterStillAssemblesOffTheCallingThread() throws Exception {
        UUID self = UUID.randomUUID();
        Thread callingThread = Thread.currentThread();
        CompletableFuture<AuditPage> pending = new CompletableFuture<>();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(pending);
        ExecutorService historyExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-history-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            when(history.callbackExecutor()).thenReturn(historyExecutor);
            CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
            ReplySink sink = mock(ReplySink.class);
            AtomicReference<Thread> replyThread = new AtomicReference<>();
            doAnswer(invocation -> {
                replyThread.set(Thread.currentThread());
                return null;
            }).when(sink).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(playerSender(self, "Alex",
                            "aceeconomy.command.money", "aceeconomy.command.history"),
                    "money", List.of("history"));

            verify(history).query(any(AuditQuery.class));
            verify(sink, never()).sendPlayerAsync(any(PlayerHandle.class), any(String.class));

            pending.complete(new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10));

            historyExecutor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(sink).sendPlayerAsync(any(PlayerHandle.class), message.capture());
            assertTrue(message.getValue().contains("DEPOSIT"),
                    "completed query must reply: " + message.getValue());
            assertNotNull(replyThread.get(), "reply must have been sent");
            assertNotSame(callingThread, replyThread.get(),
                    "a later-completed history future must not assemble the page on the completing region thread");
        } finally {
            historyExecutor.shutdownNow();
        }
    }

    private CommandServices servicesWithMessages(
            com.smile.aceeconomy.commands.v2.ports.EconomyCommandService economy,
            PlayerLookupService players, HistoryQueryService history, ConfigLangAdapter messages) {
        return new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                history,
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class),
                null, messages);
    }

    @Test
    void nullCallbackExecutorIsRejectedWithExplicitError() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(new AuditPage(List.of(), 0, 0, 10)));
        when(history.callbackExecutor()).thenReturn(null);
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException,
                "missing executor must be an explicit command error, got: " + error.getValue());
        assertEquals("ACELIB-CMD-EMPTY-EXECUTOR", ((CommandException) error.getValue()).getCode());
        verify(sink, never()).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
        verify(sink, never()).send(any(Sender.class), any(String.class));
    }

    @Test
    void rejectingCallbackExecutorIsReportedWithoutCommonPoolBypass() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(CompletableFuture.completedFuture(
                new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10)));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("history io shut down");
        };
        when(history.callbackExecutor()).thenReturn(rejecting);
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof RejectedExecutionException,
                "shutdown rejection must stay observable, got: " + error.getValue());
        assertTrue(error.getValue().getMessage().contains("shut down"));
        verify(sink, never()).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
        verify(sink, never()).send(any(Sender.class), any(String.class));
    }

    @Test
    void mapperFailureIsReportedInsteadOfSilence() {
        UUID self = UUID.randomUUID();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(CompletableFuture.completedFuture(
                new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10)));
        when(history.callbackExecutor()).thenReturn(Runnable::run);
        ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
        when(messages.renderMessage(org.mockito.ArgumentMatchers.eq("history.header"),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new RuntimeException("render boom"));
        CommandServices services = servicesWithMessages(
                economy(), mock(PlayerLookupService.class), history, messages);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender(self, "Alex",
                        "aceeconomy.command.money", "aceeconomy.command.history"),
                "money", List.of("history", "dollar", "0"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        assertEquals("render boom", error.getValue().getMessage(),
                "mapper failure must reach the error reply");
        verify(sink, never()).sendPlayerAsync(any(PlayerHandle.class), any(String.class));
        verify(sink, never()).send(any(Sender.class), any(String.class));
    }

    @Test
    void alreadyCompletedFutureRendersOnHistoryExecutorDirectly() throws Exception {
        UUID self = UUID.randomUUID();
        Thread callingThread = Thread.currentThread();
        AuditPage page = new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10);
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(page));
        ExecutorService historyExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-history-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            when(history.callbackExecutor()).thenReturn(historyExecutor);
            ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
            AtomicReference<Thread> renderThread = new AtomicReference<>();
            when(messages.plainMessage(any(String.class), any(Map.class))).thenReturn("10");
            when(messages.renderMessage(org.mockito.ArgumentMatchers.eq("history.header"),
                    org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
                        renderThread.set(Thread.currentThread());
                        return Component.text("header");
                    });
            when(messages.renderMessage(org.mockito.ArgumentMatchers.eq("history.entry-normal"),
                    org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
                        renderThread.set(Thread.currentThread());
                        return Component.text("entry");
                    });
            CommandServices services = servicesWithMessages(
                    economy(), mock(PlayerLookupService.class), history, messages);
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(playerSender(self, "Alex",
                            "aceeconomy.command.money", "aceeconomy.command.history"),
                    "money", List.of("history"));

            historyExecutor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            assertNotNull(renderThread.get(), "page assembly must have rendered");
            assertNotSame(callingThread, renderThread.get(),
                    "an already-completed future must assemble the page off the calling thread");
            assertEquals("test-history-io", renderThread.get().getName(),
                    "page assembly must run on the history IO executor");
        } finally {
            historyExecutor.shutdownNow();
        }
    }

    @Test
    void pendingFutureRendersOnHistoryExecutorDirectly() throws Exception {
        UUID self = UUID.randomUUID();
        Thread callingThread = Thread.currentThread();
        CompletableFuture<AuditPage> pending = new CompletableFuture<>();
        HistoryQueryService history = mock(HistoryQueryService.class);
        when(history.query(any(AuditQuery.class))).thenReturn(pending);
        ExecutorService historyExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-history-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            when(history.callbackExecutor()).thenReturn(historyExecutor);
            ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
            AtomicReference<Thread> renderThread = new AtomicReference<>();
            when(messages.plainMessage(any(String.class), any(Map.class))).thenReturn("10");
            when(messages.renderMessage(org.mockito.ArgumentMatchers.eq("history.header"),
                    org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
                        renderThread.set(Thread.currentThread());
                        return Component.text("header");
                    });
            when(messages.renderMessage(org.mockito.ArgumentMatchers.eq("history.entry-normal"),
                    org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
                        renderThread.set(Thread.currentThread());
                        return Component.text("entry");
                    });
            CommandServices services = servicesWithMessages(
                    economy(), mock(PlayerLookupService.class), history, messages);
            ReplySink sink = mock(ReplySink.class);
            CommandRegistryImpl registry = new CommandRegistryImpl(sink);
            V2CommandRegistry.create(services).register(registry);

            registry.dispatch(playerSender(self, "Alex",
                            "aceeconomy.command.money", "aceeconomy.command.history"),
                    "money", List.of("history"));

            pending.complete(new AuditPage(List.of(tx(UUID.randomUUID(), self)), 1, 0, 10));

            historyExecutor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            assertNotNull(renderThread.get(), "page assembly must have rendered");
            assertNotSame(callingThread, renderThread.get(),
                    "a later-completed future must assemble the page off the completing thread");
            assertEquals("test-history-io", renderThread.get().getName(),
                    "page assembly must run on the history IO executor");
        } finally {
            historyExecutor.shutdownNow();
        }
    }

    @Test
    void playerHistoryDoesNotGrantAdminHistoryWhenAdminRootIsPresent() {
        HistoryQueryService history = historyReturning(new AuditPage(List.of(), 0, 0, 10));
        CommandServices services = services(economy(), mock(PlayerLookupService.class), history);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(false).when(sender).hasPermission("aceeconomy.admin.history");
        doReturn(true).when(sender).hasPermission("aceeconomy.command.money");
        doReturn(true).when(sender).hasPermission("aceeconomy.command.history");

        registry.dispatch(sender, "aceeco", List.of("history"));

        assertEquals("ACELIB-CMD-003", capturedError(sink).getCode());
        verify(history, never()).query(any(AuditQuery.class));
    }
}
