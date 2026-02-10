package com.smile.aceeconomy.listeners;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.event.EconomyTransactionEvent;
import com.smile.aceeconomy.service.DiscordWebhook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 經濟日誌監聽器。
 * <p>
 * 監聽經濟交易事件並觸發 Discord Webhook 通知。
 * </p>
 *
 * @author Smile
 */
public class EconomyLogListener implements Listener {

    private final AceEconomy plugin;
    private final DiscordWebhook discordWebhook;

    /**
     * 建立經濟日誌監聽器。
     *
     * @param plugin         插件實例
     * @param discordWebhook Discord Webhook 服務
     */
    public EconomyLogListener(AceEconomy plugin, DiscordWebhook discordWebhook) {
        this.plugin = plugin;
        this.discordWebhook = discordWebhook;
    }

    /**
     * 處理經濟交易事件。
     *
     * @param event 交易事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEconomyTransaction(EconomyTransactionEvent event) {
        // 檢查 Discord 是否啟用
        if (!plugin.getConfigManager().isDiscordEnabled()) {
            return;
        }

        // 檢查金額是否達到門檻
        double minAmount = plugin.getConfigManager().getDiscordMinAmount();
        if (event.getAmount() < minAmount) {
            return;
        }

        // 檢查事件類型開關
        if (event.getType() == EconomyTransactionEvent.TransactionType.PAY) {
            if (!plugin.getConfigManager().isDiscordLogTransaction()) {
                return;
            }
        } else if (event.isAdminAction()) {
            if (!plugin.getConfigManager().isDiscordLogAdmin()) {
                return;
            }
        }

        // 發送 Discord 通知（已經是非同步）
        discordWebhook.sendTransactionAlert(event);
    }
}
