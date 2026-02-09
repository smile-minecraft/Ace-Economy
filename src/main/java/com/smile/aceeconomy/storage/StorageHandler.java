package com.smile.aceeconomy.storage;

import com.smile.aceeconomy.data.Account;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 資料儲存處理器介面。
 * <p>
 * 定義帳戶資料持久化的非同步操作方法。
 * 所有實作必須確保 I/O 操作不在主執行緒或區域執行緒上執行。
 * </p>
 *
 * @author Smile
 */
public interface StorageHandler {

    /**
     * 非同步載入玩家帳戶資料。
     * <p>
     * 若帳戶不存在，回傳 null。
     * </p>
     *
     * @param uuid 玩家的 UUID
     * @return 包含帳戶資料的 CompletableFuture，若不存在則為 null
     */
    CompletableFuture<Account> loadAccount(UUID uuid);

    /**
     * 非同步儲存玩家帳戶資料。
     *
     * @param account 要儲存的帳戶資料
     * @return 儲存完成時完成的 CompletableFuture
     */
    CompletableFuture<Void> saveAccount(Account account);

    /**
     * 初始化儲存處理器。
     * <p>
     * 用於建立必要的目錄結構或資料庫連線。
     * </p>
     */
    void initialize();

    /**
     * 關閉儲存處理器。
     * <p>
     * 用於釋放資源或關閉資料庫連線。
     * </p>
     */
    void shutdown();
}
