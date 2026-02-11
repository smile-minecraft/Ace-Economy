package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
            plugin.getMessageManager().send(sender, "general.no-permission");
            return true;
        }

        if (args.length < 1) {
            plugin.getMessageManager().send(sender, "usage.history");
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
                plugin.getMessageManager().send(sender, "baltop.invalid-page");
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
                    plugin.getMessageManager().send(sender, "history.empty");
                    return;
                }

                plugin.getMessageManager().send(sender, "history.header",
                        Placeholder.parsed("player", targetName),
                        Placeholder.parsed("page", String.valueOf(currentPage)));

                for (LogManager.TransactionLog log : logs) {
                    String time = dateFormat.format(log.timestamp());
                    String type = log.type().name();

                    // Simple logic for partner name
                    String partner = "";
                    if (log.senderUuid() != null && !log.senderUuid().equals(targetUuid)) {
                        OfflinePlayer p = Bukkit.getOfflinePlayer(log.senderUuid());
                        partner = "來自 " + (p.getName() != null ? p.getName() : "Unknown");
                    } else if (log.receiverUuid() != null && !log.receiverUuid().equals(targetUuid)) {
                        OfflinePlayer p = Bukkit.getOfflinePlayer(log.receiverUuid());
                        partner = "給 " + (p.getName() != null ? p.getName() : "Unknown");
                    }

                    if (log.reverted()) {
                        // Reverted entry
                        plugin.getMessageManager().send(sender, "history.entry-reverted",
                                Placeholder.parsed("time", time),
                                Placeholder.parsed("type", type),
                                Placeholder.parsed("amount", String.format("%.2f", log.amount())),
                                Placeholder.parsed("currency", log.currencyType()),
                                Placeholder.parsed("partner", partner));
                    } else if (log.type() == com.smile.aceeconomy.data.TransactionType.SET
                            && log.oldBalance() != null) {
                        // SET entry
                        plugin.getMessageManager().send(sender, "history.entry-set",
                                Placeholder.parsed("time", time),
                                Placeholder.parsed("type", type),
                                Placeholder.parsed("old_balance", String.format("%.2f", log.oldBalance())),
                                Placeholder.parsed("new_balance", String.format("%.2f", log.amount())),
                                Placeholder.parsed("currency", log.currencyType()),
                                Placeholder.parsed("partner", partner));
                    } else {
                        // Normal entry
                        plugin.getMessageManager().send(sender, "history.entry-normal",
                                Placeholder.parsed("time", time),
                                Placeholder.parsed("type", type),
                                Placeholder.parsed("amount", String.format("%.2f", log.amount())),
                                Placeholder.parsed("currency", log.currencyType()),
                                Placeholder.parsed("partner", partner));
                    }

                    plugin.getMessageManager().send(sender, "history.id",
                            Placeholder.parsed("id", String.valueOf(log.transactionId())));
                }
            }).exceptionally(throwable -> {
                plugin.getMessageManager().send(sender, "history.error");
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }
}
