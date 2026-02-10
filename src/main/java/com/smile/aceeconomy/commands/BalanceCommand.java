package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.CurrencyManager;
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
import java.util.stream.Collectors;

/**
 * 餘額查詢指令處理器。
 * <p>
 * 處理 /money 和 /balance 指令。
 * </p>
 *
 * @author Smile
 */
public class BalanceCommand implements CommandExecutor, TabCompleter {

    private final AceEconomy plugin;
    private final CurrencyManager currencyManager;

    /**
     * 建立餘額指令處理器。
     *
     * @param plugin 插件實例
     */
    public BalanceCommand(AceEconomy plugin) {
        this.plugin = plugin;
        this.currencyManager = plugin.getCurrencyManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 權限檢查
        if (!sender.hasPermission("aceeconomy.command.money")) {
            plugin.getMessageManager().send(sender, "general.no-permission");
            return true;
        }

        // /money 或 /balance（無參數）- 查看自己餘額 (預設貨幣)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, "general.console-only-player");
                return true;
            }

            String defaultCurrency = currencyManager.getDefaultCurrencyId();
            double balance = currencyManager.getBalance(player.getUniqueId(), defaultCurrency);
            String currencyName = plugin.getConfigManager().getCurrency(defaultCurrency).name();
            String formattedBalance = plugin.getConfigManager().formatMoney(balance, defaultCurrency);

            plugin.getMessageManager().send(sender, "economy.balance-check-currency",
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("balance", formattedBalance));

            if (plugin.getConfigManager().isAllowNegativeBalance()
                    && defaultCurrency.equals(currencyManager.getDefaultCurrencyId())) {
                double debtLimit = currencyManager.getDebtLimit(player.getUniqueId());
                if (debtLimit > 0) {
                    String formattedLimit = plugin.getConfigManager().formatMoney(debtLimit, defaultCurrency);
                    plugin.getMessageManager().send(sender, "economy.debt-status",
                            Placeholder.parsed("limit", formattedLimit));
                }
            }
            return true;
        }

        // /balance <player> [貨幣] - 查看他人餘額
        String targetName = args[0];

        // 取得貨幣 ID (可選參數)
        String currencyId = currencyManager.getDefaultCurrencyId();
        if (args.length >= 2) {
            String inputCurrency = args[1].toLowerCase();
            if (!currencyManager.currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "general.unknown-currency",
                        Placeholder.parsed("currency", inputCurrency));
                return true;
            }
            currencyId = inputCurrency;
        }

        final String finalCurrencyId = currencyId;
        String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();

        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer != null) {
            // 在線玩家
            double balance = currencyManager.getBalance(targetPlayer.getUniqueId(), finalCurrencyId);
            String formattedBalance = plugin.getConfigManager().formatMoney(balance, finalCurrencyId);

            plugin.getMessageManager().send(sender, "economy.balance-check-currency-other",
                    Placeholder.parsed("player", targetPlayer.getName()),
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("balance", formattedBalance));

            if (plugin.getConfigManager().isAllowNegativeBalance()
                    && finalCurrencyId.equals(currencyManager.getDefaultCurrencyId())) {
                double debtLimit = currencyManager.getDebtLimit(targetPlayer.getUniqueId());
                if (debtLimit > 0) {
                    String formattedLimit = plugin.getConfigManager().formatMoney(debtLimit, finalCurrencyId);
                    plugin.getMessageManager().send(sender, "economy.debt-status",
                            Placeholder.parsed("limit", formattedLimit));
                }
            }
        } else {
            // 離線玩家 - 非同步查詢
            plugin.getUserCacheManager().getUUID(targetName).thenAccept(uuid -> {
                if (uuid == null) {
                    plugin.getMessageManager().send(sender, "general.player-not-found",
                            Placeholder.parsed("player", targetName));
                    return;
                }

                plugin.getStorageHandler().loadAccount(uuid).thenAccept(account -> {
                    if (account == null) {
                        plugin.getMessageManager().send(sender, "economy.account-not-found");
                    } else {
                        double balance = account.getBalance(finalCurrencyId);
                        String formattedBalance = plugin.getConfigManager().formatMoney(balance, finalCurrencyId);

                        plugin.getMessageManager().send(sender, "economy.balance-check-currency-other",
                                Placeholder.parsed("player", targetName),
                                Placeholder.parsed("currency_name", currencyName),
                                Placeholder.parsed("balance", formattedBalance));

                        if (plugin.getConfigManager().isAllowNegativeBalance()
                                && finalCurrencyId.equals(currencyManager.getDefaultCurrencyId())) {
                            double debtLimit = currencyManager.getDebtLimit(uuid);
                            if (debtLimit > 0) {
                                String formattedLimit = plugin.getConfigManager().formatMoney(debtLimit,
                                        finalCurrencyId);
                                plugin.getMessageManager().send(sender, "economy.debt-status",
                                        Placeholder.parsed("limit", formattedLimit));
                            }
                        }
                    }
                });
            });
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            // 補全在線玩家名稱
            List<String> completions = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(player.getName());
                }
            }
            return completions;
        } else if (args.length == 2) {
            // 補全貨幣 ID
            String prefix = args[1].toLowerCase();
            return currencyManager.getRegisteredCurrencies().stream()
                    .filter(c -> c.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
