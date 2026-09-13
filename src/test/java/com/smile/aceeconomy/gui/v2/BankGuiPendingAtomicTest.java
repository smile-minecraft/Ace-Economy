package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

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
import org.jetbrains.annotations.NotNull;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Atomic capture-apply chain: the paint must never enqueue before the
 * player-region capture ran, the intent-only window must stay fail-closed
 * without dispatching, and a rejected capture dispatch must reject instead of
 * leaving a queued paint behind fail-open.
 *
 * <p>Narrow documented trade-off: while only the intent (not yet the exact
 * guard) is active, a same-title/same-size foreign inventory cannot be told
 * apart, so its risky clicks are cancelled too. Once the capture pins the
 * exact shell, foreign views pass through untouched again.
 */
class BankGuiPendingAtomicTest {

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
        InventoryView eventView = view;
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(eventView);
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

    private static final class ThrowingFoliaContext implements FoliaContextExecutor {
        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            throw new RuntimeException("region scheduler rejected");
        }

        @Override
        public void runForEntity(@NotNull org.bukkit.entity.Entity entity, @NotNull Runnable action) {
            throw new RuntimeException("region scheduler rejected");
        }

        @Override
        public void runAtLocation(@NotNull org.bukkit.Location location, @NotNull Runnable action) {
            throw new RuntimeException("region scheduler rejected");
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
            throw new RuntimeException("region scheduler rejected");
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
            throw new RuntimeException("region scheduler rejected");
        }
    }

    @Test
    void applyMustNotEnqueueBeforeRegionCaptureRuns() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertEquals(0, gui.pendingCallbackCount(),
                "paint must not enqueue before the region capture ran");
        assertTrue(session.hasPendingIntent(uuid, gen),
                "the intent is published synchronously so the window stays fail-closed");
        assertFalse(session.isPendingView(uuid, gen, top),
                "no exact guard may exist before the region capture ran");
    }

    @Test
    void windowBeforeCaptureFlushMustStayFailClosed() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView eventView = clickView(top);
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(eventView);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void rejectedCaptureDispatchMustNotQueuePaintFailOpen() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new ThrowingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertTrue(!outcome.isSuccess(), "rejected capture dispatch must reject the render");
        assertEquals(0, gui.pendingCallbackCount(),
                "rejected capture must not leave a queued paint behind");
    }

    @Test
    void captureFlushBindsPendingThenPaintBindsBoundInOrder() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertEquals(0, gui.pendingCallbackCount());

        BankGuiClickListener listener = new BankGuiClickListener(session);

        // Intent-only window: dangerous clicks cancelled exactly once, no dispatch.
        InventoryClickEvent topClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(topClick);
        verify(topClick, times(1)).setCancelled(true);

        InventoryClickEvent shiftClick = clickEvent(player, clickView(top), 27 + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick, times(1)).setCancelled(true);

        listener.onDrag(dragEvent(player, clickView(top), Set.of(4, 27 + 1)));
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);

        // Plain bottom clicks still pass through in the intent window.
        InventoryClickEvent bottomClick = clickEvent(player, clickView(top), 27 + 1, false);
        listener.onClick(bottomClick);
        verify(bottomClick, never()).setCancelled(Mockito.anyBoolean());

        // Region capture binds the exact guard and only then enqueues the paint.
        folia.flush();
        assertTrue(session.isPendingView(uuid, gen, top));
        assertTrue(session.hasExactGuard(uuid, gen));
        assertEquals(1, gui.pendingCallbackCount());
        assertFalse(session.isBoundView(uuid, gen, top));

        InventoryClickEvent exactClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(exactClick);
        verify(exactClick, times(1)).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);

        // Paint converts pending into bound; the action click dispatches again.
        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, gen, top));
        assertFalse(session.isPendingView(uuid, gen, top));
        assertFalse(session.hasPendingIntent(uuid, gen));

        InventoryClickEvent boundClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(boundClick);
        verify(boundClick, times(1)).setCancelled(true);
        folia.flush();
        assertEquals(1, useCase.withdrawCalls);
    }

    @Test
    void rejectedDispatchStaysFailClosedUntilCloseAndReopen() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new ThrowingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertFalse(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.hasPendingIntent(uuid, gen),
                "rejected dispatch keeps the intent fail-closed instead of clearing it");
        assertFalse(session.hasExactGuard(uuid, gen));
        assertFalse(session.isBoundView(uuid, gen, top));
        assertFalse(session.isFailedView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent topClick = clickEvent(player, clickView(top), 11, false);
        listener.onClick(topClick);
        verify(topClick, times(1)).setCancelled(true);

        InventoryClickEvent shiftClick = clickEvent(player, clickView(top), 27 + 5, true);
        listener.onClick(shiftClick);
        verify(shiftClick, times(1)).setCancelled(true);

        listener.onDrag(dragEvent(player, clickView(top), Set.of(4)));
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);

        // Consumer close converges the fail-closed state through the intent.
        InventoryView closeView = clickView(top);
        InventoryCloseEvent closeEvent = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(closeEvent.getPlayer()).thenReturn(player);
        Mockito.when(closeEvent.getView()).thenReturn(closeView);
        listener.onClose(closeEvent);
        assertFalse(session.hasTrackedSession(uuid));
        assertFalse(session.hasPendingIntent(uuid, gen));

        // A reopen on a working region chain binds the bound view again.
        V2BankGuiSession reopened = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        V2BankGuiSession.OpenOutcome second = reopened.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();
        assertTrue(reopened.renderLayout(
                uuid, newer, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        gui.flushCallbacks();
        assertTrue(reopened.isBoundView(uuid, newer, top));
    }

    @Test
    void intentOnlyWindowCancelsForeignButExactRestoresPassThrough() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());

        Inventory foreign = Mockito.mock(Inventory.class);
        Mockito.when(foreign.getSize()).thenReturn(27);
        BankGuiClickListener listener = new BankGuiClickListener(session);

        // Narrow documented trade-off: intent-only cannot tell the foreign
        // same-title/same-size shell apart, so its risky click is cancelled.
        InventoryClickEvent foreignClick = clickEvent(player, clickView(foreign), 11, false);
        listener.onClick(foreignClick);
        verify(foreignClick, times(1)).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);

        // Once the capture pins the exact shell, foreign passes through again.
        folia.flush();
        assertTrue(session.hasExactGuard(uuid, gen));
        InventoryClickEvent foreignAfter = clickEvent(player, clickView(foreign), 11, false);
        listener.onClick(foreignAfter);
        verify(foreignAfter, never()).setCancelled(Mockito.anyBoolean());

        InventoryDragEvent foreignDrag = dragEvent(player, clickView(foreign), Set.of(11));
        listener.onDrag(foreignDrag);
        verify(foreignDrag, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void staleGenerationGainsNoIntentAndNewerReopenConverges() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, new StubBankGuiUseCase(), BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
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

        V2BankGuiSession.RefreshOutcome staleOutcome =
                session.renderLayout(uuid, stale, layout, messages(), rendererWith(fullMaterials()));
        assertFalse(staleOutcome.isSuccess());
        assertFalse(session.hasPendingIntent(uuid, stale),
                "a stale render must not publish an intent");
        assertEquals(0, gui.pendingCallbackCount());

        assertTrue(session.renderLayout(
                uuid, newer, layout, messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.hasPendingIntent(uuid, newer));
        assertEquals(1, folia.queuedCount());

        folia.flush();
        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, newer, top2));
        assertFalse(session.isBoundView(uuid, stale, top1));
        assertFalse(session.isPendingView(uuid, stale, top1));
        assertFalse(session.isFailedView(uuid, stale, top1));
        assertFalse(session.hasPendingIntent(uuid, newer));
    }

    @Test
    void deferredPartialPaintConvertsIntentToFailedGuard() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> partial = Map.of("CHEST", solidMaterial());
        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(partial)).isSuccess());
        assertTrue(session.hasPendingIntent(uuid, gen));

        folia.flush();
        assertTrue(session.isPendingView(uuid, gen, top));
        assertEquals(1, gui.pendingCallbackCount());

        gui.flushCallbacks();
        assertTrue(session.isFailedView(uuid, gen, top));
        assertFalse(session.isPendingView(uuid, gen, top));
        assertFalse(session.isBoundView(uuid, gen, top));
        assertFalse(session.hasPendingIntent(uuid, gen));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 11, false);
        listener.onClick(event);
        verify(event, times(1)).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);

        Inventory foreign = Mockito.mock(Inventory.class);
        Mockito.when(foreign.getSize()).thenReturn(27);
        InventoryClickEvent foreignClick = clickEvent(player, clickView(foreign), 11, false);
        listener.onClick(foreignClick);
        verify(foreignClick, never()).setCancelled(Mockito.anyBoolean());
    }
}
