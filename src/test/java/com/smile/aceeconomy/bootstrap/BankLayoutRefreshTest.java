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
 *
 * <p>Wiring contract: consumer action slots are guarded by the consumer click
 * listener, never by AceLib. The production open therefore passes an empty
 * AceLib protected set (an action slot registered as AceLib-protected would be
 * rejected by {@code validateClick}), then renders the configured buttons
 * through the generation-bound async-update path.
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
            // Action slots stay out of the AceLib protected set; the consumer
            // listener owns click protection for them.
            Mockito.verify(gui).open(player, "Bank", 27, Set.of(), 0L);

            // A successful reload swaps the reference the supplier reads.
            current.set(newLayout);
            bank.open(id, "someone");
            Mockito.verify(gui).open(player, "Bank", 36, Set.of(), 0L);
        }
    }

    @Test
    void openRendersConfiguredButtonsAfterOpen() {
        BankGuiLayout layout = newLayout();
        AtomicReference<BankGuiLayout> current = new AtomicReference<>(layout);
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn("Bank");
        V2BankGuiSession.OpenOutcome opened = Mockito.mock(V2BankGuiSession.OpenOutcome.class);
        Mockito.when(opened.success()).thenReturn(true);
        com.smile.acelib.gui.GuiSession aceSession = new com.smile.acelib.gui.GuiSession(
                UUID.randomUUID(), 7L, "v2-bank", "Bank", 36, Set.of());
        Mockito.when(opened.session()).thenReturn(aceSession);
        Mockito.when(gui.open(Mockito.any(), Mockito.anyString(), Mockito.anyInt(),
                        Mockito.anySet(), Mockito.anyLong()))
                .thenReturn(opened);
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(gui, current::get, messages, Runnable::run);

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);

            bank.open(id, "someone");
            Mockito.verify(gui).renderLayout(
                    Mockito.eq(id), Mockito.eq(7L),
                    Mockito.same(layout), Mockito.same(messages));
        }
    }
}
