package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;
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

import java.lang.reflect.Method;
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
 * Regression for review reject: a failed render must not leave an operable empty GUI,
 * and stale open cleanup must drop the stale view tag without touching a newer reopen.
 */
class BankGuiRenderFailureTest {

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

    @Test
    void incompleteRenderReportsFailureAndLeavesNoOperableView() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        // Only one of three buttons resolvable: partial paint must not count as success.
        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials));

        assertFalse(outcome.isSuccess(),
                "partial render must not report success, got success");
        assertFalse(session.isBoundView(uuid, gen, top),
                "failed render must not bind a view tag for the generation");

        // The failed shell stays inoperable: the exact failed view is cancelled.
        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        listener.onClick(event);
        verify(event).setCancelled(true);
    }

    @Test
    void failedViewTopClickIsCancelledWithoutDispatch() {
        FakeGuiService gui = FakeGuiService.available();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase,
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());
        assertFalse(session.isBoundView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void failedViewShiftClickFromBottomIsCancelledWithoutDispatch() {
        FakeGuiService gui = FakeGuiService.available();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase,
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(27 + 5);
        Mockito.when(event.isShiftClick()).thenReturn(true);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void failedViewTopTouchingDragIsCancelled() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(4, 27 + 1));
        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void foreignInventoryIsNotCancelledAfterRenderFailure() {
        FakeGuiService gui = FakeGuiService.available();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase,
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());

        Inventory foreign = Mockito.mock(Inventory.class);
        Mockito.when(foreign.getSize()).thenReturn(27);
        InventoryView foreignView = Mockito.mock(InventoryView.class);
        Mockito.when(foreignView.getTopInventory()).thenReturn(foreign);
        Mockito.when(foreignView.getTitle()).thenReturn("Bank");

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(foreignView);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void failedViewConsumerCloseCleansLocalBookkeeping() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());
        assertTrue(session.hasTrackedSession(uuid));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        listener.onClose(event);

        assertFalse(session.hasTrackedSession(uuid));
    }

    @Test
    void failedViewBackendFirstUnlinkThenCloseCleansLocalBookkeeping() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials))
                .isSuccess());
        assertTrue(session.hasTrackedSession(uuid));

        assertTrue(gui.closeInventory(uuid, gen).isSuccess());

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        listener.onClose(event);

        assertFalse(session.hasTrackedSession(uuid));
    }

    @Test
    void deferredFailedPaintGuardsShellAfterFlush() {
        FakeGuiService gui = FakeGuiService.deferred();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), useCase,
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials));
        assertFalse(session.isBoundView(uuid, gen, top));
        gui.flushCallbacks();
        assertFalse(session.isBoundView(uuid, gen, top));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);
        listener.onClick(event);

        verify(event).setCancelled(true);
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
    }

    @Test
    void staleCloseOfFailedViewKeepsNewerReopen() {
        FakeGuiService gui = FakeGuiService.available();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, Set.of());
        assertTrue(first.success());

        Map<String, Material> partial = Map.of("CHEST", solidMaterial());
        assertFalse(session.renderLayout(
                uuid, first.session().generation(), layout, messages(), rendererWith(partial))
                .isSuccess());

        Inventory top2 = Mockito.mock(Inventory.class);
        Mockito.when(top2.getSize()).thenReturn(27);
        InventoryView view2 = Mockito.mock(InventoryView.class);
        Mockito.when(view2.getTopInventory()).thenReturn(top2);
        Mockito.when(view2.getTitle()).thenReturn("Bank");
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();
        Map<String, Material> materials = Map.of(
                "CHEST", solidMaterial(), "PAPER", solidMaterial(), "BARRIER", solidMaterial());
        assertTrue(session.renderLayout(uuid, newer, layout, messages(), rendererWith(materials))
                .isSuccess());
        assertTrue(session.isBoundView(uuid, newer, top2));

        BankGuiClickListener listener = new BankGuiClickListener(session);
        InventoryView staleView = Mockito.mock(InventoryView.class);
        Mockito.when(staleView.getTopInventory()).thenReturn(top1);
        Mockito.when(staleView.getTitle()).thenReturn("Bank");
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(staleView);
        listener.onClose(event);

        assertTrue(session.hasTrackedSession(uuid));
        assertTrue(session.isBoundView(uuid, newer, top2));
    }

    @Test
    void throwingRendererReportsFailureAndLeavesNoOperableView() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        BankGuiRenderer failing = Mockito.mock(BankGuiRenderer.class);
        Mockito.when(failing.plan(Mockito.any())).thenCallRealMethod();
        // Simulate a per-slot Bukkit failure during inventory writes.
        Mockito.doThrow(new RuntimeException("setItem boom"))
                .when(failing).renderInto(
                        Mockito.any(), Mockito.any(), Mockito.any());

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), failing);
        assertFalse(outcome.isSuccess(), "throwing render must not report success");
        assertEquals("render.failed", outcome.errorCode());
        assertFalse(session.isBoundView(uuid, gen, top));
    }

    @Test
    void fatalRendererErrorNeverReportsSuccess() {
        FakeGuiService gui = FakeGuiService.available();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout27()));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        BankGuiRenderer fatal = Mockito.mock(BankGuiRenderer.class);
        Mockito.when(fatal.plan(Mockito.any())).thenCallRealMethod();
        Mockito.doThrow(new AssertionError("vm fatal"))
                .when(fatal).renderInto(
                        Mockito.any(), Mockito.any(), Mockito.any());

        V2BankGuiSession.RefreshOutcome outcome;
        try {
            outcome = session.renderLayout(uuid, gen, layout27(), messages(), fatal);
        } catch (Throwable t) {
            // Propagating the fatal is acceptable; swallowing it as success is not.
            assertFalse(session.isBoundView(uuid, gen, top));
            return;
        }
        assertFalse(outcome.isSuccess(), "fatal render error must never report success");
        assertFalse(session.isBoundView(uuid, gen, top));
    }

    @Test
    void staleOpenCleanupRemovesStaleViewTag() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        GuiSession stale = open.session();
        Map<String, Material> materials = Map.of(
                "CHEST", solidMaterial(), "PAPER", solidMaterial(), "BARRIER", solidMaterial());
        assertTrue(session.renderLayout(
                uuid, stale.generation(), layout, messages(), rendererWith(materials)).isSuccess());
        assertTrue(session.isBoundView(uuid, stale.generation(), top));

        Method dropStale = V2BankGuiSession.class.getDeclaredMethod(
                "dropStaleBookkeeping", UUID.class, GuiSession.class);
        dropStale.setAccessible(true);
        dropStale.invoke(session, uuid, stale);

        assertFalse(session.isBoundView(uuid, stale.generation(), top),
                "stale cleanup must drop the view tag bound to the stale generation");
    }

    @Test
    void staleCleanupKeepsNewerReopenViewTag() throws Exception {
        FakeGuiService gui = FakeGuiService.available();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, new RecordingFoliaContext(), new StubBankGuiUseCase(),
                BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top1 = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top1, 27);
        V2BankGuiSession.OpenOutcome first = session.open(player, "Bank", 27, Set.of());
        assertTrue(first.success());
        GuiSession old = first.session();

        Inventory top2 = Mockito.mock(Inventory.class);
        Mockito.when(top2.getSize()).thenReturn(27);
        InventoryView view2 = Mockito.mock(InventoryView.class);
        Mockito.when(view2.getTopInventory()).thenReturn(top2);
        Mockito.when(view2.getTitle()).thenReturn("Bank");
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome second = session.open(player, "Bank", 27, Set.of());
        assertTrue(second.success());
        long newer = second.session().generation();

        Map<String, Material> materials = Map.of(
                "CHEST", solidMaterial(), "PAPER", solidMaterial(), "BARRIER", solidMaterial());
        assertTrue(session.renderLayout(
                uuid, newer, layout, messages(), rendererWith(materials)).isSuccess());
        assertTrue(session.isBoundView(uuid, newer, top2));

        Method dropStale = V2BankGuiSession.class.getDeclaredMethod(
                "dropStaleBookkeeping", UUID.class, GuiSession.class);
        dropStale.setAccessible(true);
        dropStale.invoke(session, uuid, old);

        assertTrue(session.isBoundView(uuid, newer, top2),
                "stale cleanup for the old generation must keep the newer reopen tag");
        assertTrue(session.hasTrackedSession(uuid));
    }
}
