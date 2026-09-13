package com.smile.aceeconomy.gui.v2;

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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ownership regression: a same-title/same-size inventory from another plugin is a different
 * {@link Inventory} object and must never be treated as the bank view. Clicks, drags and
 * closes on it must not cancel and must never reach the business layer.
 */
class BankGuiForeignViewTest {

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

    private static final class Harness {
        final BankGuiLayout layout = layout27();
        final FakeGuiService gui = FakeGuiService.available();
        final RecordingFoliaContext folia = new RecordingFoliaContext();
        final StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        final V2BankGuiSession session;
        final BankGuiClickListener listener;
        final UUID uuid = UUID.randomUUID();
        final Player player = Mockito.mock(Player.class);
        final Inventory bankTop = mockTop();
        final Inventory foreignTop = mockTop();

        Harness() {
            session = new V2BankGuiSession(gui, folia, useCase, BankGuiActions.resolver(layout));
            listener = new BankGuiClickListener(session);
            PlayerInventory inv = Mockito.mock(PlayerInventory.class);
            Mockito.when(player.getUniqueId()).thenReturn(uuid);
            Mockito.when(player.getInventory()).thenReturn(inv);
            Mockito.when(inv.firstEmpty()).thenReturn(0);
            Mockito.when(inv.addItem(Mockito.any(ItemStack.class))).thenReturn(new HashMap<>());
            InventoryView bankView = mockView(bankTop);
            Mockito.when(player.getOpenInventory()).thenReturn(bankView);
            V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
            assertTrue(open.success());
            Map<String, Material> materials = Map.of(
                    "CHEST", solidMaterial(), "PAPER", solidMaterial(), "BARRIER", solidMaterial());
            BankGuiRenderer renderer = new BankGuiRenderer(
                    material -> {
                        ItemStack stack = Mockito.mock(ItemStack.class);
                        Mockito.when(stack.getItemMeta()).thenReturn(Mockito.mock(ItemMeta.class));
                        return stack;
                    },
                    materials::get);
            ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
            Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                    .thenAnswer(invocation -> Component.text("t:" + invocation.getArgument(0)));
            assertTrue(session.renderLayout(
                    uuid, open.session().generation(), layout, messages, renderer).isSuccess());
        }

        /** Same title and size as the bank view, but a different inventory object. */
        InventoryView foreignView() {
            return mockView(foreignTop);
        }
    }

    private static Inventory mockTop() {
        Inventory top = Mockito.mock(Inventory.class);
        Mockito.when(top.getSize()).thenReturn(27);
        return top;
    }

    private static InventoryView mockView(Inventory top) {
        InventoryView view = Mockito.mock(InventoryView.class);
        Mockito.when(view.getTopInventory()).thenReturn(top);
        Mockito.when(view.getTitle()).thenReturn("Bank");
        return view;
    }

    @Test
    void foreignTopClickIsNotCancelledAndNeverDispatched() {
        Harness h = new Harness();
        InventoryView foreign = h.foreignView();
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(h.player);
        Mockito.when(event.getView()).thenReturn(foreign);
        Mockito.when(event.getRawSlot()).thenReturn(11);
        Mockito.when(event.isShiftClick()).thenReturn(false);

        h.listener.onClick(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
        assertEquals(0, h.useCase.withdrawCalls,
                "a click on another plugin's inventory must never reach the business layer");
        assertTrue(h.session.activeSession(h.uuid).isPresent());
    }

    @Test
    void foreignDragIsNotCancelled() {
        Harness h = new Harness();
        InventoryView foreign = h.foreignView();
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        Mockito.when(event.getWhoClicked()).thenReturn(h.player);
        Mockito.when(event.getView()).thenReturn(foreign);
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(11));

        h.listener.onDrag(event);

        verify(event, never()).setCancelled(Mockito.anyBoolean());
    }

    @Test
    void foreignCloseDoesNotDropTheBankSession() {
        Harness h = new Harness();
        InventoryView foreign = h.foreignView();
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(h.player);
        Mockito.when(event.getView()).thenReturn(foreign);

        h.listener.onClose(event);

        assertTrue(h.session.activeSession(h.uuid).isPresent(),
                "closing another plugin's GUI must not close the bank session");
        assertTrue(h.session.hasTrackedSession(h.uuid));
    }
}
