package com.smile.aceeconomy.manager;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.storage.DatabaseConnection;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 排行榜管理器。
 * <p>
 * 使用 Cache-Aside 策略管理財富排行榜。
 * 查詢時若快取過期，會觸發非同步更新。
 * </p>
 *
 * @author Smile
 */
public class LeaderboardManager {

    private final AceEconomy plugin;
    private final DatabaseConnection databaseConnection;
    private final Logger logger;

    private List<TopEntry> cachedTopAccounts = new ArrayList<>();
    private long lastUpdated = 0;
    private long cacheTimeMillis = 300 * 1000; // 預設 5 分鐘
    private int pageSize = 10;
    private boolean enabled = true;

    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    public LeaderboardManager(AceEconomy plugin, DatabaseConnection databaseConnection) {
        this.plugin = plugin;
        this.databaseConnection = databaseConnection;
        this.logger = plugin.getLogger();
        reloadConfig();
    }

    /**
     * 重新載入設定。
     */
    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("leaderboard.enabled", true);
        this.cacheTimeMillis = plugin.getConfig().getLong("leaderboard.cache-time-seconds", 300) * 1000;
        this.pageSize = plugin.getConfig().getInt("leaderboard.page-size", 10);
    }

    /**
     * 取得排行榜資料 (非同步)。
     * <p>
     * 若快取有效，立即回傳快取資料。
     * 若快取過期，觸發更新並等待結果 (或回傳舊資料，視策略而定)。
     * 這裡採用的策略是：若過期，觸發更新並回傳 Future 等待新資料。
     * 這樣能確保玩家看到的是最新的 (在容許誤差內)。
     * </p>
     *
     * @return 排行榜列表 Future
     */
    public CompletableFuture<List<TopEntry>> getTopAccounts() {
        if (!enabled) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdated < cacheTimeMillis && !cachedTopAccounts.isEmpty()) {
            // 快取有效
            return CompletableFuture.completedFuture(new ArrayList<>(cachedTopAccounts));
        }

        // 快取過期或為空，觸發更新
        return refreshCache();
    }

    /**
     * 強制重新整理快取。
     *
     * @return 更新後的排行榜列表 Future
     */
    public CompletableFuture<List<TopEntry>> refreshCache() {
        // 若已經在更新中，則等待該次更新完成 (避免重複查詢)
        if (isRefreshing.get()) {
            // 這裡簡單回傳目前的快取 (即使是舊的)，或者可以實作等待。
            // 為了簡化，若在更新中，直接回傳舊資料 (如果是 null 則回傳空)
            // 但為了避免初次載入同時多人查詢導致的問題，最好是等待。
            // 不過 Cache-Aside 通常允許短暫的不一致。
            return CompletableFuture.completedFuture(new ArrayList<>(cachedTopAccounts));
        }

        isRefreshing.set(true);

        return CompletableFuture.supplyAsync(() -> {
            List<TopEntry> newCache = new ArrayList<>();
            String sql = "SELECT uuid, username, balance FROM ace_economy ORDER BY balance DESC LIMIT 100"; // 取前 100
                                                                                                            // 名備用

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    ResultSet rs = pstmt.executeQuery()) {

                int rank = 1;
                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    String username = rs.getString("username");
                    double balance = rs.getDouble("balance");

                    // 若 username 為 null (舊資料)，嘗試查詢
                    if (username == null || username.isEmpty()) {
                        try {
                            UUID uuid = UUID.fromString(uuidStr);
                            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                            if (offlinePlayer.getName() != null) {
                                username = offlinePlayer.getName();
                            } else {
                                username = "Unknown Player";
                            }
                        } catch (Exception e) {
                            username = "Unknown";
                        }
                    }

                    newCache.add(new TopEntry(rank++, username, balance));
                }

                // 更新快取
                synchronized (this) {
                    cachedTopAccounts = newCache;
                    lastUpdated = System.currentTimeMillis();
                }

                return newCache;

            } catch (SQLException e) {
                logger.severe("排行榜查詢失敗: " + e.getMessage());
                e.printStackTrace();
                return new ArrayList<>(cachedTopAccounts); // 失敗回傳舊資料
            } finally {
                isRefreshing.set(false);
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    /**
     * 排行榜條目資料結構。
     */
    public record TopEntry(int rank, String name, double balance) {
    }
}
