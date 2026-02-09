package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.migration.CMIMigrator;
import com.smile.aceeconomy.migration.EssentialsMigrator;
import com.smile.aceeconomy.migration.Migrator;
import com.smile.aceeconomy.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理員指令處理器。
 * <p>
 * 處理 /aceeco 指令，提供管理員經濟操作功能。
 * </p>
 *
 * @author Smile
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final AceEconomy plugin;
    private final EconomyProvider economyProvider;

    /**
     * 追蹤是否有遷移任務正在進行
     */
    private static final AtomicBoolean migrationInProgress = new AtomicBoolean(false);

    /**
     * 建立管理員指令處理器。
     *
     * @param plugin 插件實例
     */
    public AdminCommand(AceEconomy plugin) {
        this.plugin = plugin;
        this.economyProvider = plugin.getEconomyProvider();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 權限檢查
        if (!sender.hasPermission("aceeconomy.admin")) {
            MessageUtils.sendError(sender, "你沒有權限使用此指令！");
            return true;
        }

        // 顯示幫助
        if (args.length < 1) {
            sendHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        // 處理 import 指令
        if (action.equals("import")) {
            return handleImport(sender, args);
        }

        // 其他指令需要 3 個參數
        if (args.length < 3) {
            sendHelp(sender);
            return true;
        }

        String targetName = args[1];
        String amountStr = args[2];

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            MessageUtils.sendError(sender, "無效的金額：<white>" + amountStr + "</white>");
            return true;
        }

        // 金額驗證
        if (amount < 0 && !action.equals("set")) {
            MessageUtils.sendError(sender, "金額不能為負數！");
            return true;
        }

        // 查找目標玩家
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            // 嘗試離線操作
            MessageUtils.sendError(sender, "玩家 <white>" + targetName + "</white> 不在線上！");
            MessageUtils.send(sender, "<gray>（目前僅支援在線玩家操作）</gray>");
            return true;
        }

        // 檢查帳戶
        if (!economyProvider.hasAccount(targetPlayer.getUniqueId())) {
            MessageUtils.sendError(sender, "目標玩家帳戶尚未載入！");
            return true;
        }

        // 執行操作
        final double finalAmount = amount;
        switch (action) {
            case "give" -> {
                if (amount <= 0) {
                    MessageUtils.sendError(sender, "給予金額必須大於 0！");
                    return true;
                }
                economyProvider.deposit(targetPlayer.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        MessageUtils.sendSuccess(sender,
                                "已給予 <aqua><player></aqua> " + MessageUtils.formatMoney(finalAmount),
                                "player", targetPlayer.getName());
                        MessageUtils.sendSuccess(targetPlayer,
                                "管理員給予你 " + MessageUtils.formatMoney(finalAmount));
                    } else {
                        MessageUtils.sendError(sender, "操作失敗！");
                    }
                });
            }

            case "take" -> {
                if (amount <= 0) {
                    MessageUtils.sendError(sender, "扣除金額必須大於 0！");
                    return true;
                }
                economyProvider.withdraw(targetPlayer.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        MessageUtils.sendSuccess(sender,
                                "已從 <aqua><player></aqua> 扣除 " + MessageUtils.formatMoney(finalAmount),
                                "player", targetPlayer.getName());
                        MessageUtils.sendSuccess(targetPlayer,
                                "管理員扣除了你 " + MessageUtils.formatMoney(finalAmount));
                    } else {
                        MessageUtils.sendError(sender, "操作失敗！可能餘額不足。");
                    }
                });
            }

            case "set" -> {
                if (amount < 0) {
                    MessageUtils.sendError(sender, "餘額不能為負數！");
                    return true;
                }
                economyProvider.setBalance(targetPlayer.getUniqueId(), amount).thenAccept(success -> {
                    if (success) {
                        MessageUtils.sendSuccess(sender,
                                "已將 <aqua><player></aqua> 的餘額設為 " + MessageUtils.formatMoney(finalAmount),
                                "player", targetPlayer.getName());
                        MessageUtils.sendSuccess(targetPlayer,
                                "管理員將你的餘額設為 " + MessageUtils.formatMoney(finalAmount));
                    } else {
                        MessageUtils.sendError(sender, "操作失敗！");
                    }
                });
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    /**
     * 處理資料匯入指令。
     *
     * @param sender 發送者
     * @param args   參數
     * @return 是否成功處理
     */
    private boolean handleImport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(sender, "<gray>用法：<white>/aceeco import <essentials|cmi></white></gray>");
            return true;
        }

        // 檢查是否有遷移正在進行
        if (migrationInProgress.get()) {
            MessageUtils.sendError(sender, "已有遷移任務正在進行中！");
            return true;
        }

        String pluginName = args[1].toLowerCase();

        // 建立對應的遷移器
        Migrator migrator = switch (pluginName) {
            case "essentials", "essentialsx" -> new EssentialsMigrator(plugin);
            case "cmi" -> new CMIMigrator(plugin);
            default -> null;
        };

        if (migrator == null) {
            MessageUtils.sendError(sender, "不支援的插件：<white>" + args[1] + "</white>");
            MessageUtils.send(sender, "<gray>支援的插件：<white>essentials, cmi</white></gray>");
            return true;
        }

        // 檢查來源是否可用
        if (!migrator.isAvailable()) {
            MessageUtils.sendError(sender, "找不到 <white>" + migrator.getName() + "</white> 的資料資料夾！");
            return true;
        }

        // 設定遷移中狀態
        migrationInProgress.set(true);

        // 發送開始訊息
        MessageUtils.send(sender, "<yellow>開始從 <white>" + migrator.getName() + "</white> 匯入資料...</yellow>");
        plugin.getLogger().info("開始資料遷移：" + migrator.getName());

        // 在非同步執行緒執行遷移
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            migrator.migrate(sender, processed -> {
                // 回報進度
                MessageUtils.send(sender, "<gray>匯入進度：<white>" + processed + "</white></gray>");
            }).thenAccept(result -> {
                // 遷移完成
                migrationInProgress.set(false);

                if (result.totalCount() == 0) {
                    MessageUtils.sendError(sender, "沒有找到可匯入的資料！");
                } else {
                    MessageUtils.sendSuccess(sender,
                            "遷移完成！成功：<white>" + result.successCount() +
                                    "</white>，失敗：<white>" + result.failCount() + "</white>");
                }

                plugin.getLogger().info("資料遷移完成：成功 " + result.successCount() +
                        "，失敗 " + result.failCount() + "，共 " + result.totalCount());

            }).exceptionally(throwable -> {
                // 遷移失敗
                migrationInProgress.set(false);
                MessageUtils.sendError(sender, "遷移失敗：<white>" + throwable.getMessage() + "</white>");
                plugin.getLogger().severe("資料遷移失敗：" + throwable.getMessage());
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }

    /**
     * 發送指令幫助訊息。
     *
     * @param sender 接收者
     */
    private void sendHelp(CommandSender sender) {
        MessageUtils.send(sender, "<yellow>--- AceEconomy 管理指令 ---</yellow>");
        MessageUtils.send(sender, "<white>/aceeco give <玩家> <金額></white> - 給予玩家金錢");
        MessageUtils.send(sender, "<white>/aceeco take <玩家> <金額></white> - 扣除玩家金錢");
        MessageUtils.send(sender, "<white>/aceeco set <玩家> <金額></white> - 設定玩家餘額");
        MessageUtils.send(sender, "<white>/aceeco import <essentials|cmi></white> - 匯入資料");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            // 補全操作類型
            List<String> actions = List.of("give", "take", "set", "import");
            String prefix = args[0].toLowerCase();
            return actions.stream()
                    .filter(a -> a.startsWith(prefix))
                    .toList();
        } else if (args.length == 2) {
            String action = args[0].toLowerCase();

            if (action.equals("import")) {
                // 補全插件名稱
                List<String> plugins = List.of("essentials", "cmi");
                String prefix = args[1].toLowerCase();
                return plugins.stream()
                        .filter(p -> p.startsWith(prefix))
                        .toList();
            } else {
                // 補全在線玩家名稱
                List<String> completions = new ArrayList<>();
                String prefix = args[1].toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
                return completions;
            }
        } else if (args.length == 3) {
            String action = args[0].toLowerCase();
            if (!action.equals("import")) {
                // 提示金額
                return List.of("100", "500", "1000", "10000");
            }
        }

        return List.of();
    }
}
