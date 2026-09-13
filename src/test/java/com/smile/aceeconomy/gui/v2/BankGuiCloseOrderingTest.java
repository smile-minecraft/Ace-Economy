package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiResult;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.FakeGuiService;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Close-ordering regression: when the backend session is already gone (the backend's own
 * close listener ran first on the same event), the consumer close must still clear the
 * local bookkeeping bound to that view/generation — and a stale close for an old view
 * must never delete a newer reopen.
 */
class BankGuiCloseOrderingTest {

    private static BankGuiLayout layout27() {
        Map<String, BankGuiLayout.SlotConfig> actions = new LinkedHashMap<>();
        actions.put("deposit", new BankGuiLayout.SlotConfig(
                "deposit", 4, BankGuiLayout.ActionType.DEPOSIT, 0L, null,
                "CHEST", "gui.bank-deposit-name", List.of("gui.bank-deposit-lore")));
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

    private static BankGuiRenderer renderer() {
        Map<String, Material> materials = Map.of("CHEST", solidMaterial(), "BARRIER", solidMaterial());
        return new BankGuiRenderer(
                material -> {
                    ItemStack stack = Mockito.mock(ItemStack.class);
                    Mockito.when(stack.getItemMeta()).thenReturn(Mockito.mock(ItemMeta.class));
                    return stack;
                },
                materials::get);
    }

    private static ConfigLangAdapter messages() {
        ConfigLangAdapter messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> Component.text("t:" + invocation.getArgument(0)));
        return messages;
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

    private static InventoryCloseEvent closeEvent(Player player, InventoryView view) {
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        return event;
    }

    @Test
    void closeAfterBackendUnlinkStillCleansLocalBookkeepingWithoutKillingReopen() {
        BankGuiLayout layout = layout27();
        FakeGuiService gui = FakeGuiService.available();
        RecordingFoliaContext folia = new RecordingFoliaContext();
        StubBankGuiUseCase useCase = new StubBankGuiUseCase();
        V2BankGuiSession session = new V2BankGuiSession(
                gui, folia, useCase, BankGuiActions.resolver(layout));
        BankGuiClickListener listener = new BankGuiClickListener(session);

        UUID uuid = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.getInventory()).thenReturn(Mockito.mock(PlayerInventory.class));
        Inventory top1 = mockTop();
        InventoryView view1 = mockView(top1);
        Mockito.when(player.getOpenInventory()).thenReturn(view1);

        V2BankGuiSession.OpenOutcome open = session.open(player, "Bank", 27, Set.of());
        assertTrue(open.success());
        long gen1 = open.session().generation();
        assertTrue(session.renderLayout(uuid, gen1, layout, messages(), renderer()).isSuccess());
        assertTrue(session.hasTrackedSession(uuid));

        // The backend's own close listener unlinks first on the same event.
        GuiResult unlinked = gui.closeInventory(uuid, gen1);
        assertTrue(unlinked.isSuccess());
        assertTrue(session.activeSession(uuid).isEmpty());
        assertTrue(session.hasTrackedSession(uuid),
                "local bookkeeping survives the backend unlink until the consumer close runs");

        // The close event still carries the bound bank view: local cleanup must run.
        listener.onClose(closeEvent(player, view1));
        assertFalse(session.hasTrackedSession(uuid),
                "a close for the bound view must clear local state even when the backend is gone");

        // A newer reopen binds a new generation and a new view object.
        Inventory top2 = mockTop();
        InventoryView view2 = mockView(top2);
        Mockito.when(player.getOpenInventory()).thenReturn(view2);
        V2BankGuiSession.OpenOutcome reopen = session.open(player, "Bank", 27, Set.of());
        assertTrue(reopen.success());
        long gen2 = reopen.session().generation();
        assertTrue(session.renderLayout(uuid, gen2, layout, messages(), renderer()).isSuccess());
        assertTrue(session.hasTrackedSession(uuid));

        // A replayed close for the old view must not delete the reopen.
        listener.onClose(closeEvent(player, view1));
        assertTrue(session.hasTrackedSession(uuid),
                "a stale close for the previous view must keep the reopened session");
        assertTrue(session.activeSession(uuid).isPresent());
    }
}
