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
     * 取得預設貨幣 ID。
     */
    private String getDefaultCurrencyId() {
        if (plugin.getConfigManager() != null && plugin.getConfigManager().getDefaultCurrency() != null) {
            return plugin.getConfigManager().getDefaultCurrency().id();
        }
        return "dollar";
    }

    /**
     * 取得玩家餘額 (預設貨幣)。
     * 適用於 Vault 相容性。
     *
     * @param uuid 玩家 UUID
     * @return 包含餘額的 CompletableFuture
     */
    public CompletableFuture<Double> getBalance(UUID uuid) {
        return getBalance(uuid, getDefaultCurrencyId());
    }

    /**
     * 取得玩家指定貨幣的餘額。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @return 包含餘額的 CompletableFuture
     */
    public CompletableFuture<Double> getBalance(UUID uuid, String currencyId) {
        if (currencyManager.hasAccount(uuid)) {
            return CompletableFuture.completedFuture(currencyManager.getBalance(uuid, currencyId));
        }

        return plugin.getStorageHandler().loadAccount(uuid)
                .thenApply(account -> account != null ? account.getBalance(currencyId) : 0.0);
    }

    /**
     * 存款至玩家帳戶 (預設貨幣)。Vault 相容。
     *
     * @param uuid   玩家 UUID
     * @param amount 存款金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> deposit(UUID uuid, double amount) {
        return deposit(uuid, getDefaultCurrencyId(), amount);
    }

    /**
     * 存款至玩家帳戶 (指定貨幣)。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     存款金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> deposit(UUID uuid, String currencyId, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            if (amount <= 0) {
                return false;
            }
            if (!currencyManager.hasAccount(uuid)) {
                return false;
            }

            double currentBalance = currencyManager.getBalance(uuid, currencyId);
            EconomyTransactionEvent event = new EconomyTransactionEvent(
                    uuid, amount, EconomyTransactionEvent.TransactionType.DEPOSIT, currentBalance);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return false;
            }

            return currencyManager.deposit(uuid, currencyId, amount);
        });
    }

    /**
     * 從玩家帳戶提款 (預設貨幣)。Vault 相容。
     *
     * @param uuid   玩家 UUID
     * @param amount 提款金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> withdraw(UUID uuid, double amount) {
        return withdraw(uuid, getDefaultCurrencyId(), amount, null);
    }

    /**
     * 從玩家帳戶提款 (預設貨幣，指定支票 UUID)。
     *
     * @param uuid         玩家 UUID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> withdraw(UUID uuid, double amount, UUID banknoteUuid) {
        return withdraw(uuid, getDefaultCurrencyId(), amount, banknoteUuid);
    }

    /**
     * 從玩家帳戶提款 (指定貨幣)。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     提款金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> withdraw(UUID uuid, String currencyId, double amount) {
        return withdraw(uuid, currencyId, amount, null);
    }

    /**
     * 從玩家帳戶提款 (指定貨幣，指定支票 UUID)。
     *
     * @param uuid         玩家 UUID
     * @param currencyId   貨幣 ID
     * @param amount       提款金額
     * @param banknoteUuid 支票 UUID (可為 null)
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> withdraw(UUID uuid, String currencyId, double amount, UUID banknoteUuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (amount <= 0) {
                return false;
            }
            if (!currencyManager.hasAccount(uuid)) {
                return false;
            }

            double currentBalance = currencyManager.getBalance(uuid, currencyId);
            // 移除手動檢查，交由 CurrencyManager 處理 (含債務系統)
            // if (currentBalance < amount) { return false; }

            EconomyTransactionEvent event = new EconomyTransactionEvent(
                    uuid, amount, EconomyTransactionEvent.TransactionType.WITHDRAW, currentBalance);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return false;
            }

            return currencyManager.withdraw(uuid, currencyId, amount, banknoteUuid);
        });
    }

    /**
     * 設定玩家餘額 (預設貨幣)。
     *
     * @param uuid   玩家 UUID
     * @param amount 新餘額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> setBalance(UUID uuid, double amount) {
        return setBalance(uuid, getDefaultCurrencyId(), amount);
    }

    /**
     * 設定玩家指定貨幣的餘額。
     *
     * @param uuid       玩家 UUID
     * @param currencyId 貨幣 ID
     * @param amount     新餘額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> setBalance(UUID uuid, String currencyId, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            if (amount < 0 && !plugin.getConfigManager().isAllowNegativeBalance()) {
                return false;
            }
            if (!currencyManager.hasAccount(uuid)) {
                return false;
            }

            double currentBalance = currencyManager.getBalance(uuid, currencyId);
            EconomyTransactionEvent event = new EconomyTransactionEvent(
                    uuid, amount, EconomyTransactionEvent.TransactionType.SET, currentBalance);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return false;
            }

            return currencyManager.setBalance(uuid, currencyId, amount);
        });
    }

    /**
     * 在兩個玩家之間轉帳 (預設貨幣)。
     *
     * @param from   發送方 UUID
     * @param to     接收方 UUID
     * @param amount 轉帳金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> transfer(UUID from, UUID to, double amount) {
        return transfer(from, to, getDefaultCurrencyId(), amount);
    }

    /**
     * 在兩個玩家之間轉帳 (指定貨幣)。
     *
     * @param from       發送方 UUID
     * @param to         接收方 UUID
     * @param currencyId 貨幣 ID
     * @param amount     轉帳金額
     * @return 操作是否成功的 CompletableFuture
     */
    public CompletableFuture<Boolean> transfer(UUID from, UUID to, String currencyId, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            if (amount <= 0) {
                return false;
            }
            if (!currencyManager.hasAccount(from) || !currencyManager.hasAccount(to)) {
                return false;
            }

            double fromBalance = currencyManager.getBalance(from, currencyId);
            double toBalance = currencyManager.getBalance(to, currencyId);

            // 移除手動檢查，交由 CurrencyManager 處理
            // if (fromBalance < amount) { return false; }

            EconomyTransactionEvent fromEvent = new EconomyTransactionEvent(
                    from, amount, EconomyTransactionEvent.TransactionType.TRANSFER_OUT, fromBalance);
            Bukkit.getPluginManager().callEvent(fromEvent);
            if (fromEvent.isCancelled()) {
                return false;
            }

            EconomyTransactionEvent toEvent = new EconomyTransactionEvent(
                    to, amount, EconomyTransactionEvent.TransactionType.TRANSFER_IN, toBalance);
            Bukkit.getPluginManager().callEvent(toEvent);
            if (toEvent.isCancelled()) {
                return false;
            }

            boolean withdrawSuccess = currencyManager.withdraw(from, currencyId, amount, null);
            if (!withdrawSuccess) {
                return false;
            }

            boolean depositSuccess = currencyManager.deposit(to, currencyId, amount);
            if (!depositSuccess) {
                // Rollback
                currencyManager.deposit(from, currencyId, amount);
                return false;
            }

            return true;
        });
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
