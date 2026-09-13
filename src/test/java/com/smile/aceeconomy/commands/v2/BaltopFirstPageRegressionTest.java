package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the live {@code /baltop top} failure ("找不到金幣的資料" despite existing dollar
 * balances): {@link LeaderboardService} pagination is 0-based, so the default first page must be
 * requested as page 0. With the old 1-based request a single-page ranking sliced to an empty list
 * and the command reported an empty leaderboard.
 */
class BaltopFirstPageRegressionTest {

    private static final String BALTOP_PERMISSION = "aceeconomy.command.baltop";

    @Test
    void defaultFirstPageQueriesZeroBasedPageAndRepliesWithSeededEntries() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(java.util.Optional.of(
                new CommandModels.CurrencyInfo("dollar", "金幣", "$", 2, true)));
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar"));
        LeaderboardQueryService leaderboard = mock(LeaderboardQueryService.class);
        when(leaderboard.pageSize()).thenReturn(10);
        when(leaderboard.top(eq("dollar"), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(
                List.of(new CommandModels.LeaderboardEntry(1, "Alice", new BigDecimal("100.00"), "dollar"))));
        CommandServices services = new CommandServices(economy, mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class), leaderboard,
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));

        ReplySink sink = mock(ReplySink.class);
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission(BALTOP_PERMISSION);
        CommandSpec baltop = BaltopCommandSpec.create(services);
        CommandRegistry registry = new CommandRegistryImpl(sink);
        registry.register(baltop);

        registry.dispatch(sender, "baltop", List.of("top"));

        // The default page must be the FIRST page (0-based), never page 1.
        verify(leaderboard).top(eq("dollar"), eq(0), eq(10));
        // A ranking that fits in one page must still be answered with its entries,
        // never with the empty-currency message.
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sink).send(org.mockito.ArgumentMatchers.same(sender), msg.capture());
        String captured = msg.getValue();
        assertTrue(captured.contains("Alice"), "seeded dollar entry must be replied: " + captured);
        assertFalse(captured.contains("baltop.empty-currency"),
                "a sub-page ranking must not be misreported as empty: " + captured);
    }

    @Test
    void genuinelyEmptyRankingStillReportsEmptyCurrency() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(java.util.Optional.of(
                new CommandModels.CurrencyInfo("dollar", "金幣", "$", 2, true)));
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar"));
        LeaderboardQueryService leaderboard = mock(LeaderboardQueryService.class);
        when(leaderboard.pageSize()).thenReturn(10);
        when(leaderboard.top(eq("dollar"), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(List.of()));
        CommandServices services = new CommandServices(economy, mock(com.smile.aceeconomy.commands.v2.ports.PlayerLookupService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService.class), leaderboard,
                mock(com.smile.aceeconomy.commands.v2.ports.BankCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.AdminCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));

        ReplySink sink = mock(ReplySink.class);
        Sender sender = mock(Sender.class);
        doReturn(false).when(sender).isPlayer();
        doReturn(true).when(sender).hasPermission(BALTOP_PERMISSION);
        CommandSpec baltop = BaltopCommandSpec.create(services);
        CommandRegistry registry = new CommandRegistryImpl(sink);
        registry.register(baltop);

        registry.dispatch(sender, "baltop", List.of("top"));

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(sink).send(org.mockito.ArgumentMatchers.same(sender), msg.capture());
        assertTrue(msg.getValue().contains("baltop.empty-currency"),
                "an empty ranking must still be reported as empty: " + msg.getValue());
    }
}
