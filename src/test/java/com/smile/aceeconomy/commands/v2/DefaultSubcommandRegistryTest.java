package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.PlayerIdentity;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the live Bedrock/Java {@code /money} symptom: a bare {@code /money} showed the
 * generated usage/help because AceLib v1.2.1 has no root-level handler and answers an empty
 * argument list with help. The {@link DefaultSubcommandRegistry} decorator must route a player's
 * bare invocation to the money spec's {@code balance} subcommand, while leaving explicit
 * subcommands, other players' lookups and console behaviour untouched.
 */
class DefaultSubcommandRegistryTest {

    private record Harness(CommandRegistry registry, ReplySink sink, Sender sender, PlayerHandle player,
                           EconomyCommandService economy, PlayerLookupService players) {
    }

    private Harness harness() {
        UUID selfId = UUID.randomUUID();
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.getBalance(any(UUID.class), eq("dollar"))).thenReturn(
                CompletableFuture.completedFuture(EconomyResult.success(Amount.of(1234, 2))));

        PlayerLookupService players = mock(PlayerLookupService.class);
        CommandServices services = new CommandServices(economy, players,
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class), mock(RollbackCommandService.class),
                mock(BackupCommandService.class));

        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl delegate = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(delegate);
        CommandRegistry registry = new DefaultSubcommandRegistry(delegate, Map.of("money", "balance"));

        Sender sender = mock(Sender.class);
        when(sender.isPlayer()).thenReturn(true);
        when(sender.hasPermission(anyString())).thenReturn(true);
        PlayerHandle player = mock(PlayerHandle.class);
        when(player.getUniqueId()).thenReturn(selfId);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Alex");
        when(sender.asPlayer()).thenReturn(player);

        return new Harness(registry, sink, sender, player, economy, players);
    }

    @Test
    void bareMoneyFromPlayerShowsOwnDefaultBalance() {
        Harness h = harness();

        h.registry().dispatch(h.sender(), "money", List.of());

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(h.sink()).sendPlayerAsync(same(h.player()), msg.capture());
        assertTrue(msg.getValue().contains("economy.balance-check"),
                "bare /money must render the self-balance message: " + msg.getValue());
        assertTrue(msg.getValue().contains("Alex"), msg.getValue());
    }

    @Test
    void bareBalanceAliasFromPlayerAlsoShowsOwnBalance() {
        Harness h = harness();

        // plugin.yml routes /balance (and /bal) to the same money spec as a root alias.
        h.registry().dispatch(h.sender(), "balance", List.of());

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(h.sink()).sendPlayerAsync(same(h.player()), msg.capture());
        assertTrue(msg.getValue().contains("economy.balance-check"), msg.getValue());
    }

    @Test
    void explicitBalanceSubcommandStillShowsOwnBalance() {
        Harness h = harness();

        h.registry().dispatch(h.sender(), "money", List.of("balance"));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(h.sink()).sendPlayerAsync(same(h.player()), msg.capture());
        assertTrue(msg.getValue().contains("economy.balance-check"), msg.getValue());
    }

    @Test
    void explicitBalanceOfAnotherPlayerStillResolvesTheTarget() {
        Harness h = harness();
        UUID otherId = UUID.randomUUID();
        when(h.players().resolve("Bob")).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new PlayerIdentity(otherId, "Bob", false))));

        h.registry().dispatch(h.sender(), "money", List.of("balance", "Bob"));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(h.sink()).sendPlayerAsync(same(h.player()), msg.capture());
        assertTrue(msg.getValue().contains("Bob"),
                "explicit target lookup must keep working: " + msg.getValue());
    }

    @Test
    void bareMoneyFromConsoleKeepsHelpInsteadOfFailingThePlayerOnlyPath() {
        Harness h = harness();
        Sender console = mock(Sender.class);
        when(console.isPlayer()).thenReturn(false);
        when(console.hasPermission(anyString())).thenReturn(true);

        h.registry().dispatch(console, "money", List.of());

        ArgumentCaptor<String> help = ArgumentCaptor.forClass(String.class);
        verify(h.sink()).send(same(console), help.capture());
        assertTrue(help.getValue().contains("money"), help.getValue());
        verify(h.sink(), never()).sendPlayerAsync(any(), anyString());
    }

    @Test
    void nonEmptyArgumentsAreNeverRewrittenToTheDefault() {
        Harness h = harness();

        // /money me is not a documented subcommand; it must keep returning the unknown-subcommand
        // error rather than silently becoming a balance call for a player literally named "me".
        h.registry().dispatch(h.sender(), "money", List.of("me"));

        verify(h.sink(), never()).sendPlayerAsync(any(), anyString());
        verify(h.sink()).sendError(same(h.sender()), any(Throwable.class));
    }

    @Test
    void tabCompletionIsUnchanged() {
        Harness h = harness();

        List<String> completions = h.registry().tabComplete(h.sender(), "money", List.of());

        assertEquals(List.of("money", "balance"), completions);
    }
}
