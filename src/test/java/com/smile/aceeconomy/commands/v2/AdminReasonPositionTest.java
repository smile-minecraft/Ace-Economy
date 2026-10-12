package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandException;
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
 * Reason position for the administrative mutations: when both currency and reason are given,
 * currency comes first and reason comes last; a reason never substitutes for an unknown
 * currency, and a sensitive reason is refused with a fixed code without echoing it.
 *
 * <p>Skeleton: the reason slots are parsed but the handlers still call the unaudited
 * overloads, so the handover cases fail until the audit wiring lands. The implementor must
 * resolve the audit from the sender plus the trailing reason, validate it through
 * {@code AdminAudit.validateReason} mapped to {@code ACELIB-CMD-INVALID-REASON}, and call
 * the audited overloads. An unknown currency must still fail with
 * {@code ACELIB-CMD-UNKNOWN-CURRENCY} even when a reason-looking token follows.
 */
class AdminReasonPositionTest {

    private static final CommandModels.CurrencyInfo DOLLAR =
            new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true);
    private static final UUID TARGET = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private AdminCommandService admin;
    private CommandServices services;

    private void wire() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(DOLLAR));
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar", "euro"));

        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.resolve("Alex")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new CommandModels.PlayerIdentity(TARGET, "Alex", true))));
        when(players.onlinePlayerNames()).thenReturn(List.of("Alex"));

        admin = mock(AdminCommandService.class);
        EconomyResult<Amount> ok =
                EconomyResult.success(Amount.of(new BigDecimal("10.00"), 2));
        when(admin.give(any(UUID.class), anyString(), any(Amount.class), any(AdminAudit.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));
        when(admin.give(any(UUID.class), anyString(), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(ok));

        services = new CommandServices(economy, players,
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), admin, mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender playerSender(UUID id, String name) {
        Sender sender = mock(Sender.class);
        PlayerHandle player = mock(PlayerHandle.class);
        doReturn(true).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission("aceeconomy.admin");
        doReturn(true).when(sender).hasPermission("aceeconomy.admin.give");
        doReturn(player).when(sender).asPlayer();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn(name);
        return sender;
    }

    private void dispatch(Sender sender, List<String> args, ReplySink sink) {
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);
        registry.dispatch(sender, "aceeco", args);
    }

    @Test
    void currencyFirstReasonLastReachesFacadeWithBoth() {
        wire();
        UUID operator = UUID.randomUUID();
        ReplySink sink = mock(ReplySink.class);

        dispatch(playerSender(operator, "Morgan"),
                List.of("give", "Alex", "10", "dollar", "harbor-check"), sink);

        ArgumentCaptor<AdminAudit> audit = ArgumentCaptor.forClass(AdminAudit.class);
        verify(admin, org.mockito.Mockito.timeout(2000))
                .give(eq(TARGET), eq("dollar"), any(Amount.class), audit.capture());
        assertEquals("harbor-check", audit.getValue().reason(),
                "the trailing token after an explicit currency is the reason");
    }

    @Test
    void unknownCurrencyIsStillUnknownWhenAReasonFollows() {
        wire();
        ReplySink sink = mock(ReplySink.class);

        dispatch(playerSender(UUID.randomUUID(), "Morgan"),
                List.of("give", "Alex", "10", "nosuchcoin", "harbor-check"), sink);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink, org.mockito.Mockito.timeout(2000))
                .sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY",
                ((CommandException) error.getValue()).getCode(),
                "an unknown currency must not be guessed as a default plus reason");
        verify(admin, never()).give(any(UUID.class), anyString(), any(Amount.class),
                any(AdminAudit.class));
        verify(admin, never()).give(any(UUID.class), anyString(), any(Amount.class));
    }

    @Test
    void reasonWithoutExplicitCurrencyIsUnknownCurrencyNotADefaultReason() {
        wire();
        ReplySink sink = mock(ReplySink.class);

        // "harbor-check" is not a known currency: without an explicit currency the third
        // token must be diagnosed as unknown currency, never as a reason on the default.
        dispatch(playerSender(UUID.randomUUID(), "Morgan"),
                List.of("give", "Alex", "10", "harbor-check"), sink);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink, org.mockito.Mockito.timeout(2000))
                .sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException);
        assertEquals("ACELIB-CMD-UNKNOWN-CURRENCY",
                ((CommandException) error.getValue()).getCode());
        verify(admin, never()).give(any(UUID.class), anyString(), any(Amount.class),
                any(AdminAudit.class));
    }

    @Test
    void sensitiveReasonIsRefusedWithoutEchoWithoutReachingTheFacade() {
        wire();
        ReplySink sink = mock(ReplySink.class);

        dispatch(playerSender(UUID.randomUUID(), "Morgan"),
                List.of("give", "Alex", "10", "dollar", "bearer-token"), sink);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(sink, org.mockito.Mockito.timeout(2000))
                .sendError(any(Sender.class), error.capture());
        assertTrue(error.getValue() instanceof CommandException);
        assertEquals("ACELIB-CMD-INVALID-REASON",
                ((CommandException) error.getValue()).getCode(),
                "a secret-looking reason must be refused with a fixed code");
        assertTrue(!error.getValue().getMessage().contains("bearer-token"),
                "the refusal must not echo the sensitive value");
        verify(admin, never()).give(any(UUID.class), anyString(), any(Amount.class),
                any(AdminAudit.class));
        verify(admin, never()).give(any(UUID.class), anyString(), any(Amount.class));
    }
}
