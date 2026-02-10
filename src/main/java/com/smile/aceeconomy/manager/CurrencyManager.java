package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 貨幣管理器。
 * <p>
 * 負責管理玩家帳戶的記憶體快取與餘額操作。
 * 使用 {@link ConcurrentHashMap} 儲存帳戶資料，
 * 並透過 {@link ReentrantReadWriteLock} 確保餘額操作的執行緒安全。
 * </p>
 *
 * @author Smile
 */
public class CurrencyManager {

    /**
     * 帳戶快取：UUID -> Account
     */
    private final ConcurrentHashMap<UUID, Account> accountCache = new ConcurrentHashMap<>();

    /**
     * 帳戶鎖定器：UUID -> ReadWriteLock (用於餘額操作)
     */
    private final ConcurrentHashMap<UUID, ReentrantReadWriteLock> accountLocks = new ConcurrentHashMap<>();

    private final StorageHandler storageHandler;
    private final ConfigManager configManager;
    private final AceEconomy plugin;
    private LogManager logManager;

    /**
     * 建立貨幣管理器。
     *
     * @param plugin         插件實例
     * @param storageHandler 儲存處理器
     * @param configManager  設定檔管理器
     */
    private final PermissionManager permissionManager;

    /**
     * 建立貨幣管理器。
     *
     * @param plugin            插件實例
     * @param permissionManager 權限管理器
     * @param storageHandler    儲存處理器
     * @param configManager     設定檔管理器
     */
    public CurrencyManager(AceEconomy plugin, PermissionManager permissionManager, StorageHandler storageHandler,
            ConfigManager configManager) {
        this.plugin = plugin;
        this.permissionManager = permissionManager;
        this.storageHandler = storageHandler;
        this.configManager = configManager;
    }

    public void setLogManager(LogManager logManager) {
        this.logManager = logManager;
    }

    /**
     * 取得所有已註冊的貨幣 ID。
     *
     * @return 貨幣 ID 集合
     */
    public java.util.Set<String> getRegisteredCurrencies() {
        return configManager.getCurrencies().keySet();
    }

    /**
     * 檢查貨幣 ID 是否存在 (Case-Insensitive, Whitespace-Safe)。
     *
     * @param currencyId 貨幣 ID
     * @return 是否存在
     */
    public boolean currencyExists(String currencyId) {
        if (currencyId == null) {
            return false;
        }
        return configManager.getCurrencies().containsKey(currencyId.trim().toLowerCase());
    }

    /**
     * 取得貨幣物件 (Case-Insensitive, Whitespace-Safe)。
     *
     * @param currencyId 貨幣 ID
     * @return 貨幣物件，若找不到回傳預設貨幣
     */
    public com.smile.aceeconomy.data.Currency getCurrency(String currencyId) {
        if (currencyId == null) {
            return configManager.getDefaultCurrency();
        }
        com.smile.aceeconomy.data.Currency currency = configManager.getCurrency(currencyId.trim().toLowerCase());
        return currency != null ? currency : configManager.getDefaultCurrency();
    }

    /**
     * 取得預設貨幣 ID。
     *
     * @return 預設貨幣 ID
     */
    public String getDefaultCurrencyId() {
        return configManager.getDefaultCurrency().id();
    }

    /**
     * 驗證貨幣 ID 是否有效。
     *
     * @param currencyId 貨幣 ID
     * @throws IllegalArgumentException 如果貨幣 ID 無效
     */
    private void validateCurrency(String currencyId) {
        if (!currencyExists(currencyId)) {
            throw new IllegalArgumentException("無效的貨幣 ID: " + currencyId);
        }
    }

    /**
     * 取得帳戶的讀寫鎖。
     *
     * @param uuid 玩家 UUID
     * @return 該帳戶的讀寫鎖
     */
    private ReentrantReadWriteLock getLock(UUID uuid) {
        return accountLocks.computeIfAbsent(uuid, k -> new ReentrantReadWriteLock());
    }

    /**
     * 建立新帳戶並加入快取。
     *
     * @param uuid      玩家 UUID
     * @param ownerName 玩家名稱
     * @return 建立的帳戶
     */
    public Account createAccount(UUID uuid, String ownerName) {
        Account account = new Account(uuid, ownerName, configManager.getStartBalance());
        accountCache.put(uuid, account);
        return account;
    }

    /**
     * 將帳戶加入快取。
     *
     * @param account 帳戶資料
     */
    public void cacheAccount(Account account) {
        accountCache.put(account.getOwner(), account);
    }

    /**
     * 從快取中移除帳戶。
     *
     * @param uuid 玩家 UUID
     */
    public void uncacheAccount(UUID uuid) {
        accountCache.remove(uuid);
        accountLocks.remove(uuid);
    }

    /**
     * 檢查帳戶是否已在快取中。
     *
     * @param uuid 玩家 UUID
     * @return 是否已快取
     */
    public boolean hasAccount(UUID uuid) {
        return accountCache.containsKey(uuid);
    }

    /**
     * 從快取中取得帳戶。
     *
     * @param uuid 玩家 UUID
     * @return 帳戶資料，若不存在則回傳 null
     */
    public Account getAccount(UUID uuid) {
        return accountCache.get(uuid);
    }

    /**
     * 取得玩家餘額 (預設貨幣)。
     *
     * @param uuid 玩家 UUID
     * @return 玩家餘額
     */
    public double getBalance(UUID uuid) {
        return getBalance(uuid, getDefaultCurrencyId());
    }

    /**
     * 取得玩家指定貨幣的餘額。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @return 玩家餘額，若帳戶不存在或該貨幣無記錄則回傳 0
     * @throws IllegalArgumentException 如果貨幣 ID 無效
     */
    public double getBalance(UUID uuid, String currencyId) {
        validateCurrency(currencyId);
        Account account = accountCache.get(uuid);
        if (account == null) {
            return 0.0;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.readLock().lock();
        try {
            return account.getBalance(currencyId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 檢查餘額是否足夠 (考慮債務上限)。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     所需金額
     * @return 是否足夠
     */
    public boolean hasEnough(UUID uuid, String currencyId, double amount) {
        if (amount <= 0)
            return true;

        double balance = getBalance(uuid, currencyId);

        // 若為預設貨幣且啟用負資產
        if (configManager.isAllowNegativeBalance() && currencyId.equals(getDefaultCurrencyId())) {
            double debtLimit = getDebtLimit(uuid);
            return balance - amount >= -debtLimit;
        }

        return balance >= amount;
    }

    /**
     * 存款至玩家帳戶 (預設貨幣)。
     *
     * @param uuid   玩家 UUID
     * @param amount 存款金額
     * @return 操作是否成功
     */
    public boolean deposit(UUID uuid, double amount) {
        return deposit(uuid, getDefaultCurrencyId(), amount);
    }

    /**
     * 存款至玩家帳戶 (指定貨幣)。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     存款金額
     * @return 操作是否成功
     * @throws IllegalArgumentException 如果貨幣 ID 無效
     */
    public boolean deposit(UUID uuid, String currencyId, double amount) {
        if (amount <= 0) {
            return false;
        }
        validateCurrency(currencyId);

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            double currentBalance = account.getBalance(currencyId);
            double newBalance = currentBalance + amount;
            account.setBalance(currencyId, newBalance);

            if (logManager != null) {
                logManager.logTransaction(null, uuid, amount, currencyId,
                        com.smile.aceeconomy.data.TransactionType.DEPOSIT, null, "System Deposit", null);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 從玩家帳戶提款 (預設貨幣)。
     */
    public boolean withdraw(UUID uuid, double amount) {
        return withdraw(uuid, getDefaultCurrencyId(), amount, null, false);
    }

    /**
     * 從玩家帳戶提款 (預設貨幣，指定支票 UUID)。
     *
     * @param uuid         玩家 UUID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID
     * @return 操作是否成功
     */
    public boolean withdraw(UUID uuid, double amount, UUID banknoteUuid) {
        return withdraw(uuid, getDefaultCurrencyId(), amount, banknoteUuid, false);
    }

    /**
     * 從玩家帳戶提款 (預設貨幣，強制模式)。
     *
     * @param uuid   玩家 UUID
     * @param amount 提款金額
     * @param force  是否強制（忽略餘額檢查）
     * @return 操作是否成功
     */
    public boolean withdraw(UUID uuid, double amount, boolean force) {
        return withdraw(uuid, getDefaultCurrencyId(), amount, null, force);
    }

    /**
     * 從玩家帳戶提款 (指定貨幣)。
     *
     * @param uuid         玩家 UUID
     * @param currencyId   貨幣 ID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID (可為 null)
     * @return 操作是否成功
     * @throws IllegalArgumentException 如果貨幣 ID 無效
     */
    public boolean withdraw(UUID uuid, String currencyId, double amount, UUID banknoteUuid) {
        return withdraw(uuid, currencyId, amount, banknoteUuid, false);
    }

    /**
     * 從玩家帳戶提款 (指定貨幣，完整參數)。
     *
     * @param uuid         玩家 UUID
     * @param currencyId   貨幣 ID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID (可為 null)
     * @param force        是否強制（忽略餘額檢查）
     * @return 操作是否成功
     */
    /**
     * 從玩家帳戶提款 (指定貨幣，完整參數)。
     *
     * @param uuid         玩家 UUID
     * @param currencyId   貨幣 ID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID (可為 null)
     * @param force        是否強制（忽略餘額檢查）
     * @return 操作是否成功
     * @throws IllegalArgumentException                                  如果貨幣 ID 無效
     * @throws com.smile.aceeconomy.exception.InsufficientFundsException 如果餘額不足且非強制
     */
    public boolean withdraw(UUID uuid, String currencyId, double amount, UUID banknoteUuid, boolean force) {
        if (amount <= 0) {
            return false;
        }
        validateCurrency(currencyId);

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            double currentBalance = account.getBalance(currencyId);

            // 檢查餘額 (若非強制)
            if (!force) {
                // 若為預設貨幣且啟用負資產，檢查債務上限
                if (configManager.isAllowNegativeBalance() && currencyId.equals(getDefaultCurrencyId())) {
                    double debtLimit = getDebtLimit(uuid);
                    if (currentBalance - amount < -debtLimit) {
                        throw new com.smile.aceeconomy.exception.InsufficientFundsException(
                                "餘額不足！您的債務上限為: " + debtLimit);
                    }
                } else {
                    // 一般檢查
                    if (currentBalance < amount) {
                        throw new com.smile.aceeconomy.exception.InsufficientFundsException("餘額不足！");
                    }
                }
            }

            account.setBalance(currencyId, currentBalance - amount);

            if (logManager != null) {
                logManager.logTransaction(uuid, null, amount, currencyId,
                        com.smile.aceeconomy.data.TransactionType.WITHDRAW, banknoteUuid, "System Withdraw", null);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 取得玩家的債務上限。
     *
     * @param uuid 玩家 UUID
     * @return 債務上限 (正數)
     */
    /**
     * 取得玩家的債務上限。
     *
     * @param uuid 玩家 UUID
     * @return 債務上限 (正數)
     */
    public double getDebtLimit(UUID uuid) {
        if (!configManager.isAllowNegativeBalance()) {
            return 0.0;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return permissionManager.getDebtLimit(player);
    }

    /**
     * 設定玩家餘額 (預設貨幣)。
     */
    public boolean setBalance(UUID uuid, double amount) {
        return setBalance(uuid, getDefaultCurrencyId(), amount);
    }

    /**
     * 設定玩家指定貨幣的餘額。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     新餘額
     * @return 操作是否成功
     * @throws IllegalArgumentException 如果貨幣 ID 無效
     */
    public boolean setBalance(UUID uuid, String currencyId, double amount) {
        if (amount < 0) {
            return false;
        }
        validateCurrency(currencyId);

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            double oldBalance = account.getBalance(currencyId);
            account.setBalance(currencyId, amount);

            if (logManager != null) {
                logManager.logTransaction(null, uuid, amount, currencyId,
                        com.smile.aceeconomy.data.TransactionType.SET, null, "Set Balance", oldBalance);
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 取得日誌管理器。
     *
     * @return 日誌管理器
     */
    public LogManager getLogManager() {
        return logManager;
    }

    /**
     * 取得儲存處理器。
     *
     * @return 儲存處理器實例
     */
    public StorageHandler getStorageHandler() {
        return storageHandler;
    }
}
