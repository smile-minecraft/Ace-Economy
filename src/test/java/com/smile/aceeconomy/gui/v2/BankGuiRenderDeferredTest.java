package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred async-render contract: an accepted async update whose renderer callback has not run
 * yet must not leave a bound view tag behind, and a callback that later fails must not leave
 * one either. The consumer listener only treats a bound tag as operable, so a tag created
 * before the paint completes would expose a blank shell.
 *
 * <p>The deferred fake answers {@code applyAsyncUpdate} with the real
 * {@code GuiResult.accepted} contract and queues the renderer; {@code flushCallbacks()}
 * drives the pending paint. A success returned before the flush therefore means "accepted",
 * never "painted": only the bound tag proves the paint completed.
 */
class BankGuiRenderDeferredTest {

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

    @Test
    void inlineFakeBindsImmediatelyWhileDeferredFakeDoesNot() {
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

        session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertTrue(session.isBoundView(uuid, gen, top),
                "inline fake runs the renderer synchronously, so the tag exists on return; "
                        + "this contrast documents why deferred tests need the deferred fake");
    }

    @Test
    void pendingDeferredCallbackLeavesNoViewTag() {
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

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));

        assertEquals(1, gui.pendingCallbackCount(), "accepted update must queue exactly one paint");
        // The pre-flush success means "accepted", not "painted": no tag may exist yet.
        assertTrue(outcome.isSuccess(), "accepted-before-callback keeps the current return value");
        assertFalse(session.isBoundView(uuid, gen, top),
                "callback not yet run: render must not bind a view tag early");
    }

    @Test
    void flushedDeferredSuccessBindsExactGenerationAndInventory() {
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

        session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(fullMaterials()));
        assertFalse(session.isBoundView(uuid, gen, top));
        gui.flushCallbacks();

        assertEquals(0, gui.pendingCallbackCount());
        assertTrue(session.isBoundView(uuid, gen, top),
                "flushed paint must bind the exact generation/inventory pair");
        Inventory other = Mockito.mock(Inventory.class);
        assertFalse(session.isBoundView(uuid, gen, other),
                "a different inventory object must never match the bound tag");
        assertFalse(session.isBoundView(uuid, gen + 1, top),
                "a different generation must never match the bound tag");
    }

    @Test
    void deferredPartialRenderLeavesNoOperableView() {
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

        // Only one of three buttons resolvable: the deferred paint is partial.
        Map<String, Material> materials = Map.of("CHEST", solidMaterial());
        session.renderLayout(uuid, gen, layout27(), messages(), rendererWith(materials));
        assertFalse(session.isBoundView(uuid, gen, top));
        gui.flushCallbacks();

        assertFalse(session.isBoundView(uuid, gen, top),
                "partial deferred paint must not bind a view tag");
    }

    @Test
    void deferredThrowingRendererLeavesNoOperableView() {
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

        BankGuiRenderer failing = Mockito.mock(BankGuiRenderer.class);
        Mockito.when(failing.plan(Mockito.any())).thenCallRealMethod();
        Mockito.doThrow(new RuntimeException("setItem boom"))
                .when(failing).renderInto(Mockito.any(), Mockito.any(), Mockito.any());

        session.renderLayout(uuid, gen, layout27(), messages(), failing);
        assertFalse(session.isBoundView(uuid, gen, top));
        gui.flushCallbacks();

        assertFalse(session.isBoundView(uuid, gen, top),
                "throwing deferred paint must not bind a view tag");
    }

    @Test
    void deferredFatalErrorPropagatesAndLeavesNoViewTag() {
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

        BankGuiRenderer fatal = Mockito.mock(BankGuiRenderer.class);
        Mockito.when(fatal.plan(Mockito.any())).thenCallRealMethod();
        Mockito.doThrow(new AssertionError("vm fatal"))
                .when(fatal).renderInto(Mockito.any(), Mockito.any(), Mockito.any());

        session.renderLayout(uuid, gen, layout27(), messages(), fatal);
        assertFalse(session.isBoundView(uuid, gen, top));

        // The fatal must surface from the deferred callback, never swallowed as success.
        assertThrows(AssertionError.class, gui::flushCallbacks);
        assertFalse(session.isBoundView(uuid, gen, top),
                "fatal deferred paint must not leave a view tag behind");
    }

    @Test
    void deferredStaleGenerationRejectedAndNewerReopenStillBinds() {
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
        assertFalse(staleOutcome.isSuccess(), "stale generation must reject before queueing");
        assertEquals("stale-generation", staleOutcome.errorCode());
        assertEquals(0, gui.pendingCallbackCount(), "stale render must not queue a paint");

        assertTrue(session.renderLayout(
                uuid, newer, layout, messages(), rendererWith(fullMaterials())).isSuccess());
        assertFalse(session.isBoundView(uuid, newer, top2));
        gui.flushCallbacks();
        assertTrue(session.isBoundView(uuid, newer, top2),
                "newer reopen paint must still bind after flush");
        assertFalse(session.isBoundView(uuid, stale, top1),
                "stale generation must never gain a tag from the newer paint");
    }
}
