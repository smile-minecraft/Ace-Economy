package com.smile.aceeconomy.gui;

import com.smile.aceeconomy.AceEconomy;

import com.smile.aceeconomy.manager.LogManager;
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

public class BankMenu implements InventoryHolder {

    private final AceEconomy plugin;
    private final Inventory inventory;
    private final Player player;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BankMenu(AceEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 27,
                miniMessage.deserialize("<gradient:#FFD700:#FFA500>🏦 AceEconomy Dashboard</gradient>"));

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

        // Static Items Setup (Slots that don't need async data immediately or have
        // placeholders)

        // Slot 14: Withdraw (Static structure)
        List<String> withdrawLore = new ArrayList<>();
        withdrawLore.add("<!italic><gray>Quickly withdraw cash from your account.");
        withdrawLore.add("");
        withdrawLore.add("<!italic><white>\uD83D\uDDB1 <yellow>Left-Click: <gold>$1,000");
        withdrawLore.add("<!italic><white>\uD83D\uDDB2 <yellow>Right-Click: <gold>$10,000");
        withdrawLore.add("<!italic><white>⇧ <yellow>Shift-Click: <gold>Custom Amount");
        inventory.setItem(14, createItem(Material.CHEST, "<!italic><gold>Withdraw Cash", withdrawLore));

        // Slot 22: Close
        inventory.setItem(22, createItem(Material.BARRIER, "<!italic><red>Close Menu"));

        // Placeholder for Dynamic Items
        ItemStack loading = createItem(Material.CLOCK, "<!italic><yellow>Loading data...");
        inventory.setItem(4, loading); // Profile
        inventory.setItem(10, loading); // Assets
        inventory.setItem(12, loading); // History
        inventory.setItem(16, loading); // Debt
    }

    public void open() {
        // Open the inventory on the player's scheduler (Main/Entity Thread)
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
            skullMeta.displayName(miniMessage.deserialize("<!italic><gold>" + player.getName() + "'s Profile"));

            List<Component> lore = new ArrayList<>();
            // Vault Rank check (if Vault exists)
            String rank = "Member"; // Default
            if (plugin.getChat() != null) {
                try {
                    rank = plugin.getChat().getPrimaryGroup(player);
                } catch (Exception ignored) {
                }
            }
            lore.add(miniMessage.deserialize("<!italic><gray>Rank: <white>" + rank));
            lore.add(
                    miniMessage.deserialize("<!italic><gray>Total Balance: <gold>$" + String.format("%,.2f", balance)));

            skullMeta.lore(lore);
            skull.setItemMeta(skullMeta);
        }
        inventory.setItem(4, skull);

        // Slot 10: Assets
        List<String> assetLore = new ArrayList<>();
        double displayBalance = Math.max(0, balance); // Only show positive balance here? User said "current positive
                                                      // balance".
        assetLore.add("<!italic><gray>Available Funds:");
        assetLore.add("<!italic><gold>$" + String.format("%,.2f", displayBalance));
        inventory.setItem(10, createItem(Material.GOLD_INGOT, "<!italic><yellow>Current Assets", assetLore));

        // Slot 16: Debt Manager
        updateDebtItem(balance, debtLimit);

        // Slot 12: History
        updateHistoryItem(history);
    }

    private void updateDebtItem(double balance, double debtLimit) {
        Material mat;
        String title;
        List<Component> lore = new ArrayList<>();

        if (balance < 0) {
            // In Debt
            mat = Material.REDSTONE_BLOCK;
            title = "<!italic><red>Debt Manager";

            double currentDebt = Math.abs(balance);
            double ratio = Math.min(1.0, currentDebt / debtLimit);
            if (debtLimit == 0)
                ratio = 1.0; // Avoid div by zero, if limit 0 and debt exists -> full bar

            lore.add(miniMessage
                    .deserialize("<!italic><gray>Current Debt: <red>$" + String.format("%,.2f", currentDebt)));
            lore.add(miniMessage
                    .deserialize("<!italic><gray>Credit Limit: <white>$" + String.format("%,.0f", debtLimit)));
            lore.add(Component.empty());
            lore.add(miniMessage.deserialize(getProgressBar(ratio, 10)));
        } else {
            // No Debt
            mat = Material.IRON_BARS;
            title = "<!italic><green>Secure Vault";
            lore.add(miniMessage.deserialize("<!italic><gray>Status: <green>Secure"));
            lore.add(miniMessage
                    .deserialize("<!italic><gray>Credit Limit: <white>$" + String.format("%,.0f", debtLimit)));
            lore.add(Component.empty());
            lore.add(miniMessage.deserialize("<dark_gray>[<green>||||||||||<dark_gray>] <white>100% Safe"));
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(title));
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

                // Heuristic based on type
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
                        symbol = "<green>+";
                        amountColor = "<green>";
                        break;
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
            meta.displayName(miniMessage.deserialize("<!italic><aqua>Recent History"));
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

    private ItemStack createItem(Material material, String name) {
        return createItem(material, name, null);
    }

    private ItemStack createItem(Material material, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name)); // Remove italic default? No, usually desired. "Start with
                                                             // <!italic> to disable italic if needed" -> User's style
                                                             // "<gradient...>"
            // Actually, usually in Paper/MiniMessage, strict deserialization is preferred.
            // I'll assume standard deserialization.

            if (loreLines != null) {
                List<Component> compLore = new ArrayList<>();
                for (String line : loreLines) {
                    compLore.add(miniMessage.deserialize(line));
                }
                meta.lore(compLore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
