package com.smile.aceeconomy.commands;

import com.smile.aceeconomy.AceEconomy;
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

    /**
     * 建立轉帳指令處理器。
     *
     * @param plugin 插件實例
     */
    public PayCommand(AceEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {

        // 必須是玩家
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "general.console-only-player");
            return true;
        }

        // 權限檢查
        if (!player.hasPermission("aceeconomy.pay")) {
            plugin.getMessageManager().send(sender, "general.no-permission");
            return true;
        }

        // 參數檢查: /pay <玩家> <金額> [貨幣]
        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "usage.pay");
            return true;
        }

        String targetName = args[0];
        String amountStr = args[1];

        // 防止轉給自己
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().send(sender, "economy.cannot-pay-self");
            return true;
        }

        // 解析金額
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().send(sender, "general.invalid-amount", Placeholder.parsed("amount", amountStr));
            return true;
        }

        // 防止負數
        if (amount <= 0) {
            plugin.getMessageManager().send(sender, "general.amount-must-be-positive");
            return true;
        }

        // 取得貨幣 ID (可選參數)
        String currencyId = plugin.getCurrencyManager().getDefaultCurrencyId();
        if (args.length >= 3) {
            String inputCurrency = args[2].toLowerCase();
            if (!plugin.getCurrencyManager().currencyExists(inputCurrency)) {
                plugin.getMessageManager().send(sender, "general.unknown-currency",
                        Placeholder.parsed("currency", inputCurrency));
                return true;
            }
            currencyId = inputCurrency;
        }

        // 檢查餘額是否足夠 (Check Sender Balance)
        // 這裡在主線程檢查一次，稍後在 transfer 裡會再檢查原子性
        double currentBalance = plugin.getCurrencyManager().getBalance(player.getUniqueId(), currencyId);
        if (currentBalance < amount) {
            String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
            plugin.getMessageManager().send(sender, "economy.insufficient-funds-currency",
                    Placeholder.parsed("currency_name", currencyName),
                    Placeholder.parsed("amount", String.valueOf(amount)));
            return true;
        }

        // 查找目標玩家 (優先使用在線玩家)
        Player onlineTarget = Bukkit.getPlayer(targetName);
        if (onlineTarget != null) {
            executeTransfer(player, onlineTarget.getUniqueId(), onlineTarget.getName(), amount, currencyId);
            return true;
        }

        // 離線玩家處理 - 非同步查詢 UUID
        final String finalCurrencyId = currencyId;
        final double finalAmount = amount;

        if (plugin.getUserCacheManager() == null) {
            plugin.getMessageManager().send(sender, "general.offline-support-disabled");
            return true;
        }

        plugin.getUserCacheManager().getUUID(targetName).thenAccept(targetUuid -> {
            if (targetUuid == null) {
                // 資料庫也找不到 -> 真的找不到玩家
                plugin.getMessageManager().send(sender, "general.player-not-found",
                        Placeholder.parsed("player", targetName));
                return;
            }

            // 執行轉帳
            // 回到 Global or Main 執行 executeTransfer (雖然 transfer 本身又是 async)
            // 這裡直接執行 executeTransfer 即可，因為它會調用 loadAccount
            executeTransfer(player, targetUuid, targetName, finalAmount, finalCurrencyId);
        });

        return true;
    }

    private void executeTransfer(Player sender, java.util.UUID targetUuid, String targetName, double amount,
            String currencyId) {
        // 先載入目標帳戶 (非同步)
        plugin.getStorageHandler().loadAccount(targetUuid).thenAccept(targetAccount -> {
            if (targetAccount == null) {
                // 可能是新玩家還沒建立帳戶
                plugin.getMessageManager().send(sender, "general.player-no-account");
                return;
            }

            // 再次檢查餘額 (防止並發變動，雖然有點多餘但安全)
            // 注意: 若 sender 在此期間下線，getBalance 依然可以從 cache 取到
            double currentBalance = plugin.getCurrencyManager().getBalance(sender.getUniqueId(), currencyId);
            if (currentBalance < amount) {
                String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
                plugin.getMessageManager().send(sender, "economy.insufficient-funds-currency",
                        Placeholder.parsed("currency_name", currencyName),
                        Placeholder.parsed("amount", String.valueOf(amount)));
                return;
            }

            // 執行轉帳
            String currencyName = plugin.getConfigManager().getCurrency(currencyId).name();
            plugin.getEconomyProvider().transfer(sender.getUniqueId(), targetUuid, currencyId, amount)
                    .thenAccept(success -> {
                        if (success) {
                            String formattedAmount = plugin.getConfigManager().formatMoney(amount, currencyId);

                            plugin.getMessageManager().send(sender, "economy.payment-sent-currency",
                                    Placeholder.parsed("amount", formattedAmount),
                                    Placeholder.parsed("currency_name", currencyName),
                                    Placeholder.parsed("player", targetName));

                            // 如果目標在線，通知他
                            Player onlineTarget = Bukkit.getPlayer(targetUuid);
                            if (onlineTarget != null) {
                                plugin.getMessageManager().send(onlineTarget, "economy.payment-received-currency",
                                        Placeholder.parsed("amount", formattedAmount),
                                        Placeholder.parsed("currency_name", currencyName),
                                        Placeholder.parsed("player", sender.getName()));
                            }

                            // 觸發事件 (需回到 Global Region 或 Main Thread)
                            EconomyTransactionEvent event = new EconomyTransactionEvent(
                                    sender.getUniqueId(), sender.getName(),
                                    targetUuid, targetName,
                                    amount, EconomyTransactionEvent.TransactionType.PAY);

                            // Folia compatible
                            Bukkit.getGlobalRegionScheduler().execute(plugin,
                                    () -> Bukkit.getPluginManager().callEvent(event));
                        } else {
                            plugin.getMessageManager().send(sender, "general.transaction-failed");
                        }
                    });
        });
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
