package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.gui.v2.BankGuiTestFixtures;
import com.smile.aceeconomy.gui.v2.StubBankGuiUseCase;
import com.smile.aceeconomy.gui.v2.V2BankGuiSession;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * Command-surface / GUI-surface consistency: {@code /bank open} must resolve
 * the currently active layout on every open, so a successful reload that swaps
 * the layout changes what newly opened interfaces show. Already-open views
 * were rebuilt in place by the reload itself, so only new opens matter here.
 *
 * <p>Opens serialize against the reload swap inside the session, so no open
 * can leak a stale view past a reload and no retry exists.
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
        GuiScope scope = BankGuiTestFixtures.scope();
        V2BankGuiSession session = new V2BankGuiSession(scope, new RecordingFoliaContext(),
                new StubBankGuiUseCase(), oldLayout, BankGuiTestFixtures.messages(),
                null, BankGuiTestFixtures.renderer());
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(session, current::get, Runnable::run);

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);

            bank.open(id, "someone");
            ArgumentCaptor<GuiView> views = ArgumentCaptor.forClass(GuiView.class);
            Mockito.verify(scope).openView(Mockito.eq(id), views.capture());
            assertEquals(27, views.getValue().size(),
                    "the first open must build from the old layout");

            // A successful reload swaps the reference the supplier reads and
            // reopens tracked views; the session below follows the swap for
            // the already-open view.
            current.set(newLayout);
            assertEquals(1, session.applyLayout(newLayout));

            bank.open(id, "someone");
            Mockito.verify(scope, Mockito.times(2)).openView(Mockito.eq(id), views.capture());
            assertEquals(36, views.getValue().size(),
                    "opens after the swap must build from the new layout");
        }
    }

    @Test
    void disabledLayoutOpensNothing() {
        AtomicReference<BankGuiLayout> current = new AtomicReference<>(BankGuiLayout.of(
                false, oldLayout().titleKey(), oldLayout().size(), oldLayout().actions()));
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(gui, current::get, Runnable::run);

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);

            bank.open(id, "someone");
            Mockito.verify(gui, Mockito.never()).open(Mockito.any());
        }
    }

    @Test
    void offlinePlayerOpensNothing() {
        AtomicReference<BankGuiLayout> current = new AtomicReference<>(oldLayout());
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(gui, current::get, Runnable::run);

        UUID id = UUID.randomUUID();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);

            bank.open(id, "someone");
            Mockito.verify(gui, Mockito.never()).open(Mockito.any());
        }
    }

    @Test
    void everyOpenDispatchesExactlyOnce() {
        AtomicReference<BankGuiLayout> current = new AtomicReference<>(oldLayout());
        V2BankGuiSession gui = Mockito.mock(V2BankGuiSession.class);
        ProductionAdapters.Bank bank =
                new ProductionAdapters.Bank(gui, current::get, Runnable::run);

        UUID id = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(id);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);

            bank.open(id, "someone");
            // No stale-retry exists: opens serialize against the swap inside
            // the session, so one command is exactly one open.
            Mockito.verify(gui, Mockito.times(1)).open(player);
        }
    }
}
