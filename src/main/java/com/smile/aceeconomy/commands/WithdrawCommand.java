package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.listeners.BanknoteListener;
import com.smile.aceeconomy.utils.MessageUtils;
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

import java.text.DecimalFormat;
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
     * 金額格式化器
     */
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.00");

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
            MessageUtils.sendError(sender, "此指令只能由玩家執行！");
            return true;
        }

        // 權限檢查
        if (!player.hasPermission("aceeconomy.withdraw")) {
            MessageUtils.sendError(sender, "你沒有權限使用此指令！");
            return true;
        }

        // 參數檢查
        if (args.length < 1) {
            MessageUtils.send(sender, "用法：<white>/withdraw <金額></white>");
            return true;
        }

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            MessageUtils.sendError(sender, "無效的金額：<white>" + args[0] + "</white>");
            return true;
        }

        // 金額驗證
        if (amount <= 0) {
            MessageUtils.sendError(sender, "金額必須大於 0！");
            return true;
        }

        // 最小金額限制
        if (amount < 1) {
            MessageUtils.sendError(sender, "最小提領金額為 $1.00！");
            return true;
        }

        // 扣除金錢
        economyProvider.withdraw(player.getUniqueId(), amount).thenAccept(success -> {
            if (!success) {
                MessageUtils.sendError(player, "餘額不足或交易被取消！");
                return;
            }

            // 建立支票物品
            ItemStack banknote = createBanknote(amount, player.getName());

            // 給予玩家物品（使用區域排程器確保 Folia 安全）
            player.getScheduler().run(plugin, task -> {
                // 檢查背包空間
                if (player.getInventory().firstEmpty() == -1) {
                    // 背包已滿，退還金錢
                    economyProvider.deposit(player.getUniqueId(), amount);
                    MessageUtils.sendError(player, "背包已滿，無法提領支票！");
                } else {
                    player.getInventory().addItem(banknote);
                    MessageUtils.sendSuccess(player, "已提領支票：" + MessageUtils.formatMoney(amount));
                }
            }, null);
        });

        return true;
    }

    /**
     * 建立支票物品。
     *
     * @param value      支票價值
     * @param issuerName 簽發人名稱
     * @return 支票物品
     */
    private ItemStack createBanknote(double value, String issuerName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // 設定顯示名稱（使用 MiniMessage 格式）
            meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<green><bold>銀行支票</bold></green> <gray>(Banknote)</gray>"));

            // 設定說明
            meta.lore(List.of(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<gray>價值：</gray><yellow>$" + MONEY_FORMAT.format(value) + "</yellow>"),
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<gray>簽發人：</gray><aqua>" + issuerName + "</aqua>"),
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<dark_gray>右鍵點擊以兌換</dark_gray>")));

            // 使用 PDC 儲存資料（防偽）
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            NamespacedKey valueKey = BanknoteListener.getValueKey(plugin);
            NamespacedKey issuerKey = BanknoteListener.getIssuerKey(plugin);

            pdc.set(valueKey, PersistentDataType.DOUBLE, value);
            pdc.set(issuerKey, PersistentDataType.STRING, issuerName);

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
