package com.smile.aceeconomy.service;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.event.EconomyTransactionEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Discord Webhook 服務。
 * <p>
 * 使用 Java 11 HttpClient 非同步發送訊息至 Discord。
 * </p>
 *
 * @author Smile
 */
public class DiscordWebhook {

    private final AceEconomy plugin;
    private final Logger logger;
    private final HttpClient httpClient;

    private static final int TIMEOUT_SECONDS = 10;

    /**
     * 建立 Discord Webhook 服務。
     *
     * @param plugin 插件實例
     */
    public DiscordWebhook(AceEconomy plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 發送交易通知至 Discord。
     *
     * @param event 交易事件
     */
    public void sendTransactionAlert(EconomyTransactionEvent event) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }

        // 建立 Embed JSON
        String json = buildEmbedJson(event);

        // 建立 HTTP 請求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        // 非同步發送
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode == 429) {
                        // Rate limited - 靜默處理
                        logger.fine("Discord Webhook 觸發速率限制");
                    } else if (statusCode < 200 || statusCode >= 300) {
                        logger.fine("Discord Webhook 回應異常: " + statusCode);
                    }
                })
                .exceptionally(throwable -> {
                    // 連線錯誤 - 靜默處理，避免刷屏
                    logger.fine("Discord Webhook 連線失敗: " + throwable.getMessage());
                    return null;
                });
    }

    /**
     * 建立 Discord Embed JSON。
     *
     * @param event 交易事件
     * @return JSON 字串
     */
    private String buildEmbedJson(EconomyTransactionEvent event) {
        // 顏色：管理員操作用紅色，玩家交易用綠色
        int color = event.isAdminAction() ? 0xFF5555 : 0x55FF55;

        String typeDisplay = switch (event.getType()) {
            case PAY -> "💸 轉帳";
            case GIVE -> "🎁 給予";
            case TAKE -> "💳 扣除";
            case SET -> "⚙️ 設定";
        };

        String senderField = event.getSender() != null
                ? String.format("{\"name\": \"發送者\", \"value\": \"%s\", \"inline\": true}",
                        escapeJson(event.getSenderName()))
                : String.format("{\"name\": \"執行者\", \"value\": \"%s\", \"inline\": true}",
                        escapeJson(event.getSenderName()));

        String formattedAmount = plugin.getConfigManager().formatMoney(event.getAmount());

        return String.format("""
                {
                    "embeds": [{
                        "title": "📊 交易通知",
                        "color": %d,
                        "fields": [
                            %s,
                            {"name": "接收者", "value": "%s", "inline": true},
                            {"name": "金額", "value": "%s", "inline": true},
                            {"name": "類型", "value": "%s", "inline": false}
                        ],
                        "footer": {"text": "AceEconomy"},
                        "timestamp": "%s"
                    }]
                }
                """,
                color,
                senderField,
                escapeJson(event.getReceiverName()),
                escapeJson(formattedAmount),
                typeDisplay,
                java.time.Instant.now().toString());
    }

    /**
     * 轉義 JSON 特殊字元。
     *
     * @param input 輸入字串
     * @return 轉義後的字串
     */
    private String escapeJson(String input) {
        if (input == null)
            return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
