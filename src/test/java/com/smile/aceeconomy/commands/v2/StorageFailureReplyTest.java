package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.ports.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Storage-failure replies: an exceptional persistence future must become a typed,
 * understandable command error — and a committed outcome must never read like an
 * ordinary failure that invites a blind retry.
 */
class StorageFailureReplyTest {

    private static PersistenceException uncommitted() {
        return new PersistenceException("Failed to save account", new RuntimeException("injected io"));
    }

    private static PersistenceException committed() {
        return new PersistenceException(
                "Save committed successfully, but restoring auto-commit failed",
                new RuntimeException("injected cleanup"), true);
    }

    private EconomyCommandService economy() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.resolveCurrency(null)).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        return economy;
    }

    private PlayerLookupService playersResolving(String name, UUID uuid) {
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve(name)).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(uuid, name, true))));
        return players;
    }

    private CommandServices services(EconomyCommandService economy, PlayerLookupService players,
                                     AdminCommandService admin) {
        return new CommandServices(economy, players,
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                admin,
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender playerSender(String permission) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission(permission);
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        return sender;
    }

    private Sender consoleSender(String root, String sub) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission(root);
        doReturn(true).when(sender).hasPermission(sub);
        return sender;
    }

    private CommandException sentError(ReplySink sink) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException,
                () -> "storage failures must become typed command errors, got " + error.getValue());
        return (CommandException) error.getValue();
    }

    @Test
    void balanceLookupFailureRepliesAsTypedAuditFailure() {
        EconomyCommandService economy = economy();
        when(economy.getBalance(any(UUID.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(uncommitted()));
        CommandServices services = services(economy,
                mock(PlayerLookupService.class), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.money"), "money", List.of("balance"));

        assertEquals("ACELIB-CMD-AUDIT-FAILURE", sentError(sink).getCode());
    }

    @Test
    void balanceLookupCommittedOutcomeWarnsAgainstRetrying() {
        EconomyCommandService economy = economy();
        when(economy.getBalance(any(UUID.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(committed()));
        CommandServices services = services(economy,
                mock(PlayerLookupService.class), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.money"), "money", List.of("balance"));

        CommandException error = sentError(sink);
        assertEquals("ACELIB-CMD-COMMITTED-UNKNOWN", error.getCode());
        String wording = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("retry"),
                () -> "a committed outcome must warn against retrying, got: " + error.getMessage());
    }

    @Test
    void payTransferFailureRepliesAsTypedAuditFailure() {
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        when(economy.transfer(any(UUID.class), any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(uncommitted()));
        CommandServices services = services(economy,
                playersResolving("Bob", bob), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay"), "pay", List.of("send", "Bob", "10"));

        assertEquals("ACELIB-CMD-AUDIT-FAILURE", sentError(sink).getCode());
    }

    @Test
    void payTransferCommittedOutcomeWarnsAgainstRetrying() {
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        when(economy.transfer(any(UUID.class), any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(committed()));
        CommandServices services = services(economy,
                playersResolving("Bob", bob), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay"), "pay", List.of("send", "Bob", "10"));

        CommandException error = sentError(sink);
        assertEquals("ACELIB-CMD-COMMITTED-UNKNOWN", error.getCode());
        String wording = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("retry"),
                () -> "a committed outcome must warn against retrying, got: " + error.getMessage());
    }

    @Test
    void adminGiveFailureRepliesAsTypedAuditFailure() {
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        AdminCommandService admin = mock(AdminCommandService.class);
        when(admin.give(any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(uncommitted()));
        CommandServices services = services(economy, playersResolving("Bob", bob), admin);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.give"),
                "aceeco", List.of("give", "Bob", "10"));

        assertEquals("ACELIB-CMD-AUDIT-FAILURE", sentError(sink).getCode());
    }

    @Test
    void adminGiveCommittedOutcomeWarnsAgainstRetrying() {
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        AdminCommandService admin = mock(AdminCommandService.class);
        when(admin.give(any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(committed()));
        CommandServices services = services(economy, playersResolving("Bob", bob), admin);
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(consoleSender("aceeconomy.admin", "aceeconomy.admin.give"),
                "aceeco", List.of("give", "Bob", "10"));

        CommandException error = sentError(sink);
        assertEquals("ACELIB-CMD-COMMITTED-UNKNOWN", error.getCode());
        String wording = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("committed"),
                () -> "a committed outcome must say so, got: " + error.getMessage());
    }

    @Test
    void serviceReturnedAuditFailuresKeepTheirExistingCode() {
        EconomyCommandService economy = economy();
        when(economy.getBalance(any(UUID.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        EconomyResult.failure(
                                com.smile.aceeconomy.domain.EconomyError.AUDIT_FAILURE, "audit blew up")));
        CommandServices services = services(economy,
                mock(PlayerLookupService.class), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.money"), "money", List.of("balance"));

        assertEquals("ACELIB-CMD-AUDIT-FAILURE", sentError(sink).getCode());
    }

    @Test
    void payTransferIndeterminateOutcomeWarnsAgainstRetrying() {
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        when(economy.transfer(any(UUID.class), any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(new com.smile.aceeconomy.ports.persistence
                        .IndeterminatePersistenceException(
                                "Transfer may have committed although its confirmation was lost",
                                new RuntimeException("injected commit response lost"))));
        CommandServices services = services(economy,
                playersResolving("Bob", bob), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay"), "pay", List.of("send", "Bob", "10"));

        CommandException error = sentError(sink);
        assertEquals("ACELIB-CMD-COMMITTED-UNKNOWN", error.getCode());
        String wording = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(wording.contains("uncertain"),
                () -> "an uncertain outcome must say so, got: " + error.getMessage());
        assertTrue(wording.contains("retry"),
                () -> "an uncertain outcome must warn against retrying, got: " + error.getMessage());
    }

    @Test
    void playerReplyNeverCarriesInternalPaths() {
        EconomyCommandService economy = economy();
        when(economy.getBalance(any(UUID.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new PersistenceException(
                        "Failed to persist banknote redemption; the new data was already published"
                                + " (file=/secret/place/data.json)",
                        new RuntimeException("injected io"))));
        CommandServices services = services(economy,
                mock(PlayerLookupService.class), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.money"), "money", List.of("balance"));

        CommandException error = sentError(sink);
        assertEquals("ACELIB-CMD-AUDIT-FAILURE", error.getCode());
        assertFalse(String.valueOf(error.getMessage()).contains("/secret/place"),
                () -> "internal paths must not reach the player reply, got: " + error.getMessage());
        assertFalse(String.valueOf(error.getMessage()).contains("data.json"),
                () -> "internal file names must not reach the player reply, got: " + error.getMessage());
    }

    @Test
    void mappedErrorKeepsTheOriginalThrowableForDiagnosis() {
        PersistenceException storage = new PersistenceException(
                "Failed to save account (file=/secret/place/data.json)",
                new RuntimeException("injected io"));
        storage.addSuppressed(new java.io.IOException("rollback blew up"));

        CommandException error = StorageFailures.from(null, storage);

        assertEquals("ACELIB-CMD-AUDIT-FAILURE", error.getCode());
        assertTrue(error.getCause() == storage,
                "the mapped error must carry the original throwable for diagnosis");
        assertTrue(java.util.Arrays.stream(error.getCause().getSuppressed())
                        .anyMatch(suppressed -> suppressed.getMessage().contains("rollback blew up")),
                "suppressed cleanup failures must stay reachable from the mapped error");
        assertFalse(String.valueOf(error.getMessage()).contains("/secret/place"),
                () -> "the fixed reply must not leak internal paths, got: " + error.getMessage());
    }

    @Test
    void errorInStorageChainIsNeverTypedAsStorageOutage() {
        AssertionError carried = new AssertionError("injected Error");
        UUID bob = UUID.randomUUID();
        EconomyCommandService economy = economy();
        when(economy.transfer(any(UUID.class), any(UUID.class), any(String.class), any(Amount.class)))
                .thenReturn(CompletableFuture.failedFuture(new com.smile.aceeconomy.ports.persistence
                        .IndeterminatePersistenceException(
                                "Transfer may have committed although its confirmation was lost",
                                carried)));
        CommandServices services = services(economy,
                playersResolving("Bob", bob), mock(AdminCommandService.class));
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        registry.dispatch(playerSender("aceeconomy.command.pay"), "pay", List.of("send", "Bob", "10"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink).sendError(any(Sender.class), error.capture());
        Throwable sent = error.getValue();
        if (sent instanceof CommandException command) {
            assertFalse(
                    "ACELIB-CMD-COMMITTED-UNKNOWN".equals(command.getCode())
                            || "ACELIB-CMD-AUDIT-FAILURE".equals(command.getCode()),
                    () -> "an Error must never be typed as a storage outage, got " + sent);
        }
        boolean carriesError = false;
        for (Throwable current = sent; current != null; current = current.getCause()) {
            if (current == carried) {
                carriesError = true;
                break;
            }
        }
        assertTrue(carriesError, () -> "the original Error must stay reachable, got " + sent);
    }
}
