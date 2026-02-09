package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LeaderboardManager;
import com.smile.aceeconomy.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final MiniMessage mm = MiniMessage.miniMessage();

    public BaltopCommand(AceEconomy plugin, LeaderboardManager leaderboardManager) {
        this.plugin = plugin;
        this.leaderboardManager = leaderboardManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.command.baltop")) {
            MessageUtils.sendError(sender, "您沒有權限執行此指令！");
            return true;
        }

        if (!leaderboardManager.isEnabled()) {
            MessageUtils.sendError(sender, "排行榜功能已停用。");
            return true;
        }

        MessageUtils.send(sender, "<gray>正在載入排行榜...</gray>");

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
                    MessageUtils.sendError(sender, "<red>未知的貨幣: <white>" + args[0] + "</white></red>");
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
                MessageUtils.sendError(sender, "請輸入有效的頁碼！");
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
                MessageUtils.sendError(sender, "排行榜目前沒有資料。");
                return;
            }

            int pageSize = leaderboardManager.getPageSize();
            int totalPages = (int) Math.ceil((double) entries.size() / pageSize);

            if (finalPage > totalPages) {
                MessageUtils.sendError(sender, "頁碼超出範圍 (最大頁數: " + totalPages + ")");
                return;
            }

            int startIndex = (finalPage - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, entries.size());

            Component header = mm.deserialize("<gold>=== 🏆 " + currencyName + " 排行榜 ===</gold>");
            sender.sendMessage(header);

            for (int i = startIndex; i < endIndex; i++) {
                LeaderboardManager.TopEntry entry = entries.get(i);
                String line = "<yellow>#" + entry.rank() + " <white>" + entry.name() + " <dark_gray>- <green>"
                        + MessageUtils.formatMoney(entry.balance());
                sender.sendMessage(mm.deserialize(line));
            }

            // Footer / Pagination
            long timeAgoSeconds = (System.currentTimeMillis() - leaderboardManager.getLastUpdated(finalCurrencyId))
                    / 1000;
            String timeAgo = formatTimeAgo(timeAgoSeconds);

            Component footer = mm.deserialize("<gray>更新於: " + timeAgo + " 前 <dark_gray>| </dark_gray>")
                    .append(mm.deserialize("<gold>[上一頁]</gold>")
                            .clickEvent(ClickEvent.runCommand("/baltop " + finalCurrencyId + " " + (finalPage - 1))))
                    .append(mm.deserialize(" <gray>(" + finalPage + "/" + totalPages + ") </gray>"))
                    .append(mm.deserialize("<gold>[下一頁]</gold>")
                            .clickEvent(ClickEvent.runCommand("/baltop " + finalCurrencyId + " " + (finalPage + 1))));

            sender.sendMessage(footer);
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
