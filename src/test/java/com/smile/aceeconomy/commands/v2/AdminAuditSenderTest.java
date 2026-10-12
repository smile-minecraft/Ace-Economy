package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * Sender-derived audit for the administrative mutations: a player sender files
 * under the UUID and name seen at command time, the console files under the
 * fixed console marker, an omitted reason falls back to the default, and no
 * reason token can make the record name anybody else.
 *
 * <p>Every case dispatches through the real registry so sender, permission and
 * argument handling are exercised together with the audit handover.
 */
class AdminAuditSenderTest {

    private static final CommandModels.CurrencyInfo DOLLAR =
            new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true);
    private static final UUID TARGET = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private AdminCommandService admin;
    private PlayerLookupService players;
    private CommandServices services;

    private void wire() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(DOLLAR));
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar", "euro"));

        players = mock(PlayerLookupService.class);
        when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(TARGET, "Alex", true))));
        when(players.onlinePlayerNames()).thenReturn(List.of("Alex"));

        admin = mock(AdminCommandService.class);
        EconomyResult<Amount> ok =
                EconomyResult.success(Amount.of(new BigDecimal("10.00"), 2));
        when(admin.give(any(UUID.class), anyString(), any(Amount.class), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.take(any(UUID.class), anyString(), any(Amount.class), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.setBalance(any(UUID.class), anyString(), any(Amount.class), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.give(any(UUID.class), anyString(), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.take(any(UUID.class), anyString(), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.setBalance(any(UUID.class), anyString(), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));

        services = new CommandServices(economy, players,
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), admin, mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender playerSender(UUID id, String name, String operation) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin." + operation);
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn(name);
        return sender;
    }

    private Sender consoleSender(String operation) {
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin." + operation);
        doReturn("console").when(sender).getName();
        return sender;
    }

    private void dispatch(Sender sender, List<String> args) {
        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(sender, "aceeco", args);
    }

    private AdminAudit capturedGive() {
        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(admin, org.mockito.Mockito.timeout(2000))
                .give(eq(TARGET), eq("dollar"), any(Amount.class), audit.capture());
        return audit.getValue();
    }

    @Test
    void playerGiveCarriesSenderIdentityAndReason() {
        wire();
        UUID operator = UUID.randomUUID();

        dispatch(playerSender(operator, "Morgan", "give"),
                List.of("give", "Alex", "10", "dollar", "harbor-check"));

        AdminAudit audit = capturedGive();
        assertEquals(operator, audit.operatorId());
        assertEquals("Morgan", audit.operatorName());
        assertEquals("harbor-check", audit.reason());
    }

    @Test
    void consoleGiveIsFiledUnderTheConsoleMarker() {
        wire();

        dispatch(consoleSender("give"),
                List.of("give", "Alex", "10", "dollar", "nightly-topup"));

        AdminAudit audit = capturedGive();
        assertNull(audit.operatorId());
        assertEquals(AdminAudit.CONSOLE_OPERATOR_NAME, audit.operatorName());
        assertEquals("nightly-topup", audit.reason());
    }

    @Test
    void omittedReasonFallsBackToTheDefault() {
        wire();
        UUID operator = UUID.randomUUID();

        dispatch(playerSender(operator, "Morgan", "give"),
                List.of("give", "Alex", "10", "dollar"));

        AdminAudit audit = capturedGive();
        assertEquals(operator, audit.operatorId());
        assertEquals(AdminAudit.DEFAULT_REASON, audit.reason());
    }

    @Test
    void reasonTokenCannotForgeAnotherOperator() {
        wire();
        UUID operator = UUID.randomUUID();
        String somebodyElse = UUID.randomUUID().toString();

        dispatch(playerSender(operator, "Morgan", "give"),
                List.of("give", "Alex", "10", "dollar", somebodyElse));

        AdminAudit audit = capturedGive();
        assertEquals(operator, audit.operatorId(),
                "the operator must come from the sender, never from an argument");
        assertEquals("Morgan", audit.operatorName());
    }

    @Test
    void playerTakeCarriesSenderIdentityAndReason() {
        wire();
        UUID operator = UUID.randomUUID();

        dispatch(playerSender(operator, "Morgan", "take"),
                List.of("take", "Alex", "10", "dollar", "harbor-check"));

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(admin, org.mockito.Mockito.timeout(2000))
                .take(eq(TARGET), eq("dollar"), any(Amount.class), audit.capture());
        assertEquals(operator, audit.getValue().operatorId());
        assertEquals("harbor-check", audit.getValue().reason());
    }

    @Test
    void playerSetCarriesSenderIdentityAndReason() {
        wire();
        UUID operator = UUID.randomUUID();

        dispatch(playerSender(operator, "Morgan", "set"),
                List.of("set", "Alex", "10", "dollar", "harbor-check"));

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(admin, org.mockito.Mockito.timeout(2000))
                .setBalance(eq(TARGET), eq("dollar"), any(Amount.class), audit.capture());
        assertEquals(operator, audit.getValue().operatorId());
        assertEquals("harbor-check", audit.getValue().reason());
    }

    @Test
    void giveWithExtraArgumentsNeverReachesTheFacade() {
        wire();
        UUID operator = UUID.randomUUID();

        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(playerSender(operator, "Morgan", "give"), "aceeco",
                List.of("give", "Alex", "10", "dollar", "harbor-check", "extra"));

        verify(admin, org.mockito.Mockito.never())
                .give(org.mockito.ArgumentMatchers.any(UUID.class),
                        anyString(), any(Amount.class), any(AdminAudit.class));
        verify(admin, org.mockito.Mockito.never())
                .give(org.mockito.ArgumentMatchers.any(UUID.class),
                        anyString(), any(Amount.class));
    }
}
