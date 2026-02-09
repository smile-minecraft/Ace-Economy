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

    private final EconomyProvider economyProvider;

    /**
     * 建立轉帳指令處理器。
     *
     * @param plugin 插件實例
     */
    public PayCommand(AceEconomy plugin) {
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

        // 參數檢查
        if (args.length < 2) {
            MessageUtils.send(sender, "用法：<white>/pay <玩家> <金額></white>");
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

        // 執行轉帳（使用 EconomyProvider 的原子 transfer 方法）
        final double finalAmount = amount;
        economyProvider.transfer(player.getUniqueId(), targetPlayer.getUniqueId(), amount)
                .thenAccept(success -> {
                    if (success) {
                        // 發送成功訊息給雙方
                        MessageUtils.sendSuccess(player,
                                "已轉帳 " + MessageUtils.formatMoney(finalAmount) + " 給 <aqua><player></aqua>！",
                                "player", targetPlayer.getName());
                        MessageUtils.sendSuccess(targetPlayer,
                                "收到來自 <aqua><player></aqua> 的轉帳：" + MessageUtils.formatMoney(finalAmount),
                                "player", player.getName());

                        // 觸發交易事件（非同步事件）
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
            // 提示金額
            return List.of("100", "500", "1000");
        }
        return List.of();
    }
}
