package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LeaderboardManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class BaltopCommand implements CommandExecutor, TabCompleter {

    private final AceEconomy plugin;
    private final LeaderboardManager leaderboardManager;

    public BaltopCommand(AceEconomy plugin, LeaderboardManager leaderboardManager) {
        this.plugin = plugin;
        this.leaderboardManager = leaderboardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.command.baltop")) {
            plugin.getMessageManager().send(sender, "no-permission");
            return true;
        }

        if (!leaderboardManager.isEnabled()) {
            plugin.getMessageManager().send(sender, "baltop-disabled");
            return true;
        }

        plugin.getMessageManager().send(sender, "baltop-loading");

        // 解析參數: /baltop [貨幣] [頁碼]
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        int page = 1;

        // 檢查第一個參數
        if (args.length > 0) {
            // 檢查是否是數字 (頁碼) 或貨幣 ID
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1)
                    page = 1;
            } catch (NumberFormatException e) {
                // 不是數字，檢查是否是貨幣 ID
                if (plugin.getCurrencyManager().currencyExists(args[0].toLowerCase())) {
                    currencyId = args[0].toLowerCase();
                } else {
                    plugin.getMessageManager().send(sender, "unknown-currency",
                            Placeholder.parsed("currency", args[0]));
                    return true;
                }
            }
        }

        // 檢查第二個參數 (頁碼)
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1)
                    page = 1;
            } catch (NumberFormatException e) {
                plugin.getMessageManager().send(sender, "invalid-page");
                return true;
            }
        }

        final int finalPage = page;
        final String finalCurrencyId = currencyId;
        String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();

        // 非同步取得資料
        leaderboardManager.getTopAccounts(finalCurrencyId).thenAccept(entries -> {
            // 回到主執行緒顯示 (雖然 adventure 允許非同步發送訊息，但為了安全與一致性)
            // Folia: CommandSender 若是 Player，可以用 getScheduler。若是 Console 則直接發。
            // 這裡使用 AceEconomy 實例的排程器 (Global for Console / Player context sensitive usually
            // handled by adventure platform or safe simple msg)
            // 但為了確保安全，我們簡單地直接發送 (Adventure audience is thread-safe usually)

            if (entries.isEmpty()) {
                plugin.getMessageManager().send(sender, "baltop-empty");
                return;
            }

            int pageSize = leaderboardManager.getPageSize();
            int totalPages = (int) Math.ceil((double) entries.size() / pageSize);

            if (finalPage > totalPages) {
                plugin.getMessageManager().send(sender, "baltop-invalid-page",
                        Placeholder.parsed("max_page", String.valueOf(totalPages)));
                return;
            }

            int startIndex = (finalPage - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, entries.size());

            // Header
            plugin.getMessageManager().send(sender, "baltop-header", Placeholder.parsed("currency_name", currencyName));

            for (int i = startIndex; i < endIndex; i++) {
                LeaderboardManager.TopEntry entry = entries.get(i);
                // 使用 MessageManager 格式化並發送每行，或構建 Component
                // 這裡使用 baltop-entry key
                String formattedBalance = plugin.getConfigManager().formatMoney(entry.balance(), finalCurrencyId);
                plugin.getMessageManager().send(sender, "baltop-entry",
                        Placeholder.parsed("rank", String.valueOf(entry.rank())),
                        Placeholder.parsed("player", entry.name()),
                        Placeholder.parsed("amount", formattedBalance));
            }

            // Footer / Pagination
            long timeAgoSeconds = (System.currentTimeMillis() - leaderboardManager.getLastUpdated(finalCurrencyId))
                    / 1000;
            String timeAgo = formatTimeAgo(timeAgoSeconds);

            plugin.getMessageManager().send(sender, "baltop-footer",
                    Placeholder.parsed("time_ago", timeAgo),
                    Placeholder.parsed("currency", finalCurrencyId),
                    Placeholder.parsed("prev_page", String.valueOf(Math.max(1, finalPage - 1))),
                    Placeholder.parsed("page", String.valueOf(finalPage)),
                    Placeholder.parsed("total_pages", String.valueOf(totalPages)),
                    Placeholder.parsed("next_page", String.valueOf(Math.min(totalPages, finalPage + 1))));
        });

        return true;
    }

    private String formatTimeAgo(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        } else {
            return (seconds / 60) + " 分鐘";
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            // 可以是貨幣 ID 或頁碼
            List<String> completions = new java.util.ArrayList<>(plugin.getCurrencyManager().getRegisteredCurrencies());
            completions.add("1");
            completions.add("2");
            return completions.stream()
                    .filter(c -> c.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        } else if (args.length == 2) {
            return List.of("1", "2", "3");
        }
        return Collections.emptyList();
    }
}
