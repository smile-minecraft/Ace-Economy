package com.smile.aceeconomy.bootstrap;

import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mockStatic;

/**
 * Command-surface / GUI-surface consistency: {@code /bank open} must resolve
 * the currently active layout on every open, so a successful reload that swaps
 * the layout changes what newly opened interfaces show. Already-open sessions
 * were dropped by {@code invalidateAll}, so only new opens matter here.
 */
class BankLayoutRefreshTest {

    private static BankGuiLayout oldLayout() {
        return BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
    }

    private static BankGuiLayout newLayout() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", new BankGuiLayout.SlotConfig(
                "withdraw100", 11, BankGuiLayout.ActionType.WITHDRAW, 100L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("withdraw500", new BankGuiLayout.SlotConfig(
                "withdraw500", 13, BankGuiLayout.ActionType.WITHDRAW, 500L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", new BankGuiLayout.SlotConfig(
                "close", 15, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        return BankGuiLayout.of(true, "gui.bank-title", 36, actions);
    }

    @Test
    void reopenAfterLayoutSwapUsesCurrentLayout() {
        BankGuiLayout oldLayout = oldLayout();
        BankGuiLayout newLayout = newLayout();
        AtomicReference<BankGuiLayout> current = new AtomicReference<>(oldLayout);
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn("Bank");
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(gui, current::get, messages, Runnable::run);

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);

            bank.open(id, "someone");
            Mockito.verify(gui).open(player, "Bank", 27, oldLayout.protectedSlots(), 0L);

            // A successful reload swaps the reference the supplier reads.
            current.set(newLayout);
            bank.open(id, "someone");
            Mockito.verify(gui).open(player, "Bank", 36, newLayout.protectedSlots(), 0L);
        }
    }
}
