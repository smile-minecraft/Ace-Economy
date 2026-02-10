package com.smile.aceeconomy.manager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 玩家名稱快取管理器。
 * <p>
 * 管理 {@code ace_users} 表，提供非同步的 Name ↔ UUID 解析，
 * 支援離線玩家操作。
 * </p>
 *
 * @author Smile
 */
public class UserCacheManager {

    private final com.smile.aceeconomy.storage.StorageProvider storageProvider;
    private final Logger logger;

    /**
     * 建立玩家名稱快取管理器。
     *
     * @param storageProvider 儲存提供者
     * @param logger          日誌記錄器
     */
    public UserCacheManager(com.smile.aceeconomy.storage.StorageProvider storageProvider, Logger logger) {
        this.storageProvider = storageProvider;
        this.logger = logger;
    }

    /**
     * 非同步更新玩家快取（Upsert）。
     * <p>
     * 通常在玩家加入伺服器時呼叫。
     * </p>
     *
     * @param uuid     玩家 UUID
     * @param username 玩家名稱
     */
    public void updateCache(UUID uuid, String username) {
        storageProvider.updatePlayerName(uuid, username);
    }

    /**
     * 非同步透過玩家名稱查詢 UUID。
     * <p>
     * 不區分大小寫。若找不到則回傳 {@code null}。
     * </p>
     *
     * @param username 玩家名稱
     * @return 包含 UUID 的 CompletableFuture，若找不到則為 null
     */
    public CompletableFuture<UUID> getUUID(String username) {
        return storageProvider.getUuidByName(username);
    }

    /**
     * 非同步透過 UUID 查詢玩家名稱。
     * <p>
     * 若找不到則回傳 {@code null}。
     * </p>
     *
     * @param uuid 玩家 UUID
     * @return 包含玩家名稱的 CompletableFuture，若找不到則為 null
     */
    public CompletableFuture<String> getName(UUID uuid) {
        return storageProvider.getNameByUuid(uuid);
    }
}
