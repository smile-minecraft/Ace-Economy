package com.smile.aceeconomy.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 儲存提供者介面。
 * <p>
 * 定義所有資料庫操作的標準介面。
 * 所有方法皆回傳 CompletableFuture 以確保非同步執行。
 * </p>
 */
public interface StorageProvider {

    /**
     * 初始化儲存提供者。
     */
    void init();

    /**
     * 關閉儲存提供者並釋放資源。
     */
    void shutdown();

    /**
     * 取得玩家餘額。
     *
     * @param uuid     玩家 UUID
     * @param currency 貨幣 ID
     * @return 餘額
     */
    CompletableFuture<Double> getBalance(UUID uuid, String currency);

    /**
     * 設定玩家餘額。
     *
     * @param uuid     玩家 UUID
     * @param currency 貨幣 ID
     * @param amount   新餘額
     * @return 完成時的 Future
     */
    CompletableFuture<Void> setBalance(UUID uuid, String currency, double amount);

    /**
     * 取得玩家所有貨幣餘額。
     *
     * @param uuid 玩家 UUID
     * @return 貨幣 ID -> 餘額 的 Map
     */
    CompletableFuture<Map<String, Double>> getBalances(UUID uuid);

    /**
     * 取得排行榜。
     *
     * @param currency 貨幣 ID
     * @param limit    取前幾名
     * @return 玩家名稱 -> 餘額 的 Map (注意：這裡回傳名稱而非 UUID，方便顯示)
     *         或是 UUID -> Double?
     *         Prompt says: Map<String, Double> getTopAccounts(String currency, int
     *         limit)
     *         Usually we want Player Name for leaderboard.
     */
    CompletableFuture<Map<String, Double>> getTopAccounts(String currency, int limit);

    /**
     * 透過名稱取得 UUID。
     *
     * @param name 玩家名稱
     * @return 玩家 UUID
     */
    CompletableFuture<UUID> getUuidByName(String name);

    /**
     * 透過 UUID 取得名稱。
     *
     * @param uuid 玩家 UUID
     * @return 玩家名稱
     */
    CompletableFuture<String> getNameByUuid(UUID uuid);

    /**
     * 更新玩家名稱紀錄 (當玩家登入時調用)。
     *
     * @param uuid 玩家 UUID
     * @param name 最新名稱
     * @return 完成時的 Future
     */
    CompletableFuture<Void> updatePlayerName(UUID uuid, String name);
}
