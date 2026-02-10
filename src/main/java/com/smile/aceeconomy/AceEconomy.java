package com.smile.aceeconomy;

import com.smile.aceeconomy.api.EconomyProvider;
import com.smile.aceeconomy.commands.AdminCommand;
import com.smile.aceeconomy.commands.BalanceCommand;
import com.smile.aceeconomy.commands.PayCommand;
import com.smile.aceeconomy.commands.WithdrawCommand;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.hook.AceEcoExpansion;
import com.smile.aceeconomy.hook.VaultImpl;
import com.smile.aceeconomy.listeners.BanknoteListener;
import com.smile.aceeconomy.listeners.EconomyLogListener;
import com.smile.aceeconomy.manager.ConfigManager;
import com.smile.aceeconomy.manager.CurrencyManager;
import com.smile.aceeconomy.manager.MessageManager;
import com.smile.aceeconomy.service.DiscordWebhook;
import com.smile.aceeconomy.storage.DatabaseConnection;
import com.smile.aceeconomy.storage.JsonStorageHandler;
import com.smile.aceeconomy.storage.StorageHandler;
import com.smile.aceeconomy.storage.implementation.SQLStorageHandler;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * AceEconomy 主插件類別。
 * <p>
 * 一個符合 Folia 架構的經濟插件，提供執行緒安全的貨幣管理功能。
 * </p>
 *
 * @author Smile
 */
public final class AceEconomy extends JavaPlugin implements Listener {

    private static AceEconomy instance;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private DatabaseConnection databaseConnection;
    private StorageHandler storageHandler;
    private CurrencyManager currencyManager;
    private EconomyProvider economyProvider;
    private DiscordWebhook discordWebhook;
    private com.smile.aceeconomy.manager.LeaderboardManager leaderboardManager;

    /**
     * 取得插件實例。
     *
     * @return 插件實例
     */
    public static AceEconomy getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // 初始化設定檔管理器
        configManager = new ConfigManager(this);
        configManager.load();

        // 初始化訊息管理器
        messageManager = new MessageManager(this);
        messageManager.load(configManager.getLocale());

        // 初始化儲存處理器（根據設定選擇）
        initializeStorage();

        // 初始化貨幣管理器 (使用 ConfigManager 取代 defaultBalance 和 logger)
        currencyManager = new CurrencyManager(storageHandler, configManager);

        // 初始化日誌管理器
        com.smile.aceeconomy.manager.LogManager logManager = new com.smile.aceeconomy.manager.LogManager(this,
                databaseConnection, currencyManager);
        currencyManager.setLogManager(logManager);

        // 初始化排行榜管理器 (僅當使用 SQL 時)
        if (databaseConnection != null && databaseConnection.isHealthy()) {
            leaderboardManager = new com.smile.aceeconomy.manager.LeaderboardManager(this, databaseConnection);
        } else {
            getLogger().warning("未使用 SQL 資料庫或連線失敗，排行榜功能將失效。");
        }

        // 初始化經濟服務提供者
        economyProvider = new EconomyProvider(this);

        // 初始化 Discord Webhook
        discordWebhook = new DiscordWebhook(this);

        // 註冊 Native API 至 ServiceManager
        Bukkit.getServicesManager().register(
                EconomyProvider.class,
                economyProvider,
                this,
                ServicePriority.Normal);
        getLogger().info("已註冊 Native API (EconomyProvider)");

        // 嘗試掛鉤 Vault
        setupVault();

        // 嘗試掛鉤 PlaceholderAPI
        setupPlaceholderAPI();

        // 註冊指令
        registerCommands(logManager);

        // 註冊事件監聽器
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new BanknoteListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EconomyLogListener(this, discordWebhook), this);

        getLogger().info("AceEconomy 已啟用！");
    }

    /**
     * 初始化儲存處理器。
     * <p>
     * 根據設定檔選擇 JSON 或 SQL 儲存。
     * </p>
     */
    private void initializeStorage() {
        String storageType = configManager.getDatabaseType();

        if ("mysql".equalsIgnoreCase(storageType) || "sqlite".equalsIgnoreCase(storageType)) {
            // SQL 儲存
            databaseConnection = new DatabaseConnection(this);
            if (databaseConnection.initialize()) {
                // 執行資料庫遷移
                com.smile.aceeconomy.storage.SchemaManager schemaManager = new com.smile.aceeconomy.storage.SchemaManager(
                        this, databaseConnection);
                schemaManager.migrate();

                storageHandler = new SQLStorageHandler(this, databaseConnection);
                storageHandler.initialize();
                getLogger().info("使用 " + storageType.toUpperCase() + " 儲存");
            } else {
                getLogger().severe("SQL 連線失敗，回退至 JSON 儲存");
                storageHandler = new JsonStorageHandler(getDataFolder().toPath(), getLogger());
                storageHandler.initialize();
            }
        } else {
            // JSON 儲存（預設）
            storageHandler = new JsonStorageHandler(getDataFolder().toPath(), getLogger());
            storageHandler.initialize();
            getLogger().info("使用 JSON 儲存");
        }
    }

    /**
     * 註冊插件指令。
     */
    private void registerCommands(com.smile.aceeconomy.manager.LogManager logManager) {
        // /money, /balance, /bal
        BalanceCommand balanceCommand = new BalanceCommand(this);
        PluginCommand moneyCmd = getCommand("money");
        if (moneyCmd != null) {
            moneyCmd.setExecutor(balanceCommand);
            moneyCmd.setTabCompleter(balanceCommand);
        }

        // /pay
        PayCommand payCommand = new PayCommand(this);
        PluginCommand payCmd = getCommand("pay");
        if (payCmd != null) {
            payCmd.setExecutor(payCommand);
            payCmd.setTabCompleter(payCommand);
        }

        // /aceeco
        AdminCommand adminCommand = new AdminCommand(this);
        PluginCommand aceEcoCmd = getCommand("aceeco");
        if (aceEcoCmd != null) {
            aceEcoCmd.setExecutor(adminCommand);
            aceEcoCmd.setTabCompleter(adminCommand);
        }

        // 注入 LogManager 和子指令
        adminCommand.setLogManager(logManager);
        adminCommand.setHistoryCommand(new com.smile.aceeconomy.commands.HistoryCommand(this, logManager));
        adminCommand.setRollbackCommand(new com.smile.aceeconomy.commands.RollbackCommand(this, logManager));

        // 註冊自定義主指令別名
        String customAlias = configManager.getMainCommandAlias();
        if (!"aceeco".equalsIgnoreCase(customAlias)) {
            new com.smile.aceeconomy.utils.CommandRegistrar(this)
                    .registerCustomAlias(customAlias, adminCommand, adminCommand);
        }

        // /withdraw
        WithdrawCommand withdrawCommand = new WithdrawCommand(this);
        PluginCommand withdrawCmd = getCommand("withdraw");
        if (withdrawCmd != null) {
            withdrawCmd.setExecutor(withdrawCommand);
            withdrawCmd.setTabCompleter(withdrawCommand);
        }

        // /baltop
        if (leaderboardManager != null) {
            com.smile.aceeconomy.commands.BaltopCommand baltopCommand = new com.smile.aceeconomy.commands.BaltopCommand(
                    this, leaderboardManager);
            PluginCommand baltopCmd = getCommand("baltop");
            if (baltopCmd != null) {
                baltopCmd.setExecutor(baltopCommand);
                baltopCmd.setTabCompleter(baltopCommand);
            }
        }

        getLogger().info("已註冊所有指令");
    }

    /**
     * 設定 Vault 整合。
     * <p>
     * 若 Vault 插件已載入，註冊我們的經濟實作至 ServiceManager。
     * </p>
     */
    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("未偵測到 Vault，跳過 Vault 整合");
            return;
        }

        // 建立並註冊 Vault 實作
        VaultImpl vaultEconomy = new VaultImpl(this, economyProvider);
        Bukkit.getServicesManager().register(
                Economy.class,
                vaultEconomy,
                this,
                ServicePriority.Normal);

        // 使用 ANSI 綠色輸出成功訊息
        getLogger().info("\u001B[32m[AceEconomy] Vault 掛鉤成功！\u001B[0m");
    }

    /**
     * 設定 PlaceholderAPI 整合。
     * <p>
     * 若 PlaceholderAPI 插件已載入，註冊佔位符擴展。
     * </p>
     */
    private void setupPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("未偵測到 PlaceholderAPI，跳過佔位符整合");
            return;
        }

        // 註冊擴展
        new AceEcoExpansion(this).register();
        getLogger().info("\u001B[32m[AceEconomy] PlaceholderAPI 掛鉤成功！\u001B[0m");
    }

    @Override
    public void onDisable() {
        // 取消註冊所有服務
        Bukkit.getServicesManager().unregisterAll(this);

        // 關閉儲存處理器
        if (storageHandler != null) {
            storageHandler.shutdown();
        }

        // 關閉資料庫連線池
        if (databaseConnection != null) {
            databaseConnection.shutdown();
        }

        getLogger().info("AceEconomy 已停用！");
    }

    /**
     * 處理玩家非同步登入事件。
     * <p>
     * 在玩家登入前非同步載入帳戶資料至快取。
     * 此事件在非同步執行緒上觸發，可安全執行 I/O 操作。
     * </p>
     *
     * @param event 非同步登入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        // 若登入被拒絕，不載入資料
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID uuid = event.getUniqueId();
        String playerName = event.getName();

        // 非同步載入帳戶資料（此事件本身已在非同步執行緒）
        storageHandler.loadAccount(uuid).thenAccept(account -> {
            if (account == null) {
                // 帳戶不存在，建立新帳戶
                account = currencyManager.createAccount(uuid, playerName);
                // 儲存新帳戶
                storageHandler.saveAccount(account);
            } else {
                // 更新玩家名稱（可能已改名）
                account.setOwnerName(playerName);
                // 加入快取
                currencyManager.cacheAccount(account);
            }
            getLogger().info("已載入玩家資料: " + playerName);
        }).exceptionally(throwable -> {
            getLogger().severe("載入玩家資料時發生錯誤: " + playerName);
            throwable.printStackTrace();
            return null;
        });
    }

    /**
     * 處理玩家離線事件。
     * <p>
     * 使用 Folia 的非同步排程器儲存玩家資料，
     * 並從快取中移除帳戶。
     * </p>
     *
     * @param event 離線事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        Account account = currencyManager.getAccount(uuid);
        if (account == null) {
            return;
        }

        // 使用 Folia 的非同步排程器儲存資料
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            storageHandler.saveAccount(account).thenRun(() -> {
                getLogger().info("已儲存玩家資料: " + playerName);
            }).exceptionally(throwable -> {
                getLogger().severe("儲存玩家資料時發生錯誤: " + playerName);
                throwable.printStackTrace();
                return null;
            });
        });

        // 從快取中移除
        currencyManager.uncacheAccount(uuid);
    }

    /**
     * 取得貨幣管理器。
     *
     * @return 貨幣管理器實例
     */
    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }

    /**
     * 取得排行榜管理器。
     *
     * @return 排行榜管理器實例
     */
    public com.smile.aceeconomy.manager.LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    /**
     * 取得儲存處理器。
     *
     * @return 儲存處理器實例
     */
    public StorageHandler getStorageHandler() {
        return storageHandler;
    }

    /**
     * 取得經濟服務提供者。
     *
     * @return 經濟服務提供者實例
     */
    public EconomyProvider getEconomyProvider() {
        return economyProvider;
    }

    /**
     * 取得設定檔管理器。
     *
     * @return 設定檔管理器實例
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * 取得訊息管理器。
     *
     * @return 訊息管理器實例
     */
    public MessageManager getMessageManager() {
        return messageManager;
    }
}
