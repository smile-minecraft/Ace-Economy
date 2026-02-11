package com.smile.aceeconomy.storage.implementation;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.storage.SchemaManager;
import com.smile.aceeconomy.storage.StorageProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * SQLite 儲存實作。
 * <p>
 * 實作 {@link StorageProvider} 介面，使用 HikariCP 管理 SQLite 連線。
 * 提供完整的非同步資料庫操作，包含餘額管理與玩家快取。
 * </p>
 *
 * @author Smile
 */
public class SQLiteImplementation implements StorageProvider {

    private final AceEconomy plugin;
    private final Logger logger;

    private HikariDataSource dataSource;

    // Table names
    private static final String TABLE_BALANCES = "ace_balances";
    private static final String TABLE_USERS = "ace_users";

    /**
     * 建立 SQLite 儲存實作。
     *
     * @param plugin 插件實例
     */
    public SQLiteImplementation(AceEconomy plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @Override
    public void init() {
        try {
            // 設定 HikariCP
            HikariConfig config = new HikariConfig();

            File dbFile = new File(plugin.getDataFolder(), "database.db");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // SQLite 連線池設定 (單一連線)
            config.setPoolName("AceEconomy-SQLite-Pool");
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setIdleTimeout(300000);
            config.setMaxLifetime(600000);
            config.setConnectionTimeout(10000);
            config.setLeakDetectionThreshold(10000);

            // SQLite 特殊設定 (WAL mode for better concurrency)
            config.addDataSourceProperty("journal_mode", "WAL");

            dataSource = new HikariDataSource(config);

            // 測試連線
            try (Connection conn = dataSource.getConnection()) {
                logger.info("[AceEconomy] SQLite 連線池初始化成功: " + dbFile.getAbsolutePath());
            }

            // 執行資料庫遷移
            SchemaManager schemaManager = new SchemaManager(plugin, this::getConnection, false);
            schemaManager.migrate();

            // 修復可能的 NULL username (Leaderboard fix)
            fixNullUsernames();

            logger.info("[AceEconomy] SQLite 儲存提供者初始化完成");

        } catch (SQLException e) {
            logger.severe("SQLite 初始化失敗: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize SQLite storage", e);
        }
    }

    private void fixNullUsernames() {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                    java.sql.Statement stmt = conn.createStatement()) {

                int updated = stmt.executeUpdate(
                        """
                                UPDATE ace_balances
                                SET username = (SELECT username FROM ace_users WHERE ace_users.uuid = ace_balances.uuid)
                                WHERE username IS NULL AND EXISTS (SELECT 1 FROM ace_users WHERE ace_users.uuid = ace_balances.uuid)
                                """);

                if (updated > 0) {
                    logger.info("[AceEconomy] 自動修復了 " + updated + " 筆遺失的玩家名稱資料 (Leaderboard Fix)");
                }
            } catch (SQLException e) {
                logger.warning("[AceEconomy] 自動修復玩家名稱失敗: " + e.getMessage());
            }
        });
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[AceEconomy] SQLite 連線池已關閉");
        }
    }

    /**
     * 取得資料庫連線 (供 SchemaManager 使用)。
     *
     * @return 資料庫連線
     * @throws SQLException 若無法取得連線
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("資料庫連線池未初始化或已關閉");
        }
        return dataSource.getConnection();
    }

    /**
     * 檢查連線池是否健康。
     *
     * @return 連線池是否健康
     */
    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Double> getBalance(UUID uuid, String currency) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT balance FROM " + TABLE_BALANCES + " WHERE uuid = ? AND currency_id = ?";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("balance");
                    }
                }

            } catch (SQLException e) {
                logger.severe("取得餘額時發生錯誤 (" + uuid + ", " + currency + "): " + e.getMessage());
                e.printStackTrace();
            }

            return 0.0;
        });
    }

    @Override
    public CompletableFuture<Map<String, Double>> getBalances(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Double> balances = new HashMap<>();
            String sql = "SELECT currency_id, balance FROM " + TABLE_BALANCES + " WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        balances.put(rs.getString("currency_id"), rs.getDouble("balance"));
                    }
                }

            } catch (SQLException e) {
                logger.severe("取得玩家所有餘額失敗 (" + uuid + "): " + e.getMessage());
                e.printStackTrace();
            }

            return balances;
        });
    }

    @Override
    public CompletableFuture<Void> setBalance(UUID uuid, String currency, double amount) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO %s (uuid, currency_id, balance, username, last_updated)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT(uuid, currency_id) DO UPDATE SET
                        balance = excluded.balance,
                        username = excluded.username,
                        last_updated = CURRENT_TIMESTAMP
                    """.formatted(TABLE_BALANCES);

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, currency);
                pstmt.setDouble(3, amount);

                // Reuse the same connection to query username (avoid nested pool deadlock)
                String username = getNameByUuidSync(conn, uuid);
                pstmt.setString(4, username);

                pstmt.executeUpdate();

            } catch (SQLException e) {
                logger.severe("設定餘額時發生錯誤 (" + uuid + ", " + currency + "): " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Double>> getTopAccounts(String currency, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Double> leaderboard = new HashMap<>();

            String sql = """
                    SELECT username, balance FROM %s
                    WHERE currency_id = ? AND username IS NOT NULL
                    ORDER BY balance DESC
                    LIMIT ?
                    """.formatted(TABLE_BALANCES);

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, currency);
                pstmt.setInt(2, limit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String username = rs.getString("username");
                        double balance = rs.getDouble("balance");
                        leaderboard.put(username, balance);
                    }
                }

            } catch (SQLException e) {
                logger.severe("查詢排行榜時發生錯誤 (" + currency + "): " + e.getMessage());
                e.printStackTrace();
            }

            return leaderboard;
        });
    }

    @Override
    public CompletableFuture<UUID> getUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT uuid FROM " + TABLE_USERS + " WHERE LOWER(username) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, name.trim());

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("uuid"));
                    }
                }

            } catch (SQLException e) {
                logger.warning("查詢玩家 UUID 失敗 (" + name + "): " + e.getMessage());
            }

            return null;
        });
    }

    @Override
    public CompletableFuture<String> getNameByUuid(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> getNameByUuidSync(uuid));
    }

    /**
     * 同步版本的 getNameByUuid (內部使用)。
     *
     * @param uuid 玩家 UUID
     * @return 玩家名稱，若找不到則回傳 "Unknown"
     */
    private String getNameByUuidSync(UUID uuid) {
        try (Connection conn = dataSource.getConnection()) {
            return getNameByUuidSync(conn, uuid);
        } catch (SQLException e) {
            logger.warning("查詢玩家名稱失敗 (取得連線失敗, " + uuid + "): " + e.getMessage());
            return "Unknown";
        }
    }

    /**
     * 重用連線版本的 getNameByUuid (避免嵌套連線死鎖)。
     *
     * @param conn 資料庫連線
     * @param uuid 玩家 UUID
     * @return 玩家名稱，若找不到則回傳 "Unknown"
     */
    private String getNameByUuidSync(Connection conn, UUID uuid) {
        String sql = "SELECT username FROM " + TABLE_USERS + " WHERE uuid = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, uuid.toString());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("username");
                }
            }

        } catch (SQLException e) {
            logger.warning("查詢玩家名稱失敗 (" + uuid + "): " + e.getMessage());
        }

        return "Unknown";
    }

    @Override
    public CompletableFuture<Void> updatePlayerName(UUID uuid, String name) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO %s (uuid, username, last_seen)
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        username = excluded.username,
                        last_seen = excluded.last_seen
                    """.formatted(TABLE_USERS);

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name);
                pstmt.setLong(3, System.currentTimeMillis());
                pstmt.executeUpdate();

            } catch (SQLException e) {
                logger.warning("更新玩家名稱快取失敗 (" + name + "): " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<com.smile.aceeconomy.data.DataDump> dumpAllData() {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<com.smile.aceeconomy.data.UserRecord> users = new java.util.ArrayList<>();
            java.util.List<com.smile.aceeconomy.data.BalanceRecord> balances = new java.util.ArrayList<>();

            try (Connection conn = dataSource.getConnection()) {
                // Dump Users
                try (java.sql.Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT uuid, username FROM " + TABLE_USERS)) {
                    while (rs.next()) {
                        users.add(new com.smile.aceeconomy.data.UserRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("username")));
                    }
                }

                // Dump Balances
                try (java.sql.Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT uuid, currency_id, balance FROM " + TABLE_BALANCES)) {
                    while (rs.next()) {
                        balances.add(new com.smile.aceeconomy.data.BalanceRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("currency_id"),
                                rs.getDouble("balance")));
                    }
                }
            } catch (SQLException e) {
                logger.severe("資料導出失敗: " + e.getMessage());
                throw new RuntimeException(e);
            }
            return new com.smile.aceeconomy.data.DataDump(users, balances);
        });
    }

    @Override
    public CompletableFuture<Void> importData(com.smile.aceeconomy.data.DataDump dump) {
        return CompletableFuture.runAsync(() -> {
            String insertUser = "INSERT OR REPLACE INTO " + TABLE_USERS
                    + " (uuid, username, last_seen) VALUES (?, ?, ?)";
            // Updated to populate username
            String insertBalance = "INSERT OR REPLACE INTO " + TABLE_BALANCES
                    + " (uuid, currency_id, balance, username, last_updated) VALUES (?, ?, ?, ?, ?)";

            // Map UUID to Username for balance insertion
            Map<UUID, String> userMap = new HashMap<>();

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);

                try (PreparedStatement pstmt = conn.prepareStatement(insertUser)) {
                    for (com.smile.aceeconomy.data.UserRecord user : dump.users()) {
                        pstmt.setString(1, user.uuid().toString());
                        pstmt.setString(2, user.name());
                        pstmt.setLong(3, System.currentTimeMillis());
                        pstmt.addBatch();

                        userMap.put(user.uuid(), user.name());
                    }
                    pstmt.executeBatch();
                }

                try (PreparedStatement pstmt = conn.prepareStatement(insertBalance)) {
                    for (com.smile.aceeconomy.data.BalanceRecord balance : dump.balances()) {
                        pstmt.setString(1, balance.uuid().toString());
                        pstmt.setString(2, balance.currency());
                        pstmt.setDouble(3, balance.amount());

                        String username = userMap.getOrDefault(balance.uuid(), "Unknown");
                        pstmt.setString(4, username);

                        pstmt.setLong(5, System.currentTimeMillis());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }

                conn.commit();
                conn.setAutoCommit(true);
                logger.info("資料匯入成功: (" + dump.users().size() + " users, " + dump.balances().size() + " balances)");

            } catch (SQLException e) {
                logger.severe("資料匯入失敗: " + e.getMessage());
                // Try rollback
                try (Connection conn = dataSource.getConnection()) {
                    if (!conn.getAutoCommit())
                        conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
                throw new RuntimeException("Import failed", e);
            }
        });
    }
}
