package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
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
    private final MiniMessage miniMessage;

    private FileConfiguration config;
    private FileConfiguration messages;
    private File messagesFile;

    // 快取的設定值
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;

    private String currencySymbol;
    private String currencyFormat;
    private DecimalFormat moneyFormatter;

    private double startBalance;
    private String prefix;

    /**
     * 建立設定檔管理器。
     *
     * @param plugin 插件實例
     */
    public ConfigManager(AceEconomy plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * 載入所有設定檔。
     */
    public void load() {
        // 儲存並載入預設 config.yml
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // 儲存並載入 messages.yml
        saveDefaultMessages();
        loadMessages();

        // 快取設定值
        cacheConfigValues();

        plugin.getLogger().info("已載入設定檔");
    }

    /**
     * 重新載入所有設定檔。
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadMessages();
        cacheConfigValues();
        plugin.getLogger().info("已重新載入設定檔");
    }

    /**
     * 儲存預設 messages.yml。
     */
    private void saveDefaultMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    /**
     * 載入 messages.yml。
     */
    private void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");

        if (!messagesFile.exists()) {
            saveDefaultMessages();
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // 合併預設值
        InputStream defaultStream = plugin.getResource("messages.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaultConfig);
        }
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
        currencySymbol = config.getString("currency.symbol", "$");
        currencyFormat = config.getString("currency.format", "#,##0.00");
        moneyFormatter = new DecimalFormat(currencySymbol + currencyFormat);

        // 起始餘額
        startBalance = config.getDouble("start-balance", 1000.0);

        // 訊息前綴
        prefix = messages.getString("prefix", "<gold>[AceEconomy]</gold> <gray>");
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

    // ==================== 貨幣設定 ====================

    /**
     * 取得貨幣符號。
     *
     * @return 貨幣符號
     */
    public String getCurrencySymbol() {
        return currencySymbol;
    }

    /**
     * 取得貨幣格式。
     *
     * @return 貨幣格式字串
     */
    public String getCurrencyFormat() {
        return currencyFormat;
    }

    /**
     * 格式化金額。
     *
     * @param amount 金額
     * @return 格式化後的金額字串
     */
    public String formatMoney(double amount) {
        return moneyFormatter.format(amount);
    }

    /**
     * 取得新玩家起始餘額。
     *
     * @return 起始餘額
     */
    public double getStartBalance() {
        return startBalance;
    }

    // ==================== 訊息系統 ====================

    /**
     * 取得訊息前綴。
     *
     * @return 訊息前綴
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * 取得原始訊息字串。
     *
     * @param key 訊息鍵值
     * @return 原始訊息字串
     */
    public String getRawMessage(String key) {
        return messages.getString("messages." + key, "<red>訊息未定義: " + key + "</red>");
    }

    /**
     * 取得並解析訊息為 Component。
     *
     * @param key 訊息鍵值
     * @return 解析後的 Component
     */
    public Component getMessage(String key) {
        String raw = prefix + getRawMessage(key);
        return miniMessage.deserialize(raw);
    }

    /**
     * 取得並解析訊息為 Component（帶佔位符）。
     *
     * @param key          訊息鍵值
     * @param placeholders 佔位符對應表
     * @return 解析後的 Component
     */
    public Component getMessage(String key, Map<String, String> placeholders) {
        String raw = prefix + getRawMessage(key);

        TagResolver.Builder resolverBuilder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolverBuilder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }

        return miniMessage.deserialize(raw, resolverBuilder.build());
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
        String raw = prefix + getRawMessage(key);
        return miniMessage.deserialize(raw, Placeholder.parsed(phKey, phVal));
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
        String raw = prefix + getRawMessage(key);
        return miniMessage.deserialize(raw,
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
        sender.sendMessage(getMessage(key));
    }

    /**
     * 發送訊息給接收者（帶佔位符）。
     *
     * @param sender       接收者
     * @param key          訊息鍵值
     * @param placeholders 佔位符對應表
     */
    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(getMessage(key, placeholders));
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
        sender.sendMessage(getMessage(key, phKey, phVal));
    }
}
