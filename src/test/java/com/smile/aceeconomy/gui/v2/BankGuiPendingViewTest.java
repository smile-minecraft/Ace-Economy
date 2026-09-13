package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
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
 * Pending-view regression: an accepted async render whose renderer callback has not run yet
 * must not leave a fail-open shell. Until the paint completes, the exact pending shell is
 * guarded by generation plus inventory identity: top clicks, bottom shift-clicks and
 * top-touching drags on it are cancelled and never dispatched. A same-title/same-size
 * foreign inventory is a different object and must pass through untouched.
 */
class BankGuiPendingViewTest {

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

    private static InventoryClickEvent clickEvent(Player player, InventoryView view,
                                                  int rawSlot, boolean shift) {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(rawSlot);
        Mockito.when(event.isShiftClick()).thenReturn(shift);
        return event;
    }

    private static InventoryView clickView(Inventory top) {
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        return view;
    }

    @Test
    void pendingTopClickIsCancelledWithoutDispatch() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertTrue(outcome.isSuccess(), "accepted deferred render keeps the success return");
        assertEquals(1, gui.pendingCallbackCount(), "accepted update must queue exactly one paint");
        assertTrue(folia.playerCalled(),
                "pending capture must go through the player-region seam, never a raw Bukkit read");
        assertTrue(session.isPendingView(uuid, gen, top),
                "accepted-but-unflushed render must guard the exact pending shell");
        assertFalse(session.isBoundView(uuid, gen, top),
                "nothing is painted yet: no bound tag may exist");

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 11, false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void pendingBottomShiftClickIsCancelledWithoutDispatch() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 27 + 5, true);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void pendingTopTouchingDragIsCancelled() {
        FakeGuiService gui = FakeGuiService.deferred();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        InventoryView dragView = clickView(top);
        Mockito.when(event.getView()).thenReturn(dragView);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(4, 27 + 1));
        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void flushSuccessConvertsPendingToBoundAndDispatches() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));

        gui.flushCallbacks();

        assertFalse(session.isPendingView(uuid, gen, top),
                "a painted shell is bound, no longer pending");
        assertTrue(session.isBoundView(uuid, gen, top),
                "flushed paint must bind the exact generation/inventory pair");

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 11, false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(1, useCase.withdrawCalls,
                "a bound action click must still dispatch after the pending window");
    }

    @Test
    void pendingForeignInventoryPassesThrough() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));

        Inventory foreign = Mockito.mock(Inventory.class);
        Mockito.when(foreign.getSize()).thenReturn(27);
        assertFalse(session.isPendingView(uuid, gen, foreign),
                "a different inventory object must never match the pending tag");

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent click = clickEvent(player, clickView(foreign), 11, false);
        listener.onClick(click);
        verify(click, never()).setCancelled(Mockito.anyBoolean());

        InventoryDragEvent drag = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(drag.getWhoClicked()).thenReturn(player);
        InventoryView foreignDragView = clickView(foreign);
        Mockito.when(drag.getView()).thenReturn(foreignDragView);
        Mockito.when(drag.getRawSlots()).thenReturn(Set.of(11));
        listener.onDrag(drag);
        verify(drag, never()).setCancelled(Mockito.anyBoolean());

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void deferredFailureConvertsPendingToFailedGuard() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase, BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> partial = Map.of("CHEST", solidMaterial());
        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(partial)).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top),
                "a doomed paint still guards the shell while its callback is queued");

        gui.flushCallbacks();

        assertFalse(session.isPendingView(uuid, gen, top),
                "a failed paint is a failed view, no longer pending");
        assertTrue(session.isFailedView(uuid, gen, top),
                "async callback failure must bind the failed guard");
        assertFalse(session.isBoundView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = clickEvent(player, clickView(top), 11, false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void pendingConsumerCloseCleansBookkeeping() {
        FakeGuiService gui = FakeGuiService.deferred();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));
        assertTrue(session.hasTrackedSession(uuid));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        InventoryView closeView = clickView(top);
        Mockito.when(event.getView()).thenReturn(closeView);
        listener.onClose(event);

        assertFalse(session.hasTrackedSession(uuid));
        assertFalse(session.isPendingView(uuid, gen, top));
    }

    @Test
    void pendingBackendFirstUnlinkThenCloseCleansBookkeeping() {
        FakeGuiService gui = FakeGuiService.deferred();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, gen, top));
        assertTrue(gui.closeInventory(uuid, gen).isSuccess());

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        InventoryView closeView = clickView(top);
        Mockito.when(event.getView()).thenReturn(closeView);
        listener.onClose(event);

        assertFalse(session.hasTrackedSession(uuid));
        assertFalse(session.isPendingView(uuid, gen, top));
    }

    @Test
    void stalePendingCallbackNeverShadowsNewerReopen() {
        FakeGuiService gui = FakeGuiService.deferred();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, Set.of());
        assertTrue(first.success());
        long stale = first.session().generation();

        assertTrue(session.renderLayout(
                uuid, stale, layout, messages(), rendererWith(fullMaterials())).isSuccess());
        assertTrue(session.isPendingView(uuid, stale, top1));

        Inventory top2 = Mockito.mock(Inventory.class);
        Mockito.when(top2.getSize()).thenReturn(27);
        InventoryView view2 = Mockito.mock(InventoryView.class);
        Mockito.when(view2.getTopInventory()).thenReturn(top2);
        Mockito.when(view2.getTitle()).thenReturn("Bank");
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();

        assertTrue(session.renderLayout(
                uuid, newer, layout, messages(), rendererWith(fullMaterials())).isSuccess());

        gui.flushCallbacks();

        assertTrue(session.isBoundView(uuid, newer, top2),
                "newer reopen paint must still bind after flush");
        assertFalse(session.isPendingView(uuid, stale, top1),
                "the stale pending guard must not survive the newer reopen");
        assertFalse(session.isBoundView(uuid, stale, top1),
                "stale generation must never gain a tag from the newer paint");
        assertFalse(session.isFailedView(uuid, stale, top1),
                "the stale callback must not bind a failed guard over the reopen either");
    }

    @Test
    void pendingCaptureRunsOnPlayerRegionSeamBeforePaint() {
        FakeGuiService gui = FakeGuiService.deferred();
        DeferredFoliaContext folia = new DeferredFoliaContext();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, new StubBankGuiUseCase(), BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        assertTrue(session.renderLayout(
                uuid, gen, layout27(), messages(), rendererWith(fullMaterials())).isSuccess());

        assertEquals(1, folia.queuedCount(),
                "pending capture must be dispatched to the player region, not read inline");
        assertEquals(0, gui.pendingCallbackCount(),
                "capture and apply are one chained region task: the paint must not "
                        + "enqueue before the region capture ran");
        assertFalse(session.isPendingView(uuid, gen, top),
                "before the region capture runs no guard may exist yet");

        folia.flush();
        assertTrue(session.isPendingView(uuid, gen, top),
                "the region capture binds the exact pending shell before the paint runs");
        assertEquals(1, gui.pendingCallbackCount(),
                "the chained task enqueues the paint only after the capture bound the guard");
        assertFalse(session.isBoundView(uuid, gen, top));

        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, gen, top),
                "paint after capture converts the pending guard into the bound view");
        assertFalse(session.isPendingView(uuid, gen, top));
    }
}
