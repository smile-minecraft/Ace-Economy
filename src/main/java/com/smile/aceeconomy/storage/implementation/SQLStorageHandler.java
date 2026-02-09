package com.smile.aceeconomy.storage.implementation;

import com.smile.aceeconomy.AceEconomy;
import com.smile.aceeconomy.data.Account;
import com.smile.aceeconomy.storage.DatabaseConnection;
import com.smile.aceeconomy.storage.StorageHandler;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * SQL 儲存處理器。
 * <p>
 * 使用 SQL 資料庫儲存玩家帳戶資料。
 * 所有 I/O 操作皆為非同步，不會阻塞主執行緒。
 * </p>
 *
 * @author Smile
 */
public class SQLStorageHandler implements StorageHandler {

    private final DatabaseConnection databaseConnection;
    private final Logger logger;

    private static final String TABLE_NAME = "ace_balances";

    // Select all balances for a user
    private static final String SELECT_BALANCES = "SELECT currency_id, balance FROM %s WHERE uuid = ?";

    // Select username (We need a separate table for users? Or just rely on one
    // entry?
    // In V5 schema, we removed `username` from `ace_balances`?
    // Wait, V1 `ace_economy` had `username`. V5 `ace_balances` creation SQL above
    // did NOT include `username`.
    // We lost username storage if we drop `ace_economy`.
    // The user plan didn't specify where to store username.
    // Usually plugins store username in a `ace_users` or similar, or just update it
    // on login in the balances table.
    // Let's check V5 SQL again: "uuid, currency_id, balance, last_updated". No
    // username.
    // We need a place to store username for `/baltop` or display.
    // I should probably add `username` to `ace_balances` for simplicity, or create
    // `ace_users`.
    // Adding to `ace_balances` means redundant username storage for every currency
    // row, but it's simplest for now.
    // OR create `ace_users` table. User request didn't specify `ace_users`.
    // Constraint: "Goal: Transform AceEconomy from a Single-Currency to a
    // Multi-Currency plugin."
    // Let's modify SQLStorageHandler to just use `ace_balances` and maybe I should
    // have added `username` to V5.
    // Let me check V5 SQL in SchemaManager again... I only defined uuid,
    // currency_id, balance.
    // I should updated V5 implementation to include username, or handle it.
    // If I don't store username, `Account` object needs it.
    // I will stick to adding `username` to `ace_balances` for now as redundant
    // column,
    // or better: assume `ace_user_data` or similar.
    // Given usage, let's create `ace_players` table in V5 as well? Or just add
    // username to `ace_balances`.
    // Redundant is fine for simpler migration.

    // UPDATE: I will perform a quick fix to SchemaManager V5 to include `username`
    // column,
    // matching V1 legacy behavior but per-row. It effectively acts as a cache.
    // Wait, if I change SchemaManager now, I need to do another tool call.
    // SQLStorageHandler needs `loadAccount` which requires `username`.

    // Let's assume for this step I will update SQLStorageHandler to use
    // `ace_balances` and expect `username` column?
    // No, I need to fix V5 first.

    // Let's look at `SQLStorageHandler` again.
    // `SELECT * FROM ace_economy WHERE uuid = ?` -> gives username.
    // I will quick-fix SchemaManager V5 to add `username` column.

    // AND update SQLStorageHandler queries.

    private static final String UPSERT_MYSQL = """
            INSERT INTO %s (uuid, currency_id, balance, username, last_updated)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                balance = VALUES(balance),
                username = VALUES(username),
                last_updated = CURRENT_TIMESTAMP
            """;

    private static final String UPSERT_SQLITE = """
            INSERT INTO %s (uuid, currency_id, balance, username, last_updated)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(uuid, currency_id) DO UPDATE SET
                balance = excluded.balance,
                username = excluded.username,
                last_updated = CURRENT_TIMESTAMP
            """;

    /**
     * 建立 SQL 儲存處理器。
     *
     * @param plugin             插件實例
     * @param databaseConnection 資料庫連線管理器
     */
    public SQLStorageHandler(AceEconomy plugin, DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.logger = plugin.getLogger();
    }

    @Override
    public void initialize() {
        // 資料表建立已移至 SchemaManager 處理
        logger.info("SQL 儲存處理器已初始化 (資料表由 SchemaManager 管理)");
    }

    @Override
    public CompletableFuture<Account> loadAccount(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            // Query all currency balances for a user, including username
            String query = "SELECT currency_id, balance, username FROM " + TABLE_NAME + " WHERE uuid = ?";
            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(query)) {

                pstmt.setString(1, uuid.toString());

                try (ResultSet rs = pstmt.executeQuery()) {
                    java.util.Map<String, Double> balances = new java.util.HashMap<>();
                    String username = "Unknown";
                    boolean found = false;

                    while (rs.next()) {
                        found = true;
                        String currency = rs.getString("currency_id");
                        double balance = rs.getDouble("balance");
                        if (rs.getString("username") != null) {
                            username = rs.getString("username");
                        }
                        balances.put(currency, balance);
                    }

                    if (found) {
                        return new Account(uuid, username, balances);
                    }
                }

            } catch (SQLException e) {
                logger.severe("載入帳戶時發生錯誤 (" + uuid + "): " + e.getMessage());
                e.printStackTrace();
            }

            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(Account account) {
        return CompletableFuture.runAsync(() -> {
            String sql = databaseConnection.isMySQL()
                    ? UPSERT_MYSQL.formatted(TABLE_NAME)
                    : UPSERT_SQLITE.formatted(TABLE_NAME);

            try (Connection conn = databaseConnection.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                // Batch update for all currencies
                conn.setAutoCommit(false);

                for (java.util.Map.Entry<String, Double> entry : account.getBalances().entrySet()) {
                    pstmt.setString(1, account.getOwner().toString());
                    pstmt.setString(2, entry.getKey()); // currency_id
                    pstmt.setDouble(3, entry.getValue());
                    pstmt.setString(4, account.getOwnerName());
                    pstmt.addBatch();
                }

                pstmt.executeBatch();
                conn.commit();
                conn.setAutoCommit(true);

            } catch (SQLException e) {
                logger.severe("儲存帳戶時發生錯誤 (" + account.getOwner() + "): " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public void shutdown() {
        // DatabaseConnection.shutdown() 由 AceEconomy 處理
        logger.info("SQL 儲存處理器已關閉");
    }

    /**
     * 取得資料庫連線管理器。
     *
     * @return 資料庫連線管理器
     */
    public DatabaseConnection getDatabaseConnection() {
        return databaseConnection;
    }
}
