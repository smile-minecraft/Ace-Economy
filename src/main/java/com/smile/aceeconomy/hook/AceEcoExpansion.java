package com.smile.aceeconomy.hook;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.CurrencyManager;
import com.smile.aceeconomy.manager.LeaderboardManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI 擴展。
 * <p>
 * 提供 AceEconomy 的佔位符支援。
 * </p>
 * 
 * 支援的佔位符:
 * - %aceeco_balance% - 預設貨幣餘額
 * - %aceeco_balance_formatted% - 格式化餘額
 * - %aceeco_balance_<currency>% - 指定貨幣餘額
 * - %aceeco_balance_<currency>_formatted% - 指定貨幣格式化餘額
 * - %aceeco_top_name_<rank>% - 排行榜第N名玩家 (預設貨幣)
 * - %aceeco_top_balance_<rank>% - 排行榜第N名餘額 (預設貨幣)
 * - %aceeco_top_name_<currency>_<rank>% - 指定貨幣排行榜第N名玩家
 * - %aceeco_top_balance_<currency>_<rank>% - 指定貨幣排行榜第N名餘額
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

    // 匹配 top_name_<rank> 或 top_name_<currency>_<rank>
    private static final Pattern TOP_NAME_PATTERN = Pattern.compile("top_name_(?:([a-zA-Z_]+)_)?(\\d+)");
    // 匹配 top_balance_<rank> 或 top_balance_<currency>_<rank>
    private static final Pattern TOP_BALANCE_PATTERN = Pattern.compile("top_balance_(?:([a-zA-Z_]+)_)?(\\d+)");
    // 匹配 balance_<currency> 或 balance_<currency>_formatted
    private static final Pattern BALANCE_CURRENCY_PATTERN = Pattern.compile("balance_([a-zA-Z_]+?)(?:_formatted)?$");

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
        String lowParams = params.toLowerCase();

        // 預設貨幣餘額類佔位符
        if (player != null) {
            String defaultCurrency = currencyManager.getDefaultCurrencyId();
            double balance = currencyManager.getBalance(player.getUniqueId(), defaultCurrency);

            switch (lowParams) {
                case "balance" -> {
                    return RAW_FORMAT.format(balance);
                }
                case "balance_formatted" -> {
                    return FORMATTED.format(balance);
                }
                case "balance_commas" -> {
                    return COMMAS_FORMAT.format(balance);
                }
                case "balance_int" -> {
                    return String.valueOf((long) balance);
                }
            }

            // 檢查是否是指定貨幣餘額: balance_<currency> 或 balance_<currency>_formatted
            if (lowParams.startsWith("balance_") && !lowParams.equals("balance_formatted")
                    && !lowParams.equals("balance_commas") && !lowParams.equals("balance_int")) {
                Matcher m = BALANCE_CURRENCY_PATTERN.matcher(lowParams);
                if (m.matches()) {
                    String currencyId = m.group(1);
                    if (currencyManager.currencyExists(currencyId)) {
                        double currencyBalance = currencyManager.getBalance(player.getUniqueId(), currencyId);
                        if (lowParams.endsWith("_formatted")) {
                            return FORMATTED.format(currencyBalance);
                        }
                        return RAW_FORMAT.format(currencyBalance);
                    }
                }
            }
        }

        // 排行榜佔位符 (不需要玩家)
        LeaderboardManager leaderboard = plugin.getLeaderboardManager();
        if (leaderboard == null || !leaderboard.isEnabled()) {
            return "N/A";
        }

        String defaultCurrency = currencyManager.getDefaultCurrencyId();

        // top_name_<rank> 或 top_name_<currency>_<rank>
        Matcher nameMatcher = TOP_NAME_PATTERN.matcher(lowParams);
        if (nameMatcher.matches()) {
            String currencyId = nameMatcher.group(1) != null ? nameMatcher.group(1) : defaultCurrency;
            int rank = Integer.parseInt(nameMatcher.group(2));
            return getTopName(leaderboard, currencyId, rank);
        }

        // top_balance_<rank> 或 top_balance_<currency>_<rank>
        Matcher balMatcher = TOP_BALANCE_PATTERN.matcher(lowParams);
        if (balMatcher.matches()) {
            String currencyId = balMatcher.group(1) != null ? balMatcher.group(1) : defaultCurrency;
            int rank = Integer.parseInt(balMatcher.group(2));
            return getTopBalance(leaderboard, currencyId, rank);
        }

        return null;
    }

    private String getTopName(LeaderboardManager leaderboard, String currencyId, int rank) {
        List<LeaderboardManager.TopEntry> entries = leaderboard.getTopAccounts(currencyId).join();
        if (rank < 1 || rank > entries.size()) {
            return "---";
        }
        return entries.get(rank - 1).name();
    }

    private String getTopBalance(LeaderboardManager leaderboard, String currencyId, int rank) {
        List<LeaderboardManager.TopEntry> entries = leaderboard.getTopAccounts(currencyId).join();
        if (rank < 1 || rank > entries.size()) {
            return "0";
        }
        return FORMATTED.format(entries.get(rank - 1).balance());
    }
}
