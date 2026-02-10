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
import java.util.UUID;
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
        if (!sender.hasPermission("aceeconomy.use")) {
            plugin.getMessageManager().send(sender, "no-permission");
            return true;
        }

        // /money 或 /balance（無參數）- 查看自己餘額 (預設貨幣)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, "console-only-player");
                return true;
            }

            String defaultCurrency = currencyManager.getDefaultCurrencyId();
            double balance = currencyManager.getBalance(player.getUniqueId(), defaultCurrency);
            String currencyName = plugin.getConfigManager().getCurrency(defaultCurrency).name();
            String formattedBalance = plugin.getConfigManager().formatMoney(balance, defaultCurrency);

            plugin.getMessageManager().send(sender, "balance-self",
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("amount", formattedBalance));
            return true;
        }

        // /balance <player> [貨幣] - 查看他人餘額
        String targetName = args[0];

        // 取得貨幣 ID (可選參數)
        String currencyId = currencyManager.getDefaultCurrencyId();
        if (args.length >= 2) {
            String inputCurrency = args[1].toLowerCase();
            if (!currencyManager.currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "unknown-currency",
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

            plugin.getMessageManager().send(sender, "balance-other",
                    Placeholder.parsed("player", targetPlayer.getName()),
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("amount", formattedBalance));
        } else {
            // 離線玩家 - 非同步查詢
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
                // offlinePlayer.getUniqueId() is safe, but getting name might be tricky if not
                // cached.
                // But Bukkit.getOfflinePlayer(name) returns an object that has name if
                // resolved.
                // If not played before, UUID might be random (UUID version 3).

                if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
                    plugin.getMessageManager().send(sender, "player-offline", Placeholder.parsed("player", targetName));
                    return;
                }

                UUID uuid = offlinePlayer.getUniqueId();
                plugin.getStorageHandler().loadAccount(uuid).thenAccept(account -> {
                    if (account == null) {
                        plugin.getMessageManager().send(sender, "account-not-found");
                    } else {
                        double balance = account.getBalance(finalCurrencyId);
                        String formattedBalance = plugin.getConfigManager().formatMoney(balance, finalCurrencyId);

                        plugin.getMessageManager().send(sender, "balance-other",
                                Placeholder.parsed("player",
                                        offlinePlayer.getName() != null ? offlinePlayer.getName() : targetName),
                                Placeholder.parsed("currency_name", currencyName),
                                Placeholder.parsed("amount", formattedBalance));
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
