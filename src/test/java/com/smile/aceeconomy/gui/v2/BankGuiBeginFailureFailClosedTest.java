package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Begin-failure fail-closed regression: when {@code beginAsyncUpdate} is rejected or throws,
 * the render must stay rejected, must not enqueue a paint, and must still publish a
 * memory-only pending intent bound to the active backend generation — otherwise the
 * freshly opened shell is left without any guard. Dangerous clicks and drags stay
 * cancelled with zero business dispatch until close (or a newer reopen) converges it.
 */
class BankGuiBeginFailureFailClosedTest {

    private static BankGuiLayout layout27() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", new BankGuiLayout.SlotConfig(
                "withdraw100", 11, BankGuiLayout.ActionType.WITHDRAW, 100L, null,
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", new BankGuiLayout.SlotConfig(
                "close", 15, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        return BankGuiLayout.of(true, "gui.bank-title", 27, actions);
    }

    private static Material solidMaterial() {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(false);
        return material;
    }

    private static ConfigLangAdapter messages() {
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> Component.text("t:" + invocation.getArgument(0)));
        return messages;
    }

    private static Player viewPlayer(UUID uuid, Inventory top, int topSize) {
        Player player = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.getInventory()).thenReturn(inv);
        Mockito.when(player.getOpenInventory()).thenReturn(view);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        Mockito.when(top.getSize()).thenReturn(topSize);
        return player;
    }

    private static BankGuiRenderer rendererWith(Map<String, Material> materials) {
        return new BankGuiRenderer(
                material -> {
                    ItemStack stack = Mockito.mock(ItemStack.class);
                    Mockito.when(stack.getItemMeta()).thenReturn(Mockito.mock(ItemMeta.class));
                    return stack;
                },
                materials::get);
    }

    private static Map<String, Material> fullMaterials() {
        return Map.of(
                "CHEST", solidMaterial(), "PAPER", solidMaterial(), "BARRIER", solidMaterial());
    }

    private static InventoryView clickView(Inventory top) {
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        return view;
    }

    private static InventoryClickEvent clickEvent(Player player, InventoryView view,
                                                  int rawSlot, boolean shift) {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(rawSlot);
        Mockito.when(event.isShiftClick()).thenReturn(shift);
        return event;
    }

    private static InventoryDragEvent dragEvent(Player player, InventoryView view, Set<Integer> rawSlots) {
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(rawSlots);
        return event;
    }

    @Test
    void beginRejectedStaysFailClosedAndBlocksDangerousEvents() {
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        FakeGuiService gui = Mockito.spy(FakeGuiService.deferred());
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Mockito.doReturn(GuiResult.rejected("begin-rejected", "injected"))
                .when(gui).beginAsyncUpdate(Mockito.any(), Mockito.anyLong(), Mockito.anyInt());

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertFalse(outcome.isSuccess(), "a rejected begin must reject the render, never success");
        assertEquals(0, gui.pendingCallbackCount(), "a rejected begin must not enqueue a paint");
        assertFalse(folia.playerCalled(), "a rejected begin must not dispatch a region task");
        assertTrue(session.hasPendingIntent(uuid, gen),
                "a rejected begin must still publish a fail-closed intent for the active generation");
        assertFalse(session.isBoundView(uuid, gen, top));
        assertFalse(session.isFailedView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent topClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        InventoryClickEvent shiftClick = clickEvent(player, clickView(top), 27 + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick).setCancelled(true);

        InventoryDragEvent drag = dragEvent(player, clickView(top), Set.of(4));
        listener.onDrag(drag);
        verify(drag).setCancelled(true);

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);

        InventoryClickEvent bottomClick = clickEvent(player, clickView(top), 27 + 1, false);
        listener.onClick(bottomClick);
        verify(bottomClick, never()).setCancelled(Mockito.anyBoolean());
    }

    @Test
    void beginThrowsStaysFailClosedAndBlocksDangerousEvents() {
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        FakeGuiService gui = Mockito.spy(FakeGuiService.deferred());
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Mockito.doThrow(new RuntimeException("begin boom"))
                .when(gui).beginAsyncUpdate(Mockito.any(), Mockito.anyLong(), Mockito.anyInt());

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertFalse(outcome.isSuccess(), "a throwing begin must reject the render, never throw nor success");
        assertEquals(0, gui.pendingCallbackCount(), "a throwing begin must not enqueue a paint");
        assertFalse(folia.playerCalled(), "a throwing begin must not dispatch a region task");
        assertTrue(session.hasPendingIntent(uuid, gen),
                "a throwing begin must still publish a fail-closed intent for the active generation");

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent topClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(topClick);
        verify(topClick).setCancelled(true);

        InventoryClickEvent shiftClick = clickEvent(player, clickView(top), 27 + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick).setCancelled(true);

        InventoryDragEvent drag = dragEvent(player, clickView(top), Set.of(4, 27 + 1));
        listener.onDrag(drag);
        verify(drag).setCancelled(true);

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void beginFailureIntentConvergesOnCloseAndNewerReopenBinds() {
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        FakeGuiService gui = Mockito.spy(FakeGuiService.deferred());
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Mockito.doThrow(new RuntimeException("begin boom"))
                .when(gui).beginAsyncUpdate(Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
        assertFalse(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.hasPendingIntent(uuid, gen));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryCloseEvent closeEvent = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(closeEvent.getPlayer()).thenReturn(player);
        InventoryView closeView = clickView(top);
        Mockito.when(closeEvent.getView()).thenReturn(closeView);
        listener.onClose(closeEvent);

        assertFalse(session.hasTrackedSession(uuid), "close must converge the begin-failure generation");
        assertFalse(session.hasPendingIntent(uuid, gen),
                "close must clean the begin-failure intent");

        Mockito.reset(gui);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();
        assertTrue(session.renderLayout(
                uuid, newer, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, newer, top),
                "a newer reopen after the converged failure must bind normally");
        assertFalse(session.hasPendingIntent(uuid, newer));
    }

    @Test
    void staleBeginFailureGainsNoStaleIntent() {
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        FakeGuiService gui = Mockito.spy(FakeGuiService.deferred());
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout));
        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, Set.of());
        assertTrue(first.success());
        long stale = first.session().generation();

        Inventory top2 = Mockito.mock(Inventory.class);
        Mockito.when(top2.getSize()).thenReturn(27);
        InventoryView view2 = Mockito.mock(InventoryView.class);
        Mockito.when(view2.getTopInventory()).thenReturn(top2);
        Mockito.when(view2.getTitle()).thenReturn("Bank");
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());

        Mockito.doThrow(new RuntimeException("begin boom"))
                .when(gui).beginAsyncUpdate(Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
        V2BankGuiSession.RefreshOutcome staleOutcome =
                session.renderLayout(uuid, stale, layout, messages(), rendererWith(fullMaterials()));
        assertFalse(staleOutcome.isSuccess());
        assertFalse(session.hasPendingIntent(uuid, stale),
                "a stale begin failure must not invent a stale intent");
        assertEquals(0, gui.pendingCallbackCount(), "a stale begin failure must not enqueue a paint");
    }

    @Test
    void staleBeginFailureAfterReopenPollutesNothing() {
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        FakeGuiService gui = Mockito.spy(FakeGuiService.deferred());
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout));
        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, Set.of());
        assertTrue(first.success());
        long stale = first.session().generation();

        Inventory top2 = Mockito.mock(Inventory.class);
        Mockito.when(top2.getSize()).thenReturn(27);
        InventoryView view2 = Mockito.mock(InventoryView.class);
        Mockito.when(view2.getTopInventory()).thenReturn(top2);
        Mockito.when(view2.getTitle()).thenReturn("Bank");
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();

        Mockito.doThrow(new RuntimeException("begin boom"))
                .when(gui).beginAsyncUpdate(Mockito.any(), Mockito.anyLong(), Mockito.anyInt());
        V2BankGuiSession.RefreshOutcome staleOutcome =
                session.renderLayout(uuid, stale, layout, messages(), rendererWith(fullMaterials()));
        assertFalse(staleOutcome.isSuccess());
        assertFalse(session.hasPendingIntent(uuid, stale),
                "a stale begin failure must not invent a stale intent");
        assertFalse(session.hasPendingIntent(uuid, newer),
                "a stale begin failure must not bind an intent to the newer reopen");
        assertEquals(0, gui.pendingCallbackCount(), "a stale begin failure must not enqueue a paint");
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);

        // Without any intent for the newer generation, its same-title view is
        // not a bank event: risky clicks pass through and close converges nothing.
        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent topClick = clickEvent(player, clickView(top2), 11, false);
        listener.onClick(topClick);
        verify(topClick, never()).setCancelled(Mockito.anyBoolean());

        InventoryCloseEvent closeEvent = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(closeEvent.getPlayer()).thenReturn(player);
        InventoryView closeView = clickView(top2);
        Mockito.when(closeEvent.getView()).thenReturn(closeView);
        listener.onClose(closeEvent);
        assertTrue(session.hasTrackedSession(uuid),
                "a stale begin failure must not close the newer reopen");
        assertFalse(session.hasPendingIntent(uuid, newer));
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void successfulRenderStillBindsAndDispatches() {
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        FakeGuiService gui = FakeGuiService.deferred();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 11, false);
        listener.onClick(event);
        verify(event).setCancelled(true);
        assertEquals(1, useCase.withdrawCalls, "a bound action click must still dispatch");
    }
}
