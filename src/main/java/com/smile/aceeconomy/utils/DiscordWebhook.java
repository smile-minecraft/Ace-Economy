package com.smile.aceeconomy.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.Gson;

/**
 * Discord Webhook 工具類。
 * <p>
 * 提供發送 Discord Webhook 訊息的功能，支援 Embeds。
 * 所有請求皆為非同步執行，失敗時會靜默處理並記錄警告。
 * </p>
 *
 * @author Smile
 */
public class DiscordWebhook {

    private final String webhookUrl;
    private final Logger logger;
    private final Plugin plugin;
    private final Gson gson;

    /**
     * 建立 Discord Webhook 實例。
     *
     * @param webhookUrl Webhook URL
     * @param logger     日誌記錄器
     * @param plugin     插件實例（用於非同步排程）
     */
    public DiscordWebhook(String webhookUrl, Logger logger, Plugin plugin) {
        this.webhookUrl = webhookUrl;
        this.logger = logger;
        this.plugin = plugin;
        this.gson = new Gson();
    }

    /**
     * 發送 Embed 訊息到 Discord。
     *
     * @param embed Embed 建構器
     */
    public void sendEmbed(EmbedBuilder embed) {
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.equals("https://discord.com/api/webhooks/...")) {
            logger.warning("[DiscordWebhook] Webhook URL 未設定或無效，跳過發送。");
            return;
        }

        // 非同步執行 (CRITICAL: Folia-safe - AsyncScheduler)
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                URL url = java.net.URI.create(webhookUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);

                // 建立請求 Body
                Map<String, Object> payload = new HashMap<>();
                payload.put("embeds", List.of(embed.build()));

                String jsonPayload = gson.toJson(payload);

                // 發送請求
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                // 檢查回應碼
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    logger.warning("[DiscordWebhook] 發送失敗，回應碼: " + responseCode);
                } else {
                    logger.fine("[DiscordWebhook] 訊息已成功發送。");
                }

            } catch (Exception e) {
                // 靜默失敗，僅記錄警告，不中斷伺服器運行
                logger.warning("[DiscordWebhook] 發送訊息時發生錯誤: " + e.getMessage());
            }
        });
    }

    /**
     * Embed 建構器。
     * <p>
     * 用於建構 Discord Embed 物件。
     * </p>
     */
    public static class EmbedBuilder {
        private String title;
        private String description;
        private Integer color;
        private final List<Map<String, String>> fields = new ArrayList<>();
        private String timestamp;

        /**
         * 設定標題。
         *
         * @param title 標題
         * @return 建構器實例
         */
        public EmbedBuilder setTitle(String title) {
            this.title = title;
            return this;
        }

        /**
         * 設定描述。
         *
         * @param description 描述
         * @return 建構器實例
         */
        public EmbedBuilder setDescription(String description) {
            this.description = description;
            return this;
        }

        /**
         * 設定顏色。
         *
         * @param color 顏色（十進制整數，例如 0x00FF00 = 綠色）
         * @return 建構器實例
         */
        public EmbedBuilder setColor(int color) {
            this.color = color;
            return this;
        }

        /**
         * 新增欄位。
         *
         * @param name   欄位名稱
         * @param value  欄位值
         * @param inline 是否為行內顯示
         * @return 建構器實例
         */
        public EmbedBuilder addField(String name, String value, boolean inline) {
            Map<String, String> field = new HashMap<>();
            field.put("name", name);
            field.put("value", value);
            field.put("inline", String.valueOf(inline));
            fields.add(field);
            return this;
        }

        /**
         * 設定時間戳（自動使用當前時間）。
         *
         * @return 建構器實例
         */
        public EmbedBuilder setTimestamp() {
            this.timestamp = Instant.now().toString();
            return this;
        }

        /**
         * 建構 Embed 物件。
         *
         * @return Embed Map
         */
        Map<String, Object> build() {
            Map<String, Object> embed = new HashMap<>();
            if (title != null) {
                embed.put("title", title);
            }
            if (description != null) {
                embed.put("description", description);
            }
            if (color != null) {
                embed.put("color", color);
            }
            if (!fields.isEmpty()) {
                embed.put("fields", fields);
            }
            if (timestamp != null) {
                embed.put("timestamp", timestamp);
            }
            return embed;
        }
    }
}
