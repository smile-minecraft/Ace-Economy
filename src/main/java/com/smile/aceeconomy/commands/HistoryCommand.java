package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.UUID;

public class HistoryCommand implements CommandExecutor {

    private final AceEconomy plugin;
    private final LogManager logManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public HistoryCommand(AceEconomy plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin.history")) {
            sender.sendMessage(Component.text("權限不足！", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("用法: /aceeco history <玩家> [頁碼]", NamedTextColor.RED));
            return true;
        }

        String targetName = args[0];
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1)
                    page = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("無效的頁碼！", NamedTextColor.RED));
                return true;
            }
        }

        final int currentPage = page;
        // 非同步獲取 UUID
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            UUID targetUuid = target.getUniqueId();

            if (!target.hasPlayedBefore() && !target.isOnline()) {
                // 嘗試從資料庫或快取查找 (若有必要)，這裡簡化直接用 Bukkit API
                // 若是完全沒玩過的玩家 UUID 可能不準確或無資料
            }

            logManager.getHistory(targetUuid, currentPage, 10).thenAccept(logs -> {
                if (logs.isEmpty()) {
                    sender.sendMessage(Component.text("找不到更多交易記錄。", NamedTextColor.YELLOW));
                    return;
                }

                sender.sendMessage(Component.text("=== " + targetName + " 的交易記錄 (第 " + currentPage + " 頁) ===",
                        NamedTextColor.GOLD));
                for (LogManager.TransactionLog log : logs) {
                    String time = dateFormat.format(log.timestamp());
                    String type = log.type().name();
                    double amount = log.amount();
                    String currency = log.currencyType();
                    String partner = "";

                    if (log.senderUuid() != null && !log.senderUuid().equals(targetUuid)) {
                        partner = "來自 " + Bukkit.getOfflinePlayer(log.senderUuid()).getName();
                    } else if (log.receiverUuid() != null && !log.receiverUuid().equals(targetUuid)) {
                        partner = "給 " + Bukkit.getOfflinePlayer(log.receiverUuid()).getName();
                    }

                    if (log.reverted()) {
                        type = type + " (已回溯)";
                    }

                    Component message;
                    if (log.type() == com.smile.aceeconomy.data.TransactionType.SET && log.oldBalance() != null) {
                        // SET: old -> new
                        message = Component.text(
                                String.format("[%s] %s %.2f \u2794 %.2f %s %s", time, type, log.oldBalance(),
                                        log.amount(), currency, partner),
                                log.reverted() ? NamedTextColor.GRAY : NamedTextColor.YELLOW);
                    } else {
                        message = Component.text(
                                String.format("[%s] %s %.2f %s %s", time, type, amount, currency, partner),
                                log.reverted() ? NamedTextColor.GRAY : NamedTextColor.GREEN);
                    }

                    sender.sendMessage(message);
                    sender.sendMessage(Component.text("  ID: " + log.transactionId(), NamedTextColor.DARK_GRAY));
                }
            }).exceptionally(throwable -> {
                sender.sendMessage(Component.text("查詢歷史記錄時發生錯誤。", NamedTextColor.RED));
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }
}
