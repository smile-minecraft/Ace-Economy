package com.smile.aceeconomy.gui;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import com.smile.aceeconomy.manager.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bank Menu GUI.
 * <p>
 * All display text is fetched from {@link MessageManager} for full localization
 * support.
 * Data is loaded asynchronously using Folia-safe schedulers.
 * </p>
 */
public class BankMenu implements InventoryHolder {

    private final AceEconomy plugin;
    private final Inventory inventory;
    private final Player player;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final MessageManager msg;

    public BankMenu(AceEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.msg = plugin.getMessageManager();

        // Title from language file: gui.bank-title
        this.inventory = Bukkit.createInventory(this, 27,
                msg.get("gui.bank-title"));

        initializeItems();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void initializeItems() {
        // Background Filler
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // Slot 14: Withdraw (Static lore from language file)
        List<Component> withdrawLore = msg.getComponents("gui.bank-withdraw-lore");
        ItemStack withdrawItem = new ItemStack(Material.CHEST);
        ItemMeta withdrawMeta = withdrawItem.getItemMeta();
        if (withdrawMeta != null) {
            withdrawMeta.displayName(msg.get("gui.bank-withdraw-name"));
            withdrawMeta.lore(withdrawLore);
            withdrawItem.setItemMeta(withdrawMeta);
        }
        inventory.setItem(14, withdrawItem);

        // Slot 22: Close
        inventory.setItem(22, createItem(Material.BARRIER, msg.get("gui.bank-close-name")));

        // Placeholders for async-loaded dynamic items
        Component loadingName = msg.get("gui.bank-loading");
        ItemStack loading = createItem(Material.CLOCK, loadingName);
        inventory.setItem(4, loading); // Profile
        inventory.setItem(10, loading); // Assets
        inventory.setItem(12, loading); // History
        inventory.setItem(16, loading); // Debt
    }

    public void open() {
        // Open the inventory on the player's scheduler (Entity Thread - Folia safe)
        player.getScheduler().run(plugin, task -> {
            player.openInventory(inventory);
            loadDataAsync();
        }, null);
    }

    private void loadDataAsync() {
        // Use Async Scheduler for data fetching
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                UUID uuid = player.getUniqueId();

                // 1. Fetch Balance & Debt Limit (Thread-safe Managers)
                double balance = plugin.getCurrencyManager().getBalance(uuid);
                double debtLimit = plugin.getCurrencyManager().getDebtLimit(uuid);

                // 2. Fetch History (Async Future)
                CompletableFuture<List<LogManager.TransactionLog>> historyFuture = plugin.getCurrencyManager()
                        .getLogManager().getHistory(uuid, 1, 3);

                historyFuture.thenAccept(history -> {
                    // Update GUI on Entity Thread
                    player.getScheduler().run(plugin, syncTask -> {
                        updateDynamicItems(balance, debtLimit, history);
                    }, null);
                }).exceptionally(ex -> {
                    plugin.getLogger().severe("Failed to load history for " + player.getName());
                    ex.printStackTrace();
                    return null;
                });

            } catch (Exception e) {
                plugin.getLogger().severe("Error loading bank menu data: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void updateDynamicItems(double balance, double debtLimit, List<LogManager.TransactionLog> history) {
        // Slot 4: Player Profile
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            // Profile name from language: gui.bank-profile-name, replace {player}
            // placeholder
            String profileRaw = msg.getRawMessage("gui.bank-profile-name")
                    .replace("{player}", player.getName());
            skullMeta.displayName(miniMessage.deserialize(profileRaw));

            List<Component> lore = new ArrayList<>();
            // Vault Rank check
            String rank = "Member";
            if (plugin.getChat() != null) {
                try {
                    rank = plugin.getChat().getPrimaryGroup(player);
                } catch (Exception ignored) {
                }
            }
            lore.add(miniMessage.deserialize("<!italic><gray>Rank: <white>" + rank));
            lore.add(miniMessage.deserialize(
                    "<!italic><gray>Total Balance: <gold>$" + String.format("%,.2f", balance)));

            skullMeta.lore(lore);
            skull.setItemMeta(skullMeta);
        }
        inventory.setItem(4, skull);

        // Slot 10: Assets
        List<Component> assetLore = new ArrayList<>();
        double displayBalance = Math.max(0, balance);
        assetLore.add(miniMessage.deserialize("<!italic><gray>Available Funds:"));
        assetLore.add(miniMessage.deserialize("<!italic><gold>$" + String.format("%,.2f", displayBalance)));
        ItemStack assetItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta assetMeta = assetItem.getItemMeta();
        if (assetMeta != null) {
            assetMeta.displayName(msg.get("gui.bank-assets-name"));
            assetMeta.lore(assetLore);
            assetItem.setItemMeta(assetMeta);
        }
        inventory.setItem(10, assetItem);

        // Slot 16: Debt Manager
        updateDebtItem(balance, debtLimit);

        // Slot 12: History
        updateHistoryItem(history);
    }

    private void updateDebtItem(double balance, double debtLimit) {
        Material mat;
        List<Component> lore = new ArrayList<>();

        if (balance < 0) {
            mat = Material.REDSTONE_BLOCK;

            double currentDebt = Math.abs(balance);
            double ratio = Math.min(1.0, currentDebt / debtLimit);
            if (debtLimit == 0)
                ratio = 1.0;

            lore.add(miniMessage.deserialize(
                    "<!italic><gray>Current Debt: <red>$" + String.format("%,.2f", currentDebt)));
            lore.add(miniMessage.deserialize(
                    "<!italic><gray>Credit Limit: <white>$" + String.format("%,.0f", debtLimit)));
            lore.add(Component.empty());
            lore.add(miniMessage.deserialize(getProgressBar(ratio, 10)));
        } else {
            mat = Material.IRON_BARS;
            lore.add(miniMessage.deserialize("<!italic><gray>Status: <green>Secure"));
            lore.add(miniMessage.deserialize(
                    "<!italic><gray>Credit Limit: <white>$" + String.format("%,.0f", debtLimit)));
            lore.add(Component.empty());
            lore.add(miniMessage.deserialize("<dark_gray>[<green>||||||||||<dark_gray>] <white>100% Safe"));
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(msg.get("gui.bank-debt-name"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(16, item);
    }

    private void updateHistoryItem(List<LogManager.TransactionLog> history) {
        List<Component> lore = new ArrayList<>();
        if (history == null || history.isEmpty()) {
            lore.add(miniMessage.deserialize("<!italic><gray>No recent transactions."));
        } else {
            for (LogManager.TransactionLog log : history) {
                boolean isSend = player.getUniqueId().equals(log.senderUuid());

                String symbol = "";
                String amountColor = "<white>";

                switch (log.type()) {
                    case PAY:
                        if (isSend) {
                            symbol = "<red>-";
                            amountColor = "<red>";
                        } else {
                            symbol = "<green>+";
                            amountColor = "<green>";
                        }
                        break;
                    case WITHDRAW:
                        symbol = "<red>-";
                        amountColor = "<red>";
                        break;
                    case DEPOSIT:
                    case GIVE:
                        symbol = "<green>+";
                        amountColor = "<green>";
                        break;
                    case TAKE:
                        symbol = "<red>-";
                        amountColor = "<red>";
                        break;
                    default:
                        symbol = "<gray>?";
                }

                lore.add(miniMessage.deserialize(
                        "<!italic><gray>" + log.type().name() + ": " + amountColor + symbol + "$"
                                + String.format("%,.2f", log.amount())));
            }
        }

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(msg.get("gui.bank-history-name"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(12, item);
    }

    private String getProgressBar(double ratio, int length) {
        int bars = (int) (ratio * length);
        StringBuilder sb = new StringBuilder("<dark_gray>[");
        sb.append("<red>").append("|".repeat(bars));
        sb.append("<gray>").append(".".repeat(length - bars));
        sb.append("<dark_gray>]");
        return sb.toString();
    }

    // Convenience: create item from string name (MiniMessage raw)
    private ItemStack createItem(Material material, String name) {
        return createItem(material, miniMessage.deserialize(name));
    }

    // Convenience: create item from Component display name
    private ItemStack createItem(Material material, Component displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(displayName);
            item.setItemMeta(meta);
        }
        return item;
    }
}
