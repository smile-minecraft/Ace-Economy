package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.listeners.BanknoteListener;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 提領支票指令處理器。
 * <p>
 * 處理 /withdraw 指令，將金錢轉換為實體支票物品。
 * </p>
 *
 * @author Smile
 */
public class WithdrawCommand implements CommandExecutor, TabCompleter {

    private final AceEconomy plugin;
    private final EconomyProvider economyProvider;

    /**
     * 建立提領指令處理器。
     *
     * @param plugin 插件實例
     */
    public WithdrawCommand(AceEconomy plugin) {
        this.plugin = plugin;
        this.economyProvider = plugin.getEconomyProvider();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 必須是玩家
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "general.console-only-player");
            return true;
        }

        // 權限檢查
        if (!player.hasPermission("aceeconomy.withdraw")) {
            plugin.getMessageManager().send(sender, "general.no-permission");
            return true;
        }

        // 參數檢查
        if (args.length < 1) {
            plugin.getMessageManager().send(sender, "usage.withdraw");
            return true;
        }

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().send(sender, "general.invalid-amount", Placeholder.parsed("amount", args[0]));
            return true;
        }

        // 金額驗證
        if (amount <= 0) {
            plugin.getMessageManager().send(sender, "general.amount-must-be-positive");
            return true;
        }

        // 最小金額限制
        if (amount < 1) {
            plugin.getMessageManager().send(sender, "economy.min-withdraw-amount",
                    Placeholder.parsed("amount", plugin.getConfigManager().formatMoney(1.0)));
            return true;
        }

        // 產生支票唯一 ID
        java.util.UUID banknoteUuid = java.util.UUID.randomUUID();

        // 扣除金錢 (使用預設貨幣)
        // TODO: 未來支援多貨幣提款，目前 withdraw 預設使用 default currency (但需不需要傳入?
        // economyProvider.withdraw 用的是預設貨幣?)
        // EconomyProvider.withdraw(uuid, amount) -> calls
        // CurrencyManager.withdraw(uuid, amount) -> uses default currency if not
        // specified?
        // 需檢查 EconomyProvider。假設它使用預設貨幣。

        // 檢查餘額 - 移除手動檢查，交由 CurrencyManager 拋出異常

        // 執行扣款 (帶入 banknoteUuid 以供記錄)
        plugin.getEconomyProvider().withdraw(player.getUniqueId(), amount, banknoteUuid)
                .thenAccept(success -> {
                    if (!success) {
                        plugin.getMessageManager().send(player, "general.transaction-failed");
                        return;
                    }

                    // 建立支票物品 (帶有 UUID)
                    ItemStack banknote = createBanknote(amount, player.getName(), banknoteUuid);

                    // 給予玩家物品（使用區域排程器確保 Folia 安全）
                    player.getScheduler().run(plugin, task -> {
                        // 檢查背包空間
                        if (player.getInventory().firstEmpty() == -1) {
                            // 背包已滿，退還金錢 (Rollback)
                            plugin.getEconomyProvider().deposit(player.getUniqueId(), amount);
                            plugin.getMessageManager().send(player, "general.inventory-full");
                        } else {
                            player.getInventory().addItem(banknote);
                            String formattedAmount = plugin.getConfigManager().formatMoney(amount);
                            plugin.getMessageManager().send(player, "economy.withdraw-success",
                                    Placeholder.parsed("amount", formattedAmount));
                        }
                    }, null);

                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause();
                    if (cause instanceof com.smile.aceeconomy.exception.InsufficientFundsException) {
                        plugin.getMessageManager().send(player, "economy.insufficient-funds"); // 或顯示具體訊息
                    } else {
                        plugin.getMessageManager().send(player, "general.transaction-failed");
                        plugin.getLogger().severe("Withdraw failed: " + ex.getMessage());
                    }
                    return null;
                });

        return true;
    }

    /**
     * 建立支票物品。
     *
     * @param value        支票價值
     * @param issuerName   簽發人名稱
     * @param banknoteUuid 支票唯一 ID
     * @return 支票物品
     */
    private ItemStack createBanknote(double value, String issuerName, java.util.UUID banknoteUuid) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String formattedValue = plugin.getConfigManager().formatMoney(value);

            // 設定顯示名稱（使用 MiniMessage 格式）
            meta.displayName(plugin.getMessageManager().get("banknote.name",
                    Placeholder.parsed("value", formattedValue)));

            // 設定說明
            // 這裡為了保持原有的樣式結構，我們可能需要手動構建，或者使用 MessageManager.get 來取得 lore 每一行
            // 假設 messages_zh_TW.yml 中有 banknote-lore-value, banknote-lore-issuer,
            // banknote-lore-click
            // 為了簡化，這裡暫時使用 configManager.formatMoney 配合硬編碼風格，但使用 MessageManager 解析

            meta.lore(List.of(
                    plugin.getMessageManager().get("banknote.lore-value", Placeholder.parsed("value", formattedValue)),
                    plugin.getMessageManager().get("banknote.lore-issuer", Placeholder.parsed("issuer", issuerName)),
                    net.kyori.adventure.text.Component.empty(),
                    plugin.getMessageManager().get("banknote.lore-click")));

            // 使用 PDC 儲存資料（防偽）
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            NamespacedKey valueKey = BanknoteListener.getValueKey(plugin);
            NamespacedKey issuerKey = BanknoteListener.getIssuerKey(plugin);
            NamespacedKey idKey = BanknoteListener.getIdKey(plugin);

            pdc.set(valueKey, PersistentDataType.DOUBLE, value);
            pdc.set(issuerKey, PersistentDataType.STRING, issuerName);
            pdc.set(idKey, PersistentDataType.STRING, banknoteUuid.toString());

            item.setItemMeta(meta);
        }

        return item;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("100", "500", "1000", "5000", "10000");
        }
        return List.of();
    }
}
