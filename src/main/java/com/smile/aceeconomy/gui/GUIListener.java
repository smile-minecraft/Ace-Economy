package com.smile.aceeconomy.gui;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.listener.BanknoteInputListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Handles player interactions within the BankMenu GUI.
 * <p>
 * Cancels all clicks to prevent item theft, routes specific slot clicks
 * to their handlers (withdraw, close), and integrates with
 * {@link BanknoteInputListener} for custom amount input.
 * </p>
 */
public class GUIListener implements Listener {

    private final AceEconomy plugin;

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
            // Left-Click: $1,000 quick withdraw
            player.performCommand("withdraw 1000");
        } else if (clickType.isRightClick() && !clickType.isShiftClick()) {
            // Right-Click: $10,000 quick withdraw
            player.performCommand("withdraw 10000");
        } else if (clickType.isShiftClick()) {
            // Shift-Click: Custom amount via chat input
            player.closeInventory();
            plugin.getMessageManager().send(player, "gui.input-request");
            BanknoteInputListener.awaitingInput.add(player.getUniqueId());
        }
    }
}
