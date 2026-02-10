package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.event.EconomyTransactionEvent;
import com.smile.aceeconomy.migration.CMIMigrator;
import com.smile.aceeconomy.migration.EssentialsMigrator;
import com.smile.aceeconomy.migration.Migrator;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import java.util.stream.Collectors;

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

    private com.smile.aceeconomy.manager.LogManager logManager;
    private CommandExecutor historyCommand;
    private CommandExecutor rollbackCommand;

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

    public void setLogManager(com.smile.aceeconomy.manager.LogManager logManager) {
        this.logManager = logManager;
    }

    public void setHistoryCommand(CommandExecutor historyCommand) {
        this.historyCommand = historyCommand;
    }

    public void setRollbackCommand(CommandExecutor rollbackCommand) {
        this.rollbackCommand = rollbackCommand;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 權限檢查
        if (!sender.hasPermission("aceeconomy.admin")) {
            plugin.getMessageManager().send(sender, "no-permission");
            return true;
        }

        // 顯示幫助
        if (args.length < 1) {
            sendHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        // 處理 history 指令
        if (action.equals("history")) {
            if (historyCommand != null) {
                // 將參數向左移動 1 位，傳遞給子指令
                String[] subArgs = new String[args.length - 1];
                System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                return historyCommand.onCommand(sender, command, label, subArgs);
            }
            return true;
        }

        // 處理 rollback 指令
        if (action.equals("rollback")) {
            if (rollbackCommand != null) {
                String[] subArgs = new String[args.length - 1];
                System.arraycopy(args, 1, subArgs, 0, subArgs.length);
                return rollbackCommand.onCommand(sender, command, label, subArgs);
            }
            return true;
        }

        // 處理 import 指令
        if (action.equals("import")) {
            return handleImport(sender, args);
        }

        // 處理 reload 指令
        if (action.equals("reload")) {
            if (!sender.hasPermission("aceeconomy.command.reload")) {
                plugin.getMessageManager().send(sender, "no-permission");
                return true;
            }

            plugin.getConfigManager().reload();
            plugin.getMessageManager().send(sender, "reload-success");

            // Console Log
            String senderName = sender instanceof Player ? ((Player) sender).getName() : "Console";
            plugin.getLogger().info("[AceEconomy] Configuration reloaded by " + senderName + ".");
            return true;
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
            plugin.getMessageManager().send(sender, "invalid-amount", Placeholder.parsed("amount", amountStr));
            return true;
        }

        // 金額驗證
        if (amount < 0 && !action.equals("set")) {
            plugin.getMessageManager().send(sender, "amount-must-be-positive");
            return true;
        }

        // 查找目標玩家
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            // 嘗試離線操作
            plugin.getMessageManager().send(sender, "player-offline", Placeholder.parsed("player", targetName));
            // MessageUtils.send(sender, "<gray>（目前僅支援在線玩家操作）</gray>"); // MessageManager
            // usually handles this context in the message itself or we add another key
            return true;
        }

        // 檢查帳戶
        if (!economyProvider.hasAccount(targetPlayer.getUniqueId())) {
            plugin.getMessageManager().send(sender, "account-not-found");
            return true;
        }

        // 取得貨幣 ID (可選參數)
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        if (args.length >= 4) {
            String inputCurrency = args[3].toLowerCase();
            if (!plugin.getCurrencyManager().currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "unknown-currency",
                        Placeholder.parsed("currency", inputCurrency));
                return true;
            }
            currencyId = inputCurrency;
        }
        String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();

        // 執行操作
        final double finalAmount = amount;
        switch (action) {
            case "give" -> {
                if (amount <= 0) {
                    plugin.getMessageManager().send(sender, "amount-must-be-positive");
                    return true;
                }
                final String gCurrency = currencyId;
                final String gCurrencyName = currencyName;
                economyProvider.deposit(targetPlayer.getUniqueId(), currencyId, amount).thenAccept(success -> {
                    if (success) {
                        String formatted = plugin.getConfigManager().formatMoney(finalAmount, gCurrency);
                        plugin.getMessageManager().send(sender, "admin-give-success",
                                Placeholder.parsed("player", targetPlayer.getName()),
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", gCurrencyName)); // Typo in key usage? "currency_name" vs
                                                                                // "currency" in message. checked:
                                                                                // "currency" in example

                        plugin.getMessageManager().send(targetPlayer, "admin-give-received",
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", gCurrencyName));

                        fireTransactionEvent(sender, targetPlayer, finalAmount,
                                EconomyTransactionEvent.TransactionType.GIVE);
                    } else {
                        plugin.getMessageManager().send(sender, "admin-action-failed");
                    }
                });
            }

            case "take" -> {
                if (amount <= 0) {
                    plugin.getMessageManager().send(sender, "amount-must-be-positive");
                    return true;
                }
                final String tCurrency = currencyId;
                final String tCurrencyName = currencyName;
                economyProvider.withdraw(targetPlayer.getUniqueId(), currencyId, amount).thenAccept(success -> {
                    if (success) {
                        String formatted = plugin.getConfigManager().formatMoney(finalAmount, tCurrency);
                        plugin.getMessageManager().send(sender, "admin-take-success",
                                Placeholder.parsed("player", targetPlayer.getName()),
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", tCurrencyName));

                        plugin.getMessageManager().send(targetPlayer, "admin-take-received",
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", tCurrencyName));

                        fireTransactionEvent(sender, targetPlayer, finalAmount,
                                EconomyTransactionEvent.TransactionType.TAKE);
                    } else {
                        plugin.getMessageManager().send(sender, "admin-taking-failed");
                    }
                });
            }

            case "set" -> {
                if (amount < 0) {
                    plugin.getMessageManager().send(sender, "amount-must-be-positive"); // message says "amount cannot
                                                                                        // be negative"
                    return true;
                }
                final String sCurrency = currencyId;
                final String sCurrencyName = currencyName;
                economyProvider.setBalance(targetPlayer.getUniqueId(), currencyId, amount).thenAccept(success -> {
                    if (success) {
                        String formatted = plugin.getConfigManager().formatMoney(finalAmount, sCurrency);
                        plugin.getMessageManager().send(sender, "admin-set-success",
                                Placeholder.parsed("player", targetPlayer.getName()),
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", sCurrencyName));

                        plugin.getMessageManager().send(targetPlayer, "admin-set-received",
                                Placeholder.parsed("amount", formatted),
                                Placeholder.parsed("currency", sCurrencyName));

                        fireTransactionEvent(sender, targetPlayer, finalAmount,
                                EconomyTransactionEvent.TransactionType.SET);
                    } else {
                        plugin.getMessageManager().send(sender, "admin-action-failed");
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
            plugin.getMessageManager().send(sender, "usage-import");
            return true;
        }

        // 檢查是否有遷移正在進行
        if (migrationInProgress.get()) {
            plugin.getMessageManager().send(sender, "migration-in-progress");
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
            plugin.getMessageManager().send(sender, "migration-unsupported-plugin",
                    Placeholder.parsed("plugin", args[1]));
            plugin.getMessageManager().send(sender, "migration-supported-plugins");
            return true;
        }

        // 檢查來源是否可用
        if (!migrator.isAvailable()) {
            plugin.getMessageManager().send(sender, "migration-source-not-found",
                    Placeholder.parsed("plugin", migrator.getName()));
            return true;
        }

        // 設定遷移中狀態
        migrationInProgress.set(true);

        // 發送開始訊息
        plugin.getMessageManager().send(sender, "migration-start", Placeholder.parsed("plugin", migrator.getName()));
        plugin.getLogger().info("開始資料遷移：" + migrator.getName());

        // 在非同步執行緒執行遷移
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            migrator.migrate(sender, processed -> {
                // 回報進度
                plugin.getMessageManager().send(sender, "migration-progress",
                        Placeholder.parsed("processed", String.valueOf(processed)));
            }).thenAccept(result -> {
                // 遷移完成
                migrationInProgress.set(false);

                if (result.totalCount() == 0) {
                    plugin.getMessageManager().send(sender, "migration-no-data");
                } else {
                    plugin.getMessageManager().send(sender, "migration-complete",
                            Placeholder.parsed("success", String.valueOf(result.successCount())),
                            Placeholder.parsed("fail", String.valueOf(result.failCount())));
                }

                plugin.getLogger().info("資料遷移完成：成功 " + result.successCount() +
                        "，失敗 " + result.failCount() + "，共 " + result.totalCount());

            }).exceptionally(throwable -> {
                // 遷移失敗
                migrationInProgress.set(false);
                plugin.getMessageManager().send(sender, "migration-failed",
                        Placeholder.parsed("error", throwable.getMessage()));
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
        plugin.getMessageManager().send(sender, "admin-help-header");
        plugin.getMessageManager().send(sender, "admin-help-money");
        plugin.getMessageManager().send(sender, "admin-help-withdraw");
        plugin.getMessageManager().send(sender, "admin-help-pay");

        if (sender.hasPermission("aceeconomy.admin")) {
            plugin.getMessageManager().send(sender, "admin-help-admin-header");
            plugin.getMessageManager().send(sender, "admin-help-give");
            plugin.getMessageManager().send(sender, "admin-help-take");
            plugin.getMessageManager().send(sender, "admin-help-set");
            plugin.getMessageManager().send(sender, "admin-help-history");
            plugin.getMessageManager().send(sender, "admin-help-rollback");
            plugin.getMessageManager().send(sender, "admin-help-import");
        }

        plugin.getMessageManager().send(sender, "admin-help-help");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            // 補全操作類型
            List<String> actions = new ArrayList<>(List.of("give", "take", "set", "import", "history", "rollback"));
            if (sender.hasPermission("aceeconomy.command.reload")) {
                actions.add("reload");
            }
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
                return List.of("100", "500", "1000", "10000");
            }
        } else if (args.length == 4) {
            String action = args[0].toLowerCase();
            if (action.equals("give") || action.equals("take") || action.equals("set")) {
                // 補全貨幣 ID
                String prefix = args[3].toLowerCase();
                return plugin.getCurrencyManager().getRegisteredCurrencies().stream()
                        .filter(c -> c.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    /**
     * 觸發經濟交易事件。
     *
     * @param sender 交易發起者
     * @param target 交易目標
     * @param amount 交易金額
     * @param type   交易類型
     */
    private void fireTransactionEvent(CommandSender sender, Player target, double amount,
            EconomyTransactionEvent.TransactionType type) {
        java.util.UUID senderUuid = null;
        if (sender instanceof Player player) {
            senderUuid = player.getUniqueId();
        }

        EconomyTransactionEvent event = new EconomyTransactionEvent(
                senderUuid, sender.getName(),
                target.getUniqueId(), target.getName(),
                amount, type);
        Bukkit.getPluginManager().callEvent(event);
    }
}
