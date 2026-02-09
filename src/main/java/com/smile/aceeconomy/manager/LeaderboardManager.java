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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // Multi-currency cache: currencyId -> CachedLeaderboard
    private final Map<String, CachedLeaderboard> leaderboardCache = new ConcurrentHashMap<>();
    private long cacheTimeMillis = 300 * 1000; // 5 minutes
    private int pageSize = 10;
    private boolean enabled = true;

    private final Map<String, AtomicBoolean> refreshingFlags = new ConcurrentHashMap<>();

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
     * 取得排行榜資料 (預設貨幣)。
     */
    public CompletableFuture<List<TopEntry>> getTopAccounts() {
        String defaultCurrency = getDefaultCurrencyId();
        return getTopAccounts(defaultCurrency);
    }

    /**
     * 取得指定貨幣的排行榜資料。
     *
     * @param currencyId 貨幣 ID
     * @return 排行榜列表 Future
     */
    public CompletableFuture<List<TopEntry>> getTopAccounts(String currencyId) {
        if (!enabled) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CachedLeaderboard cached = leaderboardCache.get(currencyId);
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.lastUpdated < cacheTimeMillis) && !cached.entries.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(cached.entries));
        }

        return refreshCache(currencyId);
    }

    /**
     * 強制重新整理快取 (預設貨幣)。
     */
    public CompletableFuture<List<TopEntry>> refreshCache() {
        return refreshCache(getDefaultCurrencyId());
    }

    /**
     * 強制重新整理指定貨幣的快取。
     *
     * @param currencyId 貨幣 ID
     * @return 更新後的排行榜列表 Future
     */
    public CompletableFuture<List<TopEntry>> refreshCache(String currencyId) {
        AtomicBoolean isRefreshing = refreshingFlags.computeIfAbsent(currencyId, k -> new AtomicBoolean(false));

        if (isRefreshing.get()) {
            CachedLeaderboard cached = leaderboardCache.get(currencyId);
            return CompletableFuture
                    .completedFuture(cached != null ? new ArrayList<>(cached.entries) : Collections.emptyList());
        }

        isRefreshing.set(true);

        return CompletableFuture.supplyAsync(() -> {
            List<TopEntry> newCache = new ArrayList<>();
            // Query from ace_balances for specific currency
            String sql = "SELECT uuid, username, balance FROM ace_balances WHERE currency_id = ? ORDER BY balance DESC LIMIT 100";

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, currencyId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        String uuidStr = rs.getString("uuid");
                        String username = rs.getString("username");
                        double balance = rs.getDouble("balance");

                        if (username == null || username.isEmpty()) {
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                                username = offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown Player";
                            } catch (Exception e) {
                                username = "Unknown";
                            }
                        }

                        newCache.add(new TopEntry(rank++, username, balance));
                    }
                }

                leaderboardCache.put(currencyId, new CachedLeaderboard(newCache, System.currentTimeMillis()));
                return newCache;

            } catch (SQLException e) {
                logger.severe("排行榜查詢失敗 (" + currencyId + "): " + e.getMessage());
                e.printStackTrace();
                CachedLeaderboard cached = leaderboardCache.get(currencyId);
                return cached != null ? new ArrayList<>(cached.entries) : Collections.emptyList();
            } finally {
                isRefreshing.set(false);
            }
        });
    }

    private String getDefaultCurrencyId() {
        if (plugin.getConfigManager() != null && plugin.getConfigManager().getDefaultCurrency() != null) {
            return plugin.getConfigManager().getDefaultCurrency().id();
        }
        return "dollar";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getLastUpdated(String currencyId) {
        CachedLeaderboard cached = leaderboardCache.get(currencyId);
        return cached != null ? cached.lastUpdated : 0;
    }

    public long getLastUpdated() {
        return getLastUpdated(getDefaultCurrencyId());
    }

    /**
     * 排行榜條目資料結構。
     */
    public record TopEntry(int rank, String name, double balance) {
    }

    /**
     * 快取的排行榜資料。
     */
    private record CachedLeaderboard(List<TopEntry> entries, long lastUpdated) {
    }
}
