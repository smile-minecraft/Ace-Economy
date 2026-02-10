package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
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
            plugin.getMessageManager().send(sender, "general.no-permission");
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

        // 處理 migrate 指令 (內部儲存遷移)
        if (action.equals("migrate")) {
            return handleMigrate(sender, args);
        }

        // 處理 reload 指令
        if (action.equals("reload")) {
            if (!sender.hasPermission("aceeconomy.command.reload")) {
                plugin.getMessageManager().send(sender, "general.no-permission");
                return true;
            }

            plugin.getConfigManager().reload();
            plugin.getMessageManager().send(sender, "general.reload-success");

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
            plugin.getMessageManager().send(sender, "general.invalid-amount", Placeholder.parsed("amount", amountStr));
            return true;
        }

        // 金額驗證
        if (amount < 0 && !action.equals("set")) {
            plugin.getMessageManager().send(sender, "general.amount-must-be-positive");
            return true;
        }

        // 取得貨幣 ID (可選參數)
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        if (args.length >= 4) {
            String inputCurrency = args[3].toLowerCase();
            if (!plugin.getCurrencyManager().currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "general.unknown-currency",
                        Placeholder.parsed("currency", inputCurrency));
                return true;
            }
            currencyId = inputCurrency;
        }

        final double finalAmount = amount;
        final String finalCurrencyId = currencyId;

        // 查找目標玩家 (優先使用在線玩家)
        Player onlineTarget = Bukkit.getPlayer(targetName);
        if (onlineTarget != null) {
            executeAdminAction(sender, onlineTarget.getUniqueId(), onlineTarget.getName(), action, finalAmount,
                    finalCurrencyId);
            return true;
        }

        // 離線玩家處理 - 非同步查詢 UUID
        plugin.getUserCacheManager().getUUID(targetName).thenAccept(targetUuid -> {
            if (targetUuid == null) {
                plugin.getMessageManager().send(sender, "general.player-not-found",
                        Placeholder.parsed("player", targetName));
                return;
            }
            executeAdminAction(sender, targetUuid, targetName, action, finalAmount, finalCurrencyId);
        });

        return true;
    }

    private void executeAdminAction(CommandSender sender, java.util.UUID targetUuid, String targetName, String action,
            double amount, String currencyId) {
        // 先載入目標帳戶 (非同步)
        plugin.getStorageHandler().loadAccount(targetUuid).thenAccept(account -> {
            if (account == null) {
                plugin.getMessageManager().send(sender, "economy.account-not-found");
                return;
            }

            // Fix: Cache the account so CurrencyManager/EconomyProvider can access it
            plugin.getCurrencyManager().cacheAccount(account);

            String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
            final String fCurrencyName = currencyName;
            final double fAmount = amount;

            switch (action) {
                case "give" -> {
                    plugin.getEconomyProvider().deposit(targetUuid, currencyId, amount).thenAccept(success -> {
                        if (success) {
                            String formatted = plugin.getConfigManager().formatMoney(fAmount, currencyId);
                            plugin.getMessageManager().send(sender, "admin.give",
                                    Placeholder.parsed("player", targetName),
                                    Placeholder.parsed("amount", formatted),
                                    Placeholder.parsed("currency", fCurrencyName));

                            // 如果目標在線，通知他
                            Player targetPlayer = Bukkit.getPlayer(targetUuid);
                            if (targetPlayer != null) {
                                plugin.getMessageManager().send(targetPlayer, "admin.notify-give",
                                        Placeholder.parsed("amount", formatted),
                                        Placeholder.parsed("currency", fCurrencyName));
                            }

                            fireTransactionEvent(sender, targetUuid, targetName, fAmount,
                                    EconomyTransactionEvent.TransactionType.GIVE);
                        } else {
                            plugin.getMessageManager().send(sender, "admin.admin-action-failed");
                        }
                    });
                }

                case "take" -> {
                    plugin.getEconomyProvider().withdraw(targetUuid, currencyId, amount).thenAccept(success -> {
                        if (success) {
                            String formatted = plugin.getConfigManager().formatMoney(fAmount, currencyId);
                            plugin.getMessageManager().send(sender, "admin.take",
                                    Placeholder.parsed("player", targetName),
                                    Placeholder.parsed("amount", formatted),
                                    Placeholder.parsed("currency", fCurrencyName));

                            Player targetPlayer = Bukkit.getPlayer(targetUuid);
                            if (targetPlayer != null) {
                                plugin.getMessageManager().send(targetPlayer, "admin.notify-take",
                                        Placeholder.parsed("amount", formatted),
                                        Placeholder.parsed("currency", fCurrencyName));
                            }

                            fireTransactionEvent(sender, targetUuid, targetName, fAmount,
                                    EconomyTransactionEvent.TransactionType.TAKE);
                        } else {
                            plugin.getMessageManager().send(sender, "admin.admin-taking-failed");
                        }
                    });
                }

                case "set" -> {
                    plugin.getEconomyProvider().setBalance(targetUuid, currencyId, amount).thenAccept(success -> {
                        if (success) {
                            String formatted = plugin.getConfigManager().formatMoney(fAmount, currencyId);
                            plugin.getMessageManager().send(sender, "admin.set",
                                    Placeholder.parsed("player", targetName),
                                    Placeholder.parsed("amount", formatted),
                                    Placeholder.parsed("currency", fCurrencyName));

                            Player targetPlayer = Bukkit.getPlayer(targetUuid);
                            if (targetPlayer != null) {
                                plugin.getMessageManager().send(targetPlayer, "admin.notify-set",
                                        Placeholder.parsed("amount", formatted),
                                        Placeholder.parsed("currency", fCurrencyName));
                            }

                            fireTransactionEvent(sender, targetUuid, targetName, fAmount,
                                    EconomyTransactionEvent.TransactionType.SET);
                        } else {
                            plugin.getMessageManager().send(sender, "admin.admin-action-failed");
                        }
                    });
                }
                default -> sendHelp(sender);
            }
        });
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
            plugin.getMessageManager().send(sender, "admin.usage-import");
            return true;
        }

        // 檢查是否有遷移正在進行
        if (migrationInProgress.get()) {
            plugin.getMessageManager().send(sender, "admin.migration-in-progress");
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
            plugin.getMessageManager().send(sender, "admin.migration-unsupported-plugin",
                    Placeholder.parsed("plugin", args[1]));
            plugin.getMessageManager().send(sender, "admin.migration-supported-plugins");
            return true;
        }

        // 檢查來源是否可用
        if (!migrator.isAvailable()) {
            plugin.getMessageManager().send(sender, "admin.migration-source-not-found",
                    Placeholder.parsed("plugin", migrator.getName()));
            return true;
        }

        // 設定遷移中狀態
        migrationInProgress.set(true);

        // 發送開始訊息
        plugin.getMessageManager().send(sender, "admin.migration-start",
                Placeholder.parsed("plugin", migrator.getName()));
        plugin.getLogger().info("開始資料遷移：" + migrator.getName());

        // 在非同步執行緒執行遷移
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            migrator.migrate(sender, processed -> {
                // 回報進度
                plugin.getMessageManager().send(sender, "admin.migration-progress",
                        Placeholder.parsed("processed", String.valueOf(processed)));
            }).thenAccept(result -> {
                // 遷移完成
                migrationInProgress.set(false);

                if (result.totalCount() == 0) {
                    plugin.getMessageManager().send(sender, "admin.migration-no-data");
                } else {
                    plugin.getMessageManager().send(sender, "admin.migration-complete",
                            Placeholder.parsed("success", String.valueOf(result.successCount())),
                            Placeholder.parsed("fail", String.valueOf(result.failCount())));
                }

                plugin.getLogger().info("資料遷移完成：成功 " + result.successCount() +
                        "，失敗 " + result.failCount() + "，共 " + result.totalCount());

            }).exceptionally(throwable -> {
                // 遷移失敗
                migrationInProgress.set(false);
                plugin.getMessageManager().send(sender, "admin.migration-failed",
                        Placeholder.parsed("error", throwable.getMessage()));
                plugin.getLogger().severe("資料遷移失敗：" + throwable.getMessage());
                throwable.printStackTrace();
                return null;
            });
        });

        return true;
    }

    /**
     * 處理內部儲存遷移指令。
     * <p>
     * 將資料從目前的儲存系統遷移至指定目標 (sqlite/mysql)。
     * </p>
     *
     * @param sender 發送者
     * @param args   參數
     * @return 是否成功處理
     */
    private boolean handleMigrate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "用法: /aceeco migrate <sqlite|mysql>",
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            return true;
        }

        if (migrationInProgress.get()) {
            plugin.getMessageManager().send(sender, "admin.migration-in-progress");
            return true;
        }

        com.smile.aceeconomy.manager.MigrationManager mm = plugin.getMigrationManager();
        if (mm == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "遷移系統不可用 (儲存提供者未初始化)",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }

        String targetType = args[1].toLowerCase();
        if (!targetType.equals("mysql") && !targetType.equals("sqlite") && !targetType.equals("mariadb")) {
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "不支援的目標類型: " + targetType + "。支援: sqlite, mysql",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }

        migrationInProgress.set(true);
        sender.sendMessage(net.kyori.adventure.text.Component.text(
                "開始遷移至 " + targetType + "...",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        plugin.getLogger().info("開始儲存遷移至: " + targetType);

        mm.migrate(targetType).thenAccept(result -> {
            migrationInProgress.set(false);
            if (result.success()) {
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "遷移完成！使用者: " + result.users() + "，餘額: " + result.balances()
                                + "，耗時: " + result.duration() + "ms",
                        net.kyori.adventure.text.format.NamedTextColor.GREEN));
                plugin.getLogger().info("儲存遷移完成: " + result.message());
            } else {
                sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "遷移失敗: " + result.message(),
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                plugin.getLogger().severe("儲存遷移失敗: " + result.message());
            }
        }).exceptionally(ex -> {
            migrationInProgress.set(false);
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "遷移發生異常: " + ex.getMessage(),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            plugin.getLogger().severe("儲存遷移異常: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });

        return true;
    }

    /**
     * 發送指令幫助訊息。
     *
     * @param sender 接收者
     */
    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin.help-header");
        plugin.getMessageManager().send(sender, "admin.help-money");
        plugin.getMessageManager().send(sender, "admin.help-withdraw");
        plugin.getMessageManager().send(sender, "admin.help-pay");

        if (sender.hasPermission("aceeconomy.admin")) {
            plugin.getMessageManager().send(sender, "admin.help-admin-header");
            plugin.getMessageManager().send(sender, "admin.help-give");
            plugin.getMessageManager().send(sender, "admin.help-take");
            plugin.getMessageManager().send(sender, "admin.help-set");
            plugin.getMessageManager().send(sender, "admin.help-history");
            plugin.getMessageManager().send(sender, "admin.help-rollback");
            plugin.getMessageManager().send(sender, "admin.help-import");
            plugin.getMessageManager().send(sender, "admin.help-migrate");
        }

        plugin.getMessageManager().send(sender, "admin.help-help");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("aceeconomy.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            // 補全操作類型
            List<String> actions = new ArrayList<>(
                    List.of("give", "take", "set", "import", "migrate", "history", "rollback"));
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
            } else if (action.equals("migrate")) {
                // 補全儲存類型
                List<String> types = List.of("sqlite", "mysql");
                String prefix = args[1].toLowerCase();
                return types.stream()
                        .filter(t -> t.startsWith(prefix))
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
    private void fireTransactionEvent(CommandSender sender, java.util.UUID targetUuid, String targetName, double amount,
            EconomyTransactionEvent.TransactionType type) {
        java.util.UUID senderUuid = null;
        if (sender instanceof Player player) {
            senderUuid = player.getUniqueId();
        }

        EconomyTransactionEvent event = new EconomyTransactionEvent(
                senderUuid, sender.getName(),
                targetUuid, targetName,
                amount, type);
        Bukkit.getPluginManager().callEvent(event);
    }
}
