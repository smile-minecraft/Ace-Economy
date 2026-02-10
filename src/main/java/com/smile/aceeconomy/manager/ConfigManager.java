package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Currency;
import net.kyori.adventure.text.Component;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 設定檔管理器。
 * <p>
 * 管理 config.yml 和 messages.yml 的載入、存取和重載。
 * </p>
 *
 * @author Smile
 */
public class ConfigManager {

    private final AceEconomy plugin;
    private FileConfiguration config;

    private String locale;

    // 快取的設定值
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;

    private final Map<String, Currency> currencies = new java.util.HashMap<>();
    private Currency defaultCurrency;

    // Legacy support fields
    private double startBalance;
    private String mainCommandAlias;

    // Discord 設定
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private double discordMinAmount;

    /**
     * 建立設定檔管理器。
     *
     * @param plugin 插件實例
     */
    public ConfigManager(AceEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * 載入所有設定檔。
     */
    /**
     * 載入所有設定檔。
     */
    public void load() {
        // 儲存並載入預設 config.yml
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 檢查並更新 config.yml (Migration to 1.3)
        checkAndMigrateConfig();

        // 重新載入以確保取得最新值
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 確保所有語言檔案都存在
        saveDefaultLanguageFiles();

        // 載入語言設定
        this.locale = config.getString("locale", "zh_TW");

        // 快取設定值
        cacheConfigValues();

        plugin.getLogger().info("已載入設定檔");
    }

    /**
     * 確保所有預設語言檔案都已儲存至磁碟。
     */
    private void saveDefaultLanguageFiles() {
        String[] locales = { "en_US", "zh_TW", "zh_CN" };
        for (String loc : locales) {
            String fileName = "lang/messages_" + loc + ".yml";
            File file = new File(plugin.getDataFolder(), fileName);
            if (!file.exists()) {
                try {
                    plugin.saveResource(fileName, false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("無法儲存預設語言檔案: " + fileName);
                }
            }
        }
    }

    /**
     * 檢查並遷移 config.yml 至最新版本。
     */
    private void checkAndMigrateConfig() {
        // 檢查是否為舊版設定 (缺 locale)
        if (!config.contains("locale")) {
            plugin.getLogger().info("Detecting legacy config (missing 'locale'). Migrating to version 1.3...");

            config.set("locale", "zh_TW"); // Default to zh_TW for upgrading users

            // Update version
            config.set("config-version", 1.3);

            plugin.saveConfig();
            plugin.getLogger().info("Config migrated to version 1.3.");
        }

        // 使用原有的 checkAndUpdate 進行標準更新檢查
        checkAndUpdate("config.yml");
    }

    /**
     * 檢查並更新設定檔版本。
     *
     * @param filename 檔案名稱 (config.yml 或 messages.yml)
     */
    private void checkAndUpdate(String filename) {
        try {
            File file = new File(plugin.getDataFolder(), filename);
            if (!file.exists()) {
                return;
            }

            YamlConfiguration diskConfig = YamlConfiguration.loadConfiguration(file);

            InputStream resourceStream = plugin.getResource(filename);
            if (resourceStream == null) {
                return;
            }

            YamlConfiguration resourceConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resourceStream, StandardCharsets.UTF_8));

            double diskVersion = diskConfig.getDouble("config-version", 0.0);
            double resourceVersion = resourceConfig.getDouble("config-version", 1.0);

            if (diskVersion < resourceVersion) {
                int addedKeys = 0;

                // 遞迴檢查並加入缺少的鍵值
                for (String key : resourceConfig.getKeys(true)) {
                    if (!diskConfig.contains(key)) {
                        diskConfig.set(key, resourceConfig.get(key));
                        addedKeys++;
                    }
                }

                // 更新版本號
                diskConfig.set("config-version", resourceVersion);

                if (addedKeys > 0 || diskVersion != resourceVersion) {
                    diskConfig.save(file);
                    plugin.getLogger()
                            .info("已更新 " + filename + " 至版本 " + resourceVersion + " (新增 " + addedKeys + " 個設定)");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("更新設定檔 " + filename + " 時發生錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 重新載入所有設定檔。
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.locale = config.getString("locale", "zh_TW");

        // 重載訊息管理器
        if (plugin.getMessageManager() != null) {
            plugin.getMessageManager().load(locale);
        }

        cacheConfigValues();
        plugin.getLogger().info("已重新載入設定檔");
    }

    /**
     * 取得當前語言設定。
     *
     * @return 語言代碼
     */
    public String getLocale() {
        return locale;
    }

    /**
     * 快取設定值以提升效能。
     */
    private void cacheConfigValues() {
        // 資料庫設定
        databaseType = config.getString("storage.type", "sqlite").toLowerCase();
        mysqlHost = config.getString("storage.mysql.host", "localhost");
        mysqlPort = config.getInt("storage.mysql.port", 3306);
        mysqlDatabase = config.getString("storage.mysql.database", "aceeconomy");
        mysqlUsername = config.getString("storage.mysql.username", "root");
        mysqlPassword = config.getString("storage.mysql.password", "password");

        // 貨幣設定
        loadCurrencies();

        // 起始餘額
        startBalance = config.getDouble("start-balance", 1000.0);

        // 訊息前綴由 MessageManager 管理
        // prefix = messages.getString("prefix", "<gold>[AceEconomy]</gold> <gray>");

        // 主指令別名
        mainCommandAlias = config.getString("settings.main-command-alias", "aceeco");

        // Discord 設定
        discordEnabled = config.getBoolean("discord.enabled", false);
        discordWebhookUrl = config.getString("discord.webhook-url", "");
        discordMinAmount = config.getDouble("discord.min-amount", 10000.0);
    }

    // ==================== 資料庫設定 ====================

    /**
     * 取得資料庫類型。
     *
     * @return "sqlite" 或 "mysql"
     */
    public String getDatabaseType() {
        return databaseType;
    }

    /**
     * 檢查是否使用 MySQL。
     *
     * @return 是否使用 MySQL
     */
    public boolean isMySQL() {
        return "mysql".equals(databaseType);
    }

    /**
     * 取得 MySQL 主機。
     *
     * @return MySQL 主機位址
     */
    public String getMySQLHost() {
        return mysqlHost;
    }

    /**
     * 取得 MySQL 連接埠。
     *
     * @return MySQL 連接埠
     */
    public int getMySQLPort() {
        return mysqlPort;
    }

    /**
     * 取得 MySQL 資料庫名稱。
     *
     * @return MySQL 資料庫名稱
     */
    public String getMySQLDatabase() {
        return mysqlDatabase;
    }

    /**
     * 取得 MySQL 使用者名稱。
     *
     * @return MySQL 使用者名稱
     */
    public String getMySQLUsername() {
        return mysqlUsername;
    }

    /**
     * 取得 MySQL 密碼。
     *
     * @return MySQL 密碼
     */
    public String getMySQLPassword() {
        return mysqlPassword;
    }

    /**
     * 載入貨幣設定。
     * 自動偵測舊版設定並遷移至新格式。
     */
    private void loadCurrencies() {
        currencies.clear();
        defaultCurrency = null;

        // 檢查是否有新的 currencies 區塊
        if (config.isConfigurationSection("currencies")) {
            var section = config.getConfigurationSection("currencies");
            for (String key : section.getKeys(false)) {
                String name = section.getString(key + ".name", "Unknown");
                String symbol = section.getString(key + ".symbol", "$");
                String format = section.getString(key + ".format", "#,##0.00");
                boolean isDefault = section.getBoolean(key + ".default", false);

                Currency currency = new Currency(key, name, symbol, format, isDefault);
                currencies.put(key, currency);

                if (isDefault) {
                    defaultCurrency = currency;
                }
            }
        }

        // === 自動遷移邏輯 ===
        // 若 currencies 為空（舊版 config 或損壞的設定），進行遷移
        if (currencies.isEmpty()) {
            plugin.getLogger().info("[AceEconomy] Detecting legacy config. Migrating to Multi-Currency format...");

            // 讀取舊版設定
            String oldSymbol = config.getString("currency.symbol", "$");
            String oldFormat = config.getString("currency.format", "#,##0.00");

            // 設定新值
            config.set("currencies.dollar.name", "金幣");
            config.set("currencies.dollar.symbol", oldSymbol);
            config.set("currencies.dollar.format", oldFormat);
            config.set("currencies.dollar.default", true);

            // 移除舊設定
            config.set("currency", null);

            // 更新版本號
            config.set("config-version", "1.2");

            // 儲存至磁碟 (關鍵步驟)
            plugin.saveConfig();
            plugin.getLogger().info("[AceEconomy] Config migration complete! Saved new format to config.yml.");

            // 建立貨幣物件
            defaultCurrency = new Currency("dollar", "金幣", oldSymbol, oldFormat, true);
            currencies.put("dollar", defaultCurrency);
        }

        // === 安全檢查 ===
        // 若沒有設定預設貨幣，強制使用第一個可用的貨幣或建立 fallback
        if (defaultCurrency == null) {
            if (!currencies.isEmpty()) {
                // 使用第一個貨幣作為預設
                defaultCurrency = currencies.values().iterator().next();
                plugin.getLogger().warning("未找到標記為 default 的貨幣，使用 '" + defaultCurrency.id() + "' 作為預設貨幣。");
            } else {
                // 完全沒有貨幣設定，建立 hardcoded fallback
                plugin.getLogger().severe("無法載入任何貨幣設定！使用 hardcoded fallback (dollar)。");
                defaultCurrency = new Currency("dollar", "金幣", "$", "#,##0.00", true);
                currencies.put("dollar", defaultCurrency);
            }
        }

        // === Debug Logging ===
        plugin.getLogger().info("=== Multi-Currency System Loaded ===");
        plugin.getLogger().info("Default Currency: " + (defaultCurrency != null ? defaultCurrency.id() : "NULL"));
        for (Currency c : currencies.values()) {
            String status = c.isDefault() ? " [DEFAULT]" : "";
            plugin.getLogger().info(String.format(" - [%s] %s (%s)%s",
                    c.id(), c.name(), c.symbol(), status));
        }
        plugin.getLogger().info("====================================");
    }

    /**
     * 取得所有貨幣。
     * 
     * @return 貨幣 Map (ID -> Currency)
     */
    public Map<String, Currency> getCurrencies() {
        return java.util.Collections.unmodifiableMap(currencies);
    }

    /**
     * 取得指定 ID 的貨幣。
     * 
     * @param id 貨幣 ID
     * @return 貨幣物件，若找不到回傳預設貨幣
     */
    public Currency getCurrency(String id) {
        return currencies.getOrDefault(id, defaultCurrency);
    }

    /**
     * 取得預設貨幣。
     * 
     * @return 預設貨幣
     */
    public Currency getDefaultCurrency() {
        return defaultCurrency;
    }

    // ==================== 貨幣設定 (舊版相容 API) ====================

    /**
     * 取得貨幣符號 (預設貨幣)。
     *
     * @return 貨幣符號
     */
    public String getCurrencySymbol() {
        return defaultCurrency.symbol();
    }

    /**
     * 取得貨幣格式 (預設貨幣)。
     *
     * @return 貨幣格式字串
     */
    public String getCurrencyFormat() {
        return defaultCurrency.format();
    }

    /**
     * 格式化金額 (預設貨幣)。
     *
     * @param amount 金額
     * @return 格式化後的金額字串
     */
    public String formatMoney(double amount) {
        return defaultCurrency.format(amount);
    }

    /**
     * 格式化金額 (指定貨幣)。
     *
     * @param amount   金額
     * @param currency 貨幣物件
     * @return 格式化後的金額字串
     */
    public String formatMoney(double amount, Currency currency) {
        return currency.format(amount);
    }

    /**
     * 格式化金額 (透過貨幣 ID)。
     *
     * @param amount     金額
     * @param currencyId 貨幣 ID
     * @return 格式化後的金額字串
     */
    public String formatMoney(double amount, String currencyId) {
        Currency currency = getCurrency(currencyId);
        return currency.format(amount);
    }

    /**
     * 取得新玩家起始餘額。
     *
     * @return 起始餘額
     */
    public double getStartBalance() {
        return startBalance;
    }

    // ==================== Discord 設定 ====================

    /**
     * 檢查 Discord Webhook 是否啟用。
     *
     * @return 是否啟用
     */
    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    /**
     * 取得 Discord Webhook URL。
     *
     * @return Webhook URL
     */
    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    /**
     * 取得 Discord 記錄最低金額門檻。
     *
     * @return 最低金額
     */
    public double getDiscordMinAmount() {
        return discordMinAmount;
    }

    // ==================== 一般設定 ====================

    /**
     * 取得主指令別名。
     *
     * @return 主指令別名
     */
    public String getMainCommandAlias() {
        return mainCommandAlias;
    }

    // ==================== 訊息系統 ====================

    /**
     * 取得訊息前綴。
     *
     * @return 訊息前綴
     */
    public String getPrefix() {
        return plugin.getMessageManager().getPrefix();
    }

    /**
     * 取得原始訊息字串。
     *
     * @param key 訊息鍵值
     * @return 原始訊息字串
     */
    public String getRawMessage(String key) {
        return plugin.getMessageManager().getRawMessage(key);
    }

    /**
     * 取得並解析訊息為 Component。
     *
     * @param key 訊息鍵值
     * @return 解析後的 Component
     */
    public Component getMessage(String key) {
        return plugin.getMessageManager().get(key);
    }

    /**
     * 取得並解析訊息為 Component（帶佔位符）。
     *
     * @param key          訊息鍵值
     * @param placeholders 佔位符對應表
     * @return 解析後的 Component
     */
    public Component getMessage(String key, Map<String, String> placeholders) {
        TagResolver.Builder resolverBuilder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolverBuilder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        return plugin.getMessageManager().get(key, resolverBuilder.build());
    }

    /**
     * 取得並解析訊息為 Component（單一佔位符）。
     *
     * @param key   訊息鍵值
     * @param phKey 佔位符名稱
     * @param phVal 佔位符值
     * @return 解析後的 Component
     */
    public Component getMessage(String key, String phKey, String phVal) {
        return plugin.getMessageManager().get(key, Placeholder.parsed(phKey, phVal));
    }

    /**
     * 取得並解析訊息為 Component（兩個佔位符）。
     *
     * @param key    訊息鍵值
     * @param phKey1 佔位符1名稱
     * @param phVal1 佔位符1值
     * @param phKey2 佔位符2名稱
     * @param phVal2 佔位符2值
     * @return 解析後的 Component
     */
    public Component getMessage(String key, String phKey1, String phVal1, String phKey2, String phVal2) {
        return plugin.getMessageManager().get(key,
                Placeholder.parsed(phKey1, phVal1),
                Placeholder.parsed(phKey2, phVal2));
    }

    /**
     * 發送訊息給接收者。
     *
     * @param sender 接收者
     * @param key    訊息鍵值
     */
    public void sendMessage(CommandSender sender, String key) {
        plugin.getMessageManager().send(sender, key);
    }

    /**
     * 發送訊息給接收者（帶佔位符）。
     *
     * @param sender       接收者
     * @param key          訊息鍵值
     * @param placeholders 佔位符對應表
     */
    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        TagResolver.Builder resolverBuilder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolverBuilder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        plugin.getMessageManager().send(sender, key, resolverBuilder.build());
    }

    /**
     * 發送訊息給接收者（單一佔位符）。
     *
     * @param sender 接收者
     * @param key    訊息鍵值
     * @param phKey  佔位符名稱
     * @param phVal  佔位符值
     */
    public void sendMessage(CommandSender sender, String key, String phKey, String phVal) {
        plugin.getMessageManager().send(sender, key, Placeholder.parsed(phKey, phVal));
    }
}
