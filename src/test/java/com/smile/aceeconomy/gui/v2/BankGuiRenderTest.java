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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render contract for the Java bank GUI: the configured material / name-key / lore-keys must
 * reach the matching top-inventory slots through the generation-bound async-update path, stale
 * generations must never paint, and unresolvable entries must not blank the whole view.
 */
class BankGuiRenderTest {

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
        actions.put("filler", new BankGuiLayout.SlotConfig(
                "filler", 0, BankGuiLayout.ActionType.NONE, 0L, null,
                "", "", List.of()));
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

    /** Renderer stub whose materials come from a fixed map and whose stacks are recorded mocks. */
    private static final class RecordingRenderer {
        final Map<String, Material> materials = new LinkedHashMap<>();
        final List<ItemStack> created = new ArrayList<>();
        final BankGuiRenderer renderer = new BankGuiRenderer(
                material -> {
                    ItemStack stack = Mockito.mock(ItemStack.class);
                    ItemMeta meta = Mockito.mock(ItemMeta.class);
                    Mockito.when(stack.getItemMeta()).thenReturn(meta);
                    created.add(stack);
                    return stack;
                },
                materials::get);
    }

    @Test
    void planCoversActionSlotsAndSkipsNone() {
        RecordingRenderer rec = new RecordingRenderer();
        Map<Integer, BankGuiLayout.SlotConfig> plan = rec.renderer.plan(layout27());
        assertEquals(Set.of(4, 11, 15), plan.keySet(),
                "every action slot must be planned and NONE fillers skipped");
    }

    @Test
    void buildButtonAppliesMaterialNameAndLore() {
        RecordingRenderer rec = new RecordingRenderer();
        Material chest = solidMaterial();
        rec.materials.put("CHEST", chest);
        BankGuiLayout.SlotConfig slot = layout27().actionForSlot(4).orElseThrow();

        ItemStack stack = rec.renderer.buildButton(
                slot, Component.text("Deposit"), List.of(Component.text("Lore")));
        assertNotNull(stack, "a resolvable material must build a button");
        Mockito.verify(stack.getItemMeta()).displayName(Component.text("Deposit"));
        Mockito.verify(stack.getItemMeta()).lore(List.of(Component.text("Lore")));
    }

    @Test
    void buildButtonWithUnknownMaterialLeavesSlotEmpty() {
        RecordingRenderer rec = new RecordingRenderer();
        BankGuiLayout.SlotConfig slot = layout27().actionForSlot(4).orElseThrow();
        assertNull(rec.renderer.buildButton(slot, Component.text("x"), List.of()),
                "an unresolvable material must not build a button");
        assertTrue(rec.created.isEmpty(), "no stack may be created for an unknown material");
    }

    @Test
    void renderIntoWritesConfiguredButtonsToTopSlots() {
        RecordingRenderer rec = new RecordingRenderer();
        rec.materials.put("CHEST", solidMaterial());
        rec.materials.put("PAPER", solidMaterial());
        rec.materials.put("BARRIER", solidMaterial());
        Inventory top = Mockito.mock(Inventory.class);

        Set<Integer> written = rec.renderer.renderInto(top, layout27(), messages());

        assertEquals(Set.of(4, 11, 15), written);
        assertEquals(3, rec.created.size());
        for (ItemStack stack : rec.created) {
            Mockito.verify(stack).setItemMeta(Mockito.any(ItemMeta.class));
        }
        Mockito.verify(top).setItem(Mockito.eq(4), Mockito.eq(rec.created.get(0)));
        Mockito.verify(top).setItem(Mockito.eq(11), Mockito.eq(rec.created.get(1)));
        Mockito.verify(top).setItem(Mockito.eq(15), Mockito.eq(rec.created.get(2)));
        Mockito.verify(top, Mockito.never()).setItem(Mockito.eq(0), Mockito.any());
    }

    @Test
    void renderIntoSkipsUnresolvableMaterialWithoutBlankingOthers() {
        RecordingRenderer rec = new RecordingRenderer();
        rec.materials.put("CHEST", solidMaterial());
        // PAPER and BARRIER deliberately unmapped.
        Inventory top = Mockito.mock(Inventory.class);

        Set<Integer> written = rec.renderer.renderInto(top, layout27(), messages());

        assertEquals(Set.of(4), written);
        Mockito.verify(top).setItem(Mockito.eq(4), Mockito.any(ItemStack.class));
        Mockito.verify(top, Mockito.never())
                .setItem(Mockito.eq(11), Mockito.any(ItemStack.class));
        Mockito.verify(top, Mockito.never())
                .setItem(Mockito.eq(15), Mockito.any(ItemStack.class));
    }

    /**
     * Withdraw buttons must render with their configured amount as the {@code {amount}} template
     * var, so the locale lore can never drift from the actual withdraw amount in config. Deposit
     * and close buttons carry no amount var.
     */
    @Test
    void withdrawButtonsRenderWithConfiguredAmountVar() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
        actions.put("withdraw100", new BankGuiLayout.SlotConfig(
                "withdraw100", 11, BankGuiLayout.ActionType.WITHDRAW, 100L, "dollar",
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("withdraw500", new BankGuiLayout.SlotConfig(
                "withdraw500", 13, BankGuiLayout.ActionType.WITHDRAW, 500L, "dollar",
                "PAPER", "gui.bank-withdraw-name", List.of("gui.bank-withdraw-lore")));
        actions.put("close", new BankGuiLayout.SlotConfig(
                "close", 15, BankGuiLayout.ActionType.CLOSE, 0L, null,
                "BARRIER", "gui.bank-close-name", List.of()));
        BankGuiLayout layout = BankGuiLayout.of(true, "gui.bank-title", 27, actions);

        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        List<String> rendered = new ArrayList<>();
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> {
                    rendered.add(invocation.getArgument(0, String.class) + "="
                            + invocation.getArgument(1, Map.class));
                    return Component.text("t:" + invocation.getArgument(0));
                });

        RecordingRenderer rec = new RecordingRenderer();
        rec.materials.put("CHEST", solidMaterial());
        rec.materials.put("PAPER", solidMaterial());
        rec.materials.put("BARRIER", solidMaterial());
        Inventory top = Mockito.mock(Inventory.class);

        Set<Integer> written = rec.renderer.renderInto(top, layout, messages);

        assertEquals(Set.of(4, 11, 13, 15), written);
        assertTrue(rendered.contains("gui.bank-withdraw-lore={amount=100}"),
                "withdraw100 lore must render with the configured 100 amount, got: " + rendered);
        assertTrue(rendered.contains("gui.bank-withdraw-lore={amount=500}"),
                "withdraw500 lore must render with the configured 500 amount, got: " + rendered);
        assertTrue(rendered.contains("gui.bank-deposit-lore={}"),
                "non-withdraw buttons must not carry an amount var, got: " + rendered);
    }

    private static Player viewPlayer(UUID uuid, Inventory top, int topSize) {
        Player player = Mockito.mock(Player.class);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.getInventory()).thenReturn(inv);
        Mockito.when(player.getOpenInventory()).thenReturn(view);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(top.getSize()).thenReturn(topSize);
        return player;
    }

    @Test
    void renderLayoutPaintsButtonsThroughAsyncUpdate() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        RecordingRenderer rec = new RecordingRenderer();
        rec.materials.put("CHEST", solidMaterial());
        rec.materials.put("PAPER", solidMaterial());
        rec.materials.put("BARRIER", solidMaterial());

        V2BankGuiSession.RefreshOutcome outcome =
                session.renderLayout(uuid, gen, layout, messages(), rec.renderer);
        assertTrue(outcome.isSuccess(), "render on the active generation must succeed");
        Mockito.verify(top).setItem(Mockito.eq(4), Mockito.any(ItemStack.class));
        Mockito.verify(top).setItem(Mockito.eq(11), Mockito.any(ItemStack.class));
        Mockito.verify(top).setItem(Mockito.eq(15), Mockito.any(ItemStack.class));
    }

    @Test
    void renderLayoutWithStaleGenerationNeverPaints() {
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        BankGuiLayout layout = layout27();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout));
        UUID uuid = UUID.randomUUID();
        Inventory top = Mockito.mock(Inventory.class);
        Player player = viewPlayer(uuid, top, 27);
        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen = open.session().generation();

        RecordingRenderer rec = new RecordingRenderer();
        rec.materials.put("CHEST", solidMaterial());

        V2BankGuiSession.RefreshOutcome stale =
                session.renderLayout(uuid, gen + 1, layout, messages(), rec.renderer);
        assertFalse(stale.isSuccess());
        assertEquals("stale-generation", stale.errorCode());
        assertTrue(rec.created.isEmpty(), "a stale render must never build items");
        Mockito.verify(top, Mockito.never()).setItem(Mockito.anyInt(), Mockito.any());
    }
}
