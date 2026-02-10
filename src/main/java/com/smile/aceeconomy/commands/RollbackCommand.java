package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.LogManager;
import com.smile.aceeconomy.utils.TimeUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
            plugin.getMessageManager().send(sender, "general.no-permission");
            return true;
        }

        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "rollback.usage");
            return true;
        }

        String targetName = args[0];
        String durationStr = args[1];
        String category = args.length > 2 ? args[2] : "all";

        long durationMillis = TimeUtil.parseDuration(durationStr);
        if (durationMillis <= 0) {
            plugin.getMessageManager().send(sender, "general.invalid-time-format");
            return true;
        }

        long sinceTimestamp = System.currentTimeMillis() - durationMillis;

        // 非同步處理
        plugin.getMessageManager().send(sender, "rollback.searching");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            UUID targetUuid = target.getUniqueId();

            if (!target.hasPlayedBefore() && !target.isOnline()) {
                // 這裡可以增加更嚴格的檢查，但目前先假設管理員輸入正確
            }

            logManager.getLogs(targetUuid, sinceTimestamp, category).thenAccept(logs -> {
                if (logs.isEmpty()) {
                    plugin.getMessageManager().send(sender, "rollback.none-found");
                    return;
                }

                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);
                List<String> errors = new ArrayList<>();
                // Remove unused totalValue variable

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

                plugin.getMessageManager().send(sender, "rollback.complete",
                        Placeholder.parsed("success", String.valueOf(successCount.get())),
                        Placeholder.parsed("fail", String.valueOf(failCount.get())));

            }).exceptionally(throwable -> {
                plugin.getMessageManager().send(sender, "rollback.error");
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }
}
