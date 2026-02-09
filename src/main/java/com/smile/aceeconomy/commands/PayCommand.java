package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.event.EconomyTransactionEvent;
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
            MessageUtils.sendError(sender, "此指令只能由玩家執行！");
            return true;
        }

        // 權限檢查
        if (!player.hasPermission("aceeconomy.pay")) {
            MessageUtils.sendError(sender, "你沒有權限使用此指令！");
            return true;
        }

        // 參數檢查: /pay <玩家> <金額> [貨幣]
        if (args.length < 2) {
            MessageUtils.send(sender, "用法：<white>/pay <玩家> <金額> [貨幣]</white>");
            return true;
        }

        String targetName = args[0];
        String amountStr = args[1];

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            MessageUtils.sendError(sender, "無效的金額：<white>" + amountStr + "</white>");
            return true;
        }

        // 防止負數
        if (amount <= 0) {
            MessageUtils.sendError(sender, "金額必須大於 0！");
            return true;
        }

        // 防止轉給自己
        if (targetName.equalsIgnoreCase(player.getName())) {
            MessageUtils.sendError(sender, "你不能轉帳給自己！");
            return true;
        }

        // 查找目標玩家
        Player targetPlayer = Bukkit.getPlayer(targetName);
        if (targetPlayer == null) {
            MessageUtils.sendError(sender, "玩家 <white>" + targetName + "</white> 不在線上！");
            return true;
        }

        // 檢查目標玩家帳戶是否已載入
        if (!economyProvider.hasAccount(targetPlayer.getUniqueId())) {
            MessageUtils.sendError(sender, "目標玩家帳戶尚未載入！");
            return true;
        }

        // 取得貨幣 ID (可選參數)
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        if (args.length >= 3) {
            String inputCurrency = args[2].toLowerCase();
            if (!plugin.getCurrencyManager().currencyExists(inputCurrency)) {
                MessageUtils.sendError(sender, "<red>未知的貨幣: <white>" + inputCurrency + "</white></red>");
                return true;
            }
            currencyId = inputCurrency;
        }

        // 檢查餘額是否足夠
        double currentBalance = plugin.getCurrencyManager().getBalance(player.getUniqueId(), currencyId);
        if (currentBalance < amount) {
            String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
            MessageUtils.sendError(sender, "<red>你沒有足夠的 " + currencyName + "！</red>");
            return true;
        }

        // 執行轉帳
        final double finalAmount = amount;
        final String finalCurrencyId = currencyId;
        String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();

        economyProvider.transfer(player.getUniqueId(), targetPlayer.getUniqueId(), currencyId, amount)
                .thenAccept(success -> {
                    if (success) {
                        MessageUtils.sendSuccess(player,
                                "已轉帳 " + MessageUtils.formatMoney(finalAmount) + " " + currencyName
                                        + " 給 <aqua><player></aqua>！",
                                "player", targetPlayer.getName());
                        MessageUtils.sendSuccess(targetPlayer,
                                "收到來自 <aqua><player></aqua> 的轉帳：" + MessageUtils.formatMoney(finalAmount) + " "
                                        + currencyName,
                                "player", player.getName());

                        EconomyTransactionEvent event = new EconomyTransactionEvent(
                                player.getUniqueId(), player.getName(),
                                targetPlayer.getUniqueId(), targetPlayer.getName(),
                                finalAmount, EconomyTransactionEvent.TransactionType.PAY);
                        Bukkit.getPluginManager().callEvent(event);
                    } else {
                        MessageUtils.sendError(player, "轉帳失敗！可能餘額不足或交易被取消。");
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
