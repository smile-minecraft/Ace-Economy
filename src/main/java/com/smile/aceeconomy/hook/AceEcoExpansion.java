package com.smile.aceeconomy.hook;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.CurrencyManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;

/**
 * PlaceholderAPI 擴展。
 * <p>
 * 提供 AceEconomy 的佔位符支援。
 * </p>
 *
 * @author Smile
 */
public class AceEcoExpansion extends PlaceholderExpansion {

    private final AceEconomy plugin;
    private final CurrencyManager currencyManager;

    /**
     * 原始數值格式化器（兩位小數）
     */
    private static final DecimalFormat RAW_FORMAT = new DecimalFormat("0.00");

    /**
     * 完整格式化器（貨幣符號 + 千分位）
     */
    private static final DecimalFormat FORMATTED = new DecimalFormat("$#,##0.00");

    /**
     * 千分位格式化器
     */
    private static final DecimalFormat COMMAS_FORMAT = new DecimalFormat("#,##0");

    /**
     * 建立 PlaceholderAPI 擴展。
     *
     * @param plugin 插件實例
     */
    public AceEcoExpansion(AceEconomy plugin) {
        this.plugin = plugin;
        this.currencyManager = plugin.getCurrencyManager();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "aceeco";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Smile";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // 插件重載時保持註冊
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }

        // 取得餘額（從快取，若不在線則為 0）
        double balance = currencyManager.getBalance(player.getUniqueId());

        return switch (params.toLowerCase()) {
            // %aceeco_balance% - 原始數值
            case "balance" -> RAW_FORMAT.format(balance);

            // %aceeco_balance_formatted% - 格式化（$1,000.00）
            case "balance_formatted" -> FORMATTED.format(balance);

            // %aceeco_balance_commas% - 千分位（1,000）
            case "balance_commas" -> COMMAS_FORMAT.format(balance);

            // %aceeco_balance_int% - 整數部分
            case "balance_int" -> String.valueOf((long) balance);

            default -> null;
        };
    }
}
