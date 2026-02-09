package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import com.smile.aceeconomy.utils.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class RollbackCommand implements CommandExecutor {

    private final AceEconomy plugin;
    private final LogManager logManager;

    public RollbackCommand(AceEconomy plugin, LogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin.rollback")) {
            sender.sendMessage(Component.text("權限不足！", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /aceeco rollback <玩家> <時間> [類別]", NamedTextColor.RED));
            sender.sendMessage(Component.text("時間範例: 1h, 30m, 1d", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("類別: all(預設), trade, admin", NamedTextColor.GRAY));
            return true;
        }

        String targetName = args[0];
        String durationStr = args[1];
        String category = args.length > 2 ? args[2] : "all";

        long durationMillis = TimeUtil.parseDuration(durationStr);
        if (durationMillis <= 0) {
            sender.sendMessage(Component.text("無效的時間格式！請使用如 1h, 30m, 1d 的格式。", NamedTextColor.RED));
            return true;
        }

        long sinceTimestamp = System.currentTimeMillis() - durationMillis;

        // 非同步處理
        sender.sendMessage(Component.text("正在搜尋並回溯交易...", NamedTextColor.YELLOW));
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            UUID targetUuid = target.getUniqueId();

            if (!target.hasPlayedBefore() && !target.isOnline()) {
                // 這裡可以增加更嚴格的檢查，但目前先假設管理員輸入正確
            }

            logManager.getLogs(targetUuid, sinceTimestamp, category).thenAccept(logs -> {
                if (logs.isEmpty()) {
                    sender.sendMessage(Component.text("找不到符合條件的可回溯交易。", NamedTextColor.RED));
                    return;
                }

                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);
                List<String> errors = new ArrayList<>();
                double totalValue = 0;

                // 依序執行回溯 (雖然是 batch，但為了安全還是逐筆呼叫 rollbackTransaction)
                // 若要優化效能，可實作 batch rollback，但目前邏輯複用較安全
                // 使用 CompletableFuture chain 確保順序性 (或是用 loop + join，但要注意非同步執行緒是否足夠)
                // 這裡簡單使用 loop + join 阻塞 worker thread，因為我們已經在 async user task 中

                for (LogManager.TransactionLog log : logs) {
                    // 略過已經回溯的
                    if (log.reverted())
                        continue;

                    try {
                        String result = logManager.rollbackTransaction(log.transactionId()).join();
                        if (result.contains("成功")) {
                            successCount.incrementAndGet();
                            // 累加金額 (需判斷方向，這裡簡單累加絕對值)
                            // totalValue += log.amount();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        errors.add(e.getMessage());
                    }
                }

                sender.sendMessage(Component.text("回溯完成！", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("成功: " + successCount.get(), NamedTextColor.GREEN));
                if (failCount.get() > 0) {
                    sender.sendMessage(Component.text("失敗: " + failCount.get(), NamedTextColor.RED));
                }

            }).exceptionally(throwable -> {
                sender.sendMessage(Component.text("執行回溯時發生錯誤。", NamedTextColor.RED));
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }
}
