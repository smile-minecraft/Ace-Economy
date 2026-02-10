package com.smile.aceeconomy.gui;

import com.smile.aceeconomy.AceEconomy;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

public class GUIListener implements Listener {

    private final AceEconomy plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public GUIListener(AceEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;

        // Check if the inventory is our BankMenu
        if (event.getInventory().getHolder() instanceof BankMenu) {
            event.setCancelled(true); // Cancel interactions to prevent item theft

            if (event.getClickedInventory() == null
                    || event.getClickedInventory() != event.getView().getTopInventory()) {
                return; // Ignore clicks outside the GUI or in bottom inventory
            }

            Player player = (Player) event.getWhoClicked();
            int slot = event.getSlot();

            if (slot == 14) { // Withdraw Slot
                handleWithdrawClick(player, event.getClick());
            } else if (slot == 22) { // Close Slot
                player.closeInventory();
            }
        }
    }

    private void handleWithdrawClick(Player player, ClickType clickType) {
        if (clickType.isLeftClick() && !clickType.isShiftClick()) {
            player.performCommand("withdraw 1000");
        } else if (clickType.isRightClick() && !clickType.isShiftClick()) {
            player.performCommand("withdraw 10000");
        } else if (clickType.isShiftClick()) {
            player.closeInventory();
            player.sendMessage(miniMessage.deserialize("<yellow>Please type amount in chat:"));
            // Note: This just prompts the user. If input capture is needed, a ChatListener
            // would be required.
        }
    }
}
