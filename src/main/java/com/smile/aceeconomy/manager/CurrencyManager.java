package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

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
    private final Logger logger;

    /**
     * 預設初始餘額
     */
    private final double defaultBalance;

    /**
     * 建立貨幣管理器。
     *
     * @param storageHandler 儲存處理器
     * @param logger         日誌記錄器
     * @param defaultBalance 預設初始餘額
     */
    public CurrencyManager(StorageHandler storageHandler, Logger logger, double defaultBalance) {
        this.storageHandler = storageHandler;
        this.logger = logger;
        this.defaultBalance = defaultBalance;
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
        Account account = new Account(uuid, ownerName, defaultBalance);
        accountCache.put(uuid, account);
        logger.info("已建立新帳戶: " + ownerName + " (" + uuid + ")");
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
     * 取得玩家餘額。
     * <p>
     * 直接從快取讀取，使用讀取鎖確保一致性。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 玩家餘額，若帳戶不存在則回傳 0
     */
    public double getBalance(UUID uuid) {
        Account account = accountCache.get(uuid);
        if (account == null) {
            return 0.0;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.readLock().lock();
        try {
            return account.getBalance();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 存款至玩家帳戶。
     * <p>
     * 執行緒安全的餘額增加操作。
     * </p>
     *
     * @param uuid   玩家 UUID
     * @param amount 存款金額（必須為正數）
     * @return 操作是否成功
     */
    public boolean deposit(UUID uuid, double amount) {
        if (amount <= 0) {
            return false;
        }

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            double newBalance = account.getBalance() + amount;
            account.setBalance(newBalance);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 從玩家帳戶提款。
     * <p>
     * 執行緒安全的餘額減少操作，會檢查餘額是否足夠。
     * </p>
     *
     * @param uuid   玩家 UUID
     * @param amount 提款金額（必須為正數）
     * @return 操作是否成功（餘額足夠時回傳 true）
     */
    public boolean withdraw(UUID uuid, double amount) {
        if (amount <= 0) {
            return false;
        }

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            double currentBalance = account.getBalance();
            if (currentBalance < amount) {
                // 餘額不足
                return false;
            }
            account.setBalance(currentBalance - amount);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 設定玩家餘額。
     *
     * @param uuid   玩家 UUID
     * @param amount 新餘額（必須為非負數）
     * @return 操作是否成功
     */
    public boolean setBalance(UUID uuid, double amount) {
        if (amount < 0) {
            return false;
        }

        Account account = accountCache.get(uuid);
        if (account == null) {
            return false;
        }

        ReentrantReadWriteLock lock = getLock(uuid);
        lock.writeLock().lock();
        try {
            account.setBalance(amount);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
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
