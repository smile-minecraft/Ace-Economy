package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.event.EconomyTransactionEvent;
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
 * 轉帳指令處理器。
 * <p>
 * 處理 /pay 指令，實現玩家間轉帳功能。
 * 使用原子操作確保交易安全。
 * </p>
 *
 * @author Smile
 */
public class PayCommand implements CommandExecutor, TabCompleter {

    private final AceEconomy plugin;
    private final EconomyProvider economyProvider;

    /**
     * 建立轉帳指令處理器。
     *
     * @param plugin 插件實例
     */
    public PayCommand(AceEconomy plugin) {
        this.plugin = plugin;
        this.economyProvider = plugin.getEconomyProvider();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 必須是玩家
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "console-only-player");
            return true;
        }

        // 權限檢查
        if (!player.hasPermission("aceeconomy.pay")) {
            plugin.getMessageManager().send(sender, "no-permission");
            return true;
        }

        // 參數檢查: /pay <玩家> <金額> [貨幣]
        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "usage-pay");
            return true;
        }

        String targetName = args[0];
        String amountStr = args[1];

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().send(sender, "invalid-amount", Placeholder.parsed("amount", amountStr));
            return true;
        }

        // 防止負數
        if (amount <= 0) {
            plugin.getMessageManager().send(sender, "amount-must-be-positive");
            return true;
        }

        // 防止轉給自己
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().send(sender, "cannot-pay-self");
            return true;
        }

        // 查找目標玩家
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            plugin.getMessageManager().send(sender, "player-offline", Placeholder.parsed("player", targetName));
            return true;
        }

        // 檢查目標玩家帳戶是否已載入
        if (!economyProvider.hasAccount(targetPlayer.getUniqueId())) {
            plugin.getMessageManager().send(sender, "account-not-loaded");
            return true;
        }

        // 取得貨幣 ID (可選參數)
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        if (args.length >= 3) {
            String inputCurrency = args[2].toLowerCase();
            if (!plugin.getCurrencyManager().currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "unknown-currency",
                        Placeholder.parsed("currency", inputCurrency));
                return true;
            }
            currencyId = inputCurrency;
        }

        // 檢查餘額是否足夠
        double currentBalance = plugin.getCurrencyManager().getBalance(player.getUniqueId(), currencyId);
        if (currentBalance < amount) {
            String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
            plugin.getMessageManager().send(sender, "insufficient-funds-currency",
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("amount", String.valueOf(amount)));
            return true;
        }

        // 執行轉帳
        final double finalAmount = amount;
        final String finalCurrencyId = currencyId;
        String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();

        economyProvider.transfer(player.getUniqueId(), targetPlayer.getUniqueId(), currencyId, amount)
                .thenAccept(success -> {
                    if (success) {
                        // 使用 configManager.formatMoney 來格式化金額（包含貨幣符號）
                        String formattedAmount = plugin.getConfigManager().formatMoney(finalAmount, finalCurrencyId);

                        plugin.getMessageManager().send(player, "payment-sent-currency",
                                Placeholder.parsed("amount", formattedAmount),
                                Placeholder.parsed("currency_name", currencyName),
                                Placeholder.parsed("player", targetPlayer.getName()));

                        plugin.getMessageManager().send(targetPlayer, "payment-received-currency",
                                Placeholder.parsed("amount", formattedAmount),
                                Placeholder.parsed("currency_name", currencyName),
                                Placeholder.parsed("player", player.getName()));

                        EconomyTransactionEvent event = new EconomyTransactionEvent(
                                player.getUniqueId(), player.getName(),
                                targetPlayer.getUniqueId(), targetPlayer.getName(),
                                finalAmount, EconomyTransactionEvent.TransactionType.PAY);
                        Bukkit.getPluginManager().callEvent(event);
                    } else {
                        plugin.getMessageManager().send(player, "transaction-failed");
                    }
                });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            // 補全在線玩家名稱（排除自己）
            List<String> completions = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.equals(sender) && player.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(player.getName());
                }
            }
            return completions;
        } else if (args.length == 2) {
            return List.of("100", "500", "1000");
        } else if (args.length == 3) {
            // 補全貨幣 ID
            String prefix = args[2].toLowerCase();
            return plugin.getCurrencyManager().getRegisteredCurrencies().stream()
                    .filter(c -> c.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
