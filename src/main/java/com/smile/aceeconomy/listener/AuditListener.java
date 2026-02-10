package com.smile.aceeconomy.listener;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.event.EconomyTransactionEvent;
import com.smile.aceeconomy.utils.DiscordWebhook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/**
 * Discord 審計日誌監聽器。
 * <p>
 * 監聽經濟交易事件，並發送到 Discord Webhook。
 * </p>
 *
 * @author Smile
 */
public class AuditListener implements Listener {

    private final AceEconomy plugin;
    private final Logger logger;
    private DiscordWebhook webhook;

    public AuditListener(AceEconomy plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * 初始化或重新載入 Webhook。
     */
    public void reloadWebhook() {
        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        this.webhook = new DiscordWebhook(webhookUrl, logger, plugin);
    }

    /**
     * 監聽經濟交易事件。
     *
     * @param event 經濟交易事件
     */
    @EventHandler
    public void onTransaction(EconomyTransactionEvent event) {
        // 檢查是否啟用 Discord 日誌
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) {
            return;
        }

        // 檢查最小金額門檻
        double minAmount = plugin.getConfig().getDouble("discord.min-amount", 0.0);
        if (event.getAmount() < minAmount) {
            return;
        }

        // 檢查事件類型開關
        boolean logTransaction = plugin.getConfig().getBoolean("discord.log-events.transaction", true);
        boolean logAdmin = plugin.getConfig().getBoolean("discord.log-events.admin", true);

        if (event.getType() == EconomyTransactionEvent.TransactionType.PAY) {
            if (!logTransaction) {
                return;
            }
            sendTransactionEmbed(event);
        } else if (event.isAdminAction()) {
            if (!logAdmin) {
                return;
            }
            sendAdminEmbed(event);
        }
    }

    /**
     * 發送玩家交易 Embed。
     *
     * @param event 交易事件
     */
    private void sendTransactionEmbed(EconomyTransactionEvent event) {
        if (webhook == null) {
            reloadWebhook();
        }

        DiscordWebhook.EmbedBuilder embed = new DiscordWebhook.EmbedBuilder()
                .setTitle("💸 玩家轉帳")
                .setDescription(String.format("**%s** 向 **%s** 轉帳了 **%.2f** 元",
                        event.getSenderName(),
                        event.getReceiverName(),
                        event.getAmount()))
                .setColor(0x3498db) // 藍色
                .addField("發送者", event.getSenderName(), true)
                .addField("接收者", event.getReceiverName(), true)
                .addField("金額", String.format("%.2f", event.getAmount()), true)
                .setTimestamp();

        webhook.sendEmbed(embed);
    }

    /**
     * 發送管理員操作 Embed。
     *
     * @param event 交易事件
     */
    private void sendAdminEmbed(EconomyTransactionEvent event) {
        if (webhook == null) {
            reloadWebhook();
        }

        String action = switch (event.getType()) {
            case GIVE -> "給予";
            case TAKE -> "扣除";
            case SET -> "設定餘額";
            default -> "未知操作";
        };

        int color = switch (event.getType()) {
            case GIVE -> 0x2ecc71; // 綠色
            case TAKE -> 0xe74c3c; // 紅色
            case SET -> 0xf39c12; // 橙色
            default -> 0x95a5a6; // 灰色
        };

        DiscordWebhook.EmbedBuilder embed = new DiscordWebhook.EmbedBuilder()
                .setTitle("⚙️ 管理員操作")
                .setDescription(String.format("**%s** 對 **%s** 執行了 **%s** 操作",
                        event.getSenderName(),
                        event.getReceiverName(),
                        action))
                .setColor(color)
                .addField("操作者", event.getSenderName(), true)
                .addField("目標玩家", event.getReceiverName(), true)
                .addField("操作", action, true)
                .addField("金額", String.format("%.2f", event.getAmount()), false)
                .setTimestamp();

        webhook.sendEmbed(embed);
    }
}
