package com.smile.aceeconomy.data;

import java.util.UUID;

/**
 * 帳戶資料物件。
 * <p>
 * 儲存玩家的經濟資料，包含擁有者 UUID、餘額和擁有者名稱。
 * </p>
 *
 * @author Smile
 */
public class Account {

    /**
     * 帳戶擁有者的唯一識別碼
     */
    private final UUID owner;

    /**
     * 帳戶餘額 (Currency ID -> Amount) - 使用 ConcurrentHashMap 確保執行緒安全
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Double> balances;

    /**
     * 帳戶擁有者的名稱（用於顯示）
     */
    private String ownerName;

    /**
     * 預設貨幣 ID (用於相容舊版 API)
     */
    private static final String DEFAULT_CURRENCY_ID = "dollar";

    /**
     * 建立新帳戶。
     *
     * @param owner           帳戶擁有者的 UUID
     * @param ownerName       帳戶擁有者的名稱
     * @param initialBalances 初始餘額 Map
     */
    public Account(UUID owner, String ownerName, java.util.Map<String, Double> initialBalances) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.balances = new java.util.concurrent.ConcurrentHashMap<>(initialBalances);
    }

    /**
     * 建立新帳戶 (單一貨幣，相容舊版)。
     *
     * @param owner     帳戶擁有者的 UUID
     * @param ownerName 帳戶擁有者的名稱
     * @param balance   初始餘額 (預設貨幣)
     */
    public Account(UUID owner, String ownerName, double balance) {
        this(owner, ownerName, java.util.Map.of(DEFAULT_CURRENCY_ID, balance));
    }

    /**
     * 取得帳戶擁有者的 UUID。
     *
     * @return 擁有者 UUID
     */
    public UUID getOwner() {
        return owner;
    }

    /**
     * 取得預設貨幣餘額 (相容舊版 API)。
     *
     * @return 目前餘額
     */
    public double getBalance() {
        return getBalance(DEFAULT_CURRENCY_ID);
    }

    /**
     * 取得指定貨幣的餘額。
     *
     * @param currencyId 貨幣 ID
     * @return 目前餘額，若無則回傳 0.0
     */
    public double getBalance(String currencyId) {
        return balances.getOrDefault(currencyId, 0.0);
    }

    /**
     * 設定預設貨幣餘額 (相容舊版 API)。
     *
     * @param balance 新餘額
     */
    public void setBalance(double balance) {
        setBalance(DEFAULT_CURRENCY_ID, balance);
    }

    /**
     * 設定指定貨幣的餘額。
     *
     * @param currencyId 貨幣 ID
     * @param balance    新餘額
     */
    public void setBalance(String currencyId, double balance) {
        balances.put(currencyId, balance);
    }

    /**
     * 取得所有餘額 Map (唯讀)。
     *
     * @return 餘額 Map
     */
    public java.util.Map<String, Double> getBalances() {
        return java.util.Collections.unmodifiableMap(balances);
    }

    /**
     * 取得帳戶擁有者的名稱。
     *
     * @return 擁有者名稱
     */
    public String getOwnerName() {
        return ownerName;
    }

    /**
     * 設定帳戶擁有者的名稱。
     *
     * @param ownerName 新的擁有者名稱
     */
    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }
}
