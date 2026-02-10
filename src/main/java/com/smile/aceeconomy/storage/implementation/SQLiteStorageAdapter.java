package com.smile.aceeconomy.storage.implementation;

import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.StorageHandler;
import com.smile.aceeconomy.storage.StorageProvider;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite 儲存適配器。
 * <p>
 * 將 {@link StorageProvider} 適配為 {@link StorageHandler} 介面，
 * 用於維持向下相容性。
 * </p>
 *
 * @author Smile
 */
public class SQLiteStorageAdapter implements StorageHandler {

    private final StorageProvider storageProvider;

    public SQLiteStorageAdapter(StorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    @Override
    public CompletableFuture<Account> loadAccount(UUID uuid) {
        // StorageHandler 需要載入完整 Account 物件
        // 但 StorageProvider 只提供個別欄位查詢
        // 這裡需要組合多次查詢來建構 Account

        return storageProvider.getNameByUuid(uuid).thenCompose(username -> {
            if (username == null || "Unknown".equals(username)) {
                return CompletableFuture.completedFuture(null);
            }

            return storageProvider.getBalances(uuid).thenApply(balances -> {
                // 使用 Map 建構子建立 Account
                return new Account(uuid, username, balances);
            });
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(Account account) {
        // 儲存帳戶需要將每個貨幣的餘額分別寫入
        CompletableFuture<Void>[] futures = account.getBalances().entrySet().stream()
                .map(entry -> storageProvider.setBalance(
                        account.getOwner(),
                        entry.getKey(),
                        entry.getValue()))
                .toArray(CompletableFuture[]::new);

        // 同時更新玩家名稱
        CompletableFuture<Void> updateName = storageProvider.updatePlayerName(
                account.getOwner(),
                account.getOwnerName());

        // 等待所有操作完成
        return CompletableFuture.allOf(
                CompletableFuture.allOf(futures),
                updateName);
    }

    @Override
    public void initialize() {
        // StorageProvider 已經初始化過了
    }

    @Override
    public void shutdown() {
        // StorageProvider 會自己處理關閉
    }
}
