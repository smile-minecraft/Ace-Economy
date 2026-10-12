package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * typed-argument migration contract for tab completion: the exact suggestion list each subcommand
 * slot produces today, locked through the real registry before the typed-argument
 * migration. The typed {@code suggest} functions must reproduce these lists one by one,
 * including the inherited positional quirks (for example the amount slot suggesting
 * currencies, or the import tail slots). Permission-denied senders always see nothing.
 */
class TypedCompletionContractTest {

    private CommandServices services() {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CommandModels.CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.knownCurrencyIds()).thenReturn(List.of("dollar", "euro"));
        PlayerLookupService players = mock(PlayerLookupService.class);
        when(players.onlinePlayerNames()).thenReturn(List.of("Alex", "Bob"));
        return new CommandServices(economy, players,
                mock(WithdrawCommandService.class), mock(LeaderboardQueryService.class),
                mock(BankCommandService.class), mock(AdminCommandService.class),
                mock(HistoryQueryService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.RollbackCommandService.class),
                mock(com.smile.aceeconomy.commands.v2.ports.BackupCommandService.class));
    }

    private Sender permittedSender() {
        Sender sender = mock(Sender.class);
        doReturn(true).when(sender).hasPermission(org.mockito.ArgumentMatchers.anyString());
        return sender;
    }

    private CommandRegistryImpl registry() {
        CommandRegistryImpl registry = new CommandRegistryImpl(mock(ReplySink.class));
        V2CommandRegistry.create(services()).register(registry);
        return registry;
    }

    @Test
    void moneyBalanceSlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("Alex"), registry.tabComplete(sender, "money", List.of("balance", "A")));
        assertEquals(List.of("dollar"), registry.tabComplete(sender, "money", List.of("balance", "Alex", "d")));
        assertEquals(List.of(), registry.tabComplete(sender, "money", List.of("balance", "Alex", "dollar", "x")));
    }

    @Test
    void paySendSlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("Alex"), registry.tabComplete(sender, "pay", List.of("send", "A")));
        assertEquals(List.of("dollar", "euro"),
                registry.tabComplete(sender, "pay", List.of("send", "Alex", "")));
        assertEquals(List.of("dollar"),
                registry.tabComplete(sender, "pay", List.of("send", "Alex", "10", "d")));
    }

    @Test
    void withdrawAndBaltopCurrencySlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("dollar", "euro"),
                registry.tabComplete(sender, "withdraw", List.of("cash", "")));
        assertEquals(List.of("euro"),
                registry.tabComplete(sender, "withdraw", List.of("cash", "10", "e")));
        assertEquals(List.of("dollar", "euro"),
                registry.tabComplete(sender, "baltop", List.of("top", "")));
    }

    @Test
    void historySlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("Alex"),
                registry.tabComplete(sender, "aceeco", List.of("history", "A")));
        assertEquals(List.of("dollar"),
                registry.tabComplete(sender, "aceeco", List.of("history", "Alex", "d")));
        assertEquals(List.of(),
                registry.tabComplete(sender, "aceeco", List.of("history", "Alex", "dollar", "1")));
    }

    @Test
    void mutationSlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("Bob"), registry.tabComplete(sender, "aceeco", List.of("give", "B")));
        assertEquals(List.of("dollar", "euro"),
                registry.tabComplete(sender, "aceeco", List.of("give", "Alex", "")));
        assertEquals(List.of("euro"),
                registry.tabComplete(sender, "aceeco", List.of("give", "Alex", "10", "e")));
    }

    @Test
    void importTailSlots() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        // Source slot: the legacy positional mapping yields no suggestions here.
        assertEquals(List.of(), registry.tabComplete(sender, "aceeco", List.of("import", "e")));
        // Path slot: currencies plus the apply keyword.
        assertEquals(List.of("dollar", "euro", "apply"),
                registry.tabComplete(sender, "aceeco", List.of("import", "essentials", "")));
        // Third slot: apply or confirm.
        assertEquals(List.of("apply"),
                registry.tabComplete(sender, "aceeco", List.of("import", "essentials", "userdata", "a")));
        // Fourth slot: the confirm keyword.
        assertEquals(List.of("confirm"),
                registry.tabComplete(sender, "aceeco",
                        List.of("import", "essentials", "userdata", "dollar", "c")));
        // Fifth slot: nothing follows confirm.
        assertEquals(List.of(),
                registry.tabComplete(sender, "aceeco",
                        List.of("import", "essentials", "userdata", "dollar", "apply", "c")));
    }

    @Test
    void slotsWithoutACompleterStayEmpty() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of(), registry.tabComplete(sender, "aceeco", List.of("rollback", "")));
        assertEquals(List.of(), registry.tabComplete(sender, "aceeco", List.of("backup", "")));
        assertEquals(List.of(), registry.tabComplete(sender, "aceeco", List.of("restore", "")));
        assertEquals(List.of(), registry.tabComplete(sender, "aceeco", List.of("restore", "some-id", "")));
    }

    @Test
    void deniedSendersSeeNoCompletions() {
        CommandRegistryImpl registry = registry();
        Sender denied = mock(Sender.class);
        assertEquals(List.of(), registry.tabComplete(denied, "pay", List.of("send", "A")));
        assertEquals(List.of(), registry.tabComplete(denied, "aceeco", List.of("give", "A")));
    }

    @Test
    void unknownSubcommandFallsBackToNamePrefixMatches() {
        CommandRegistryImpl registry = registry();
        Sender sender = permittedSender();
        assertEquals(List.of("send"), registry.tabComplete(sender, "pay", List.of("s")));
    }
}
