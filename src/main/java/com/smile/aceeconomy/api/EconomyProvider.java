package com.smile.aceeconomy.api;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.manager.CurrencyManager;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 經濟服務提供者。
 * <p>
 * 提供非同步的經濟操作 API，所有涉及潛在 I/O 的操作
 * 都回傳 {@link CompletableFuture} 以支援非阻塞操作。
 * </p>
 * <p>
 * 在執行任何餘額修改操作前，會觸發 {@link EconomyTransactionEvent}，
 * 允許其他插件攔截或取消交易。
 * </p>
 *
 * @author Smile
 */
public class EconomyProvider {

    private final AceEconomy plugin;
    private final CurrencyManager currencyManager;

    /**
     * 建立經濟服務提供者。
     *
     * @param plugin 插件實例
     */
    public EconomyProvider(AceEconomy plugin) {
        this.plugin = plugin;
        this.currencyManager = plugin.getCurrencyManager();
    }

    /**
     * 取得玩家餘額。
     * <p>
     * 若玩家在線，直接從快取讀取（即時）。
     * 若玩家離線，嘗試從儲存載入。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 包含餘額的 CompletableFuture
     */
    public CompletableFuture<Double> getBalance(UUID uuid) {
        // 優先從快取讀取
        if (currencyManager.hasAccount(uuid)) {
            return CompletableFuture.completedFuture(currencyManager.getBalance(uuid));
        }

        // 離線玩家：從儲存載入
        return plugin.getStorageHandler().loadAccount(uuid)
                .thenApply(account -> account != null ? account.getBalance() : 0.0);
    }

    /**
     * 存款至玩家帳戶。
     * <p>
     * 會觸發 {@link EconomyTransactionEvent}，若事件被取消則操作失敗。
     * </p>
     *
     * @param uuid   玩家 UUID
     * @param amount 存款金額（必須為正數）
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> deposit(UUID uuid, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        // 檢查帳戶是否在快取中
        if (!currencyManager.hasAccount(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        double currentBalance = currencyManager.getBalance(uuid);

        // 觸發交易事件
        EconomyTransactionEvent event = new EconomyTransactionEvent(
                uuid, amount, EconomyTransactionEvent.TransactionType.DEPOSIT, currentBalance);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(false);
        }

        // 執行存款
        boolean success = currencyManager.deposit(uuid, amount);
        return CompletableFuture.completedFuture(success);
    }

    /**
     * 從玩家帳戶提款。
     * <p>
     * 會觸發 {@link EconomyTransactionEvent}，若事件被取消則操作失敗。
     * 若餘額不足，操作也會失敗。
     * </p>
     *
     * @param uuid   玩家 UUID
     * @param amount 提款金額（必須為正數）
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> withdraw(UUID uuid, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        // 檢查帳戶是否在快取中
        if (!currencyManager.hasAccount(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        double currentBalance = currencyManager.getBalance(uuid);

        // 檢查餘額是否足夠（在觸發事件前）
        if (currentBalance < amount) {
            return CompletableFuture.completedFuture(false);
        }

        // 觸發交易事件
        EconomyTransactionEvent event = new EconomyTransactionEvent(
                uuid, amount, EconomyTransactionEvent.TransactionType.WITHDRAW, currentBalance);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(false);
        }

        // 執行提款
        boolean success = currencyManager.withdraw(uuid, amount);
        return CompletableFuture.completedFuture(success);
    }

    /**
     * 設定玩家餘額。
     * <p>
     * 會觸發 {@link EconomyTransactionEvent}，若事件被取消則操作失敗。
     * </p>
     *
     * @param uuid   玩家 UUID
     * @param amount 新餘額（必須為非負數）
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> setBalance(UUID uuid, double amount) {
        if (amount < 0) {
            return CompletableFuture.completedFuture(false);
        }

        // 檢查帳戶是否在快取中
        if (!currencyManager.hasAccount(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        double currentBalance = currencyManager.getBalance(uuid);

        // 觸發交易事件
        EconomyTransactionEvent event = new EconomyTransactionEvent(
                uuid, amount, EconomyTransactionEvent.TransactionType.SET, currentBalance);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(false);
        }

        // 執行設定餘額
        boolean success = currencyManager.setBalance(uuid, amount);
        return CompletableFuture.completedFuture(success);
    }

    /**
     * 在兩個玩家之間轉帳。
     * <p>
     * 會為發送方和接收方各觸發一個 {@link EconomyTransactionEvent}，
     * 任一事件被取消則整個轉帳操作失敗。
     * </p>
     *
     * @param from   發送方 UUID
     * @param to     接收方 UUID
     * @param amount 轉帳金額（必須為正數）
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> transfer(UUID from, UUID to, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        // 檢查雙方帳戶是否在快取中
        if (!currencyManager.hasAccount(from) || !currencyManager.hasAccount(to)) {
            return CompletableFuture.completedFuture(false);
        }

        double fromBalance = currencyManager.getBalance(from);
        double toBalance = currencyManager.getBalance(to);

        // 檢查發送方餘額
        if (fromBalance < amount) {
            return CompletableFuture.completedFuture(false);
        }

        // 觸發發送方事件
        EconomyTransactionEvent fromEvent = new EconomyTransactionEvent(
                from, amount, EconomyTransactionEvent.TransactionType.TRANSFER_OUT, fromBalance);
        Bukkit.getPluginManager().callEvent(fromEvent);

        if (fromEvent.isCancelled()) {
            return CompletableFuture.completedFuture(false);
        }

        // 觸發接收方事件
        EconomyTransactionEvent toEvent = new EconomyTransactionEvent(
                to, amount, EconomyTransactionEvent.TransactionType.TRANSFER_IN, toBalance);
        Bukkit.getPluginManager().callEvent(toEvent);

        if (toEvent.isCancelled()) {
            return CompletableFuture.completedFuture(false);
        }

        // 執行轉帳：先提款再存款
        boolean withdrawSuccess = currencyManager.withdraw(from, amount);
        if (!withdrawSuccess) {
            return CompletableFuture.completedFuture(false);
        }

        boolean depositSuccess = currencyManager.deposit(to, amount);
        if (!depositSuccess) {
            // 退回發送方的金額（回滾）
            currencyManager.deposit(from, amount);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.completedFuture(true);
    }

    /**
     * 檢查玩家帳戶是否已載入（在線）。
     *
     * @param uuid 玩家 UUID
     * @return 帳戶是否已載入
     */
    public boolean hasAccount(UUID uuid) {
        return currencyManager.hasAccount(uuid);
    }

    /**
     * 取得貨幣管理器（內部使用）。
     *
     * @return 貨幣管理器實例
     */
    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }
}
